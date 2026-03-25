package com.bit.coin.utils;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.encoders.Hex;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bit.coin.utils.Ed25519HDWallet.generateMnemonic;
import static com.bit.coin.utils.Ed25519HDWallet.getSolanaKeyPair;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static com.bit.coin.utils.SerializeUtils.hexToBytes;


/**
 * 用线程池并行签名 和 验证  现在是1毫秒一个验证 0.8毫秒一个签名 无法满足 每秒1万个签名和验证
 * 设计时无需考虑延展性
 */

@Slf4j
public class Ed25519Signer {
    // 线程局部缓存签名器和验证器（底层API无状态，可复用）
    private static final ThreadLocal<org.bouncycastle.crypto.signers.Ed25519Signer> SIGNER_THREAD_LOCAL = ThreadLocal.withInitial(org.bouncycastle.crypto.signers.Ed25519Signer::new);
    private static final ThreadLocal<org.bouncycastle.crypto.signers.Ed25519Signer> VERIFIER_THREAD_LOCAL = ThreadLocal.withInitial(org.bouncycastle.crypto.signers.Ed25519Signer::new);

    // CPU核心数（Ed25519是CPU密集型操作，线程数与核心数匹配）
    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();
    // 线程池（固定大小，避免线程创建销毁开销）
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
            CPU_CORES,
            CPU_CORES,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(100000), // 大缓冲队列
            new ThreadFactory() {
                private final AtomicInteger seq = new AtomicInteger(0);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "ed25519-worker-" + seq.getAndIncrement());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY + 1); // 提高优先级
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy() // 队列满时调用方执行，避免任务丢失
    );


    // 静态代码块：注册BouncyCastle Provider（优先于系统默认）
    // 签名缓存：key=公钥哈希+数据哈希（合并为字符串），value=签名结果（64字节）
    private static final com.github.benmanes.caffeine.cache.Cache<String, byte[]> SIGNATURE_CACHE;

    // ------------------------------ 线程局部变量缓存 Signature 实例 ------------------------------
    /**
     * 线程局部变量：缓存JDK原生Ed25519签名器（优先使用）
     */
    private static final ThreadLocal<Signature> JDK_SIGNER_THREAD_LOCAL = ThreadLocal.withInitial(() -> {
        try {
            return Signature.getInstance("Ed25519");
        } catch (NoSuchAlgorithmException e) {
            // 若JDK不支持，返回null（后续会使用BouncyCastle）
            return null;
        }
    });

    /**
     * 线程局部变量：缓存BouncyCastle的Ed25519签名器（降级方案）
     */
    private static final ThreadLocal<Signature> BC_SIGNER_THREAD_LOCAL = ThreadLocal.withInitial(() -> {
        try {
            return Signature.getInstance("Ed25519", "BC");
        } catch (Exception e) {
            throw new RuntimeException("初始化BouncyCastle签名器失败", e);
        }
    });

    /**
     * 线程局部变量：缓存JDK原生Ed25519验证器
     */
    private static final ThreadLocal<Signature> JDK_VERIFIER_THREAD_LOCAL = ThreadLocal.withInitial(() -> {
        try {
            return Signature.getInstance("Ed25519");
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    });

    /**
     * 线程局部变量：缓存BouncyCastle的Ed25519验证器
     */
    private static final ThreadLocal<Signature> BC_VERIFIER_THREAD_LOCAL = ThreadLocal.withInitial(() -> {
        try {
            return Signature.getInstance("Ed25519", "BC");
        } catch (Exception e) {
            throw new RuntimeException("初始化BouncyCastle验证器失败", e);
        }
    });

    private static final ThreadLocal<KeyFactory> ED25519_KEY_FACTORY = ThreadLocal.withInitial(() -> {
        try {
            // 优先尝试JDK原生实现
            return KeyFactory.getInstance("Ed25519");
        } catch (NoSuchAlgorithmException e) {
            // 降级使用BouncyCastle实现
            try {
                return KeyFactory.getInstance("Ed25519", "BC");
            } catch (NoSuchAlgorithmException | NoSuchProviderException ex) {
                throw new RuntimeException("初始化Ed25519 KeyFactory失败", ex);
            }
        }
    });


    static {
        Security.addProvider(new BouncyCastleProvider());
        SIGNATURE_CACHE   = Caffeine.newBuilder()
                .maximumSize(100_000_0) // 最大缓存10万条签名
                .expireAfterWrite(10, TimeUnit.MINUTES) // 5秒
                .recordStats() // 启用统计（便于监控命中率）
                .build();
    }

    // Ed25519核心密钥长度（公钥/私钥均为32字节）
    public static final int CORE_KEY_LENGTH = 32;
    // X.509公钥编码头部长度（固定12字节）
    static final int X509_HEADER_LENGTH = 12;
    // PKCS#8私钥编码头部长度（固定16字节）
    static final int PKCS8_HEADER_LENGTH = 16;
    // X.509公钥固定头部（用于补全32字节核心公钥）
    static final byte[] X509_PUBLIC_HEADER = new byte[]{
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };
    // PKCS#8私钥固定头部（用于补全32字节核心私钥）
    static final byte[] PKCS8_PRIVATE_HEADER = new byte[]{
            0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20
    };


    //根据助记词生成私钥  从私钥恢复公钥 HD 分层确定性


    /**
     * 生成 Ed25519 密钥对
     * @return 包含公钥（32字节）和私钥（32字节）的 KeyPair
     */
    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("Ed25519");
            return keyPairGenerator.generateKeyPair(); // 直接生成
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Ed25519 密钥对生成失败", e);
        }
    }


    /**
     * 优化后的Ed25519签名：复用fastSign的高性能实现，保持对PrivateKey对象的兼容
     * @param privateKey Ed25519 私钥对象（内部提取32字节核心私钥）
     * @param data 待签名原始数据
     * @return 签名结果（64字节）
     */
    public static byte[] applySignature(PrivateKey privateKey, byte[] data) {
        try {
            // 提取32字节核心私钥（跳过JCA对象的冗余信息）
            byte[] corePrivateKey = extractPrivateKeyCore(privateKey);
            // 直接调用高性能签名实现
            return fastSign(corePrivateKey, data);
        } catch (Exception e) {
            throw new RuntimeException("Ed25519 签名失败", e);
        }
    }

    /**
     * 优化后的Ed25519验签：复用fastVerify的高性能实现，保持对PublicKey对象的兼容
     * @param publicKey Ed25519 公钥对象（内部提取32字节核心公钥）
     * @param data 原始数据（需与签名时一致）
     * @param signature 签名结果（64字节）
     * @return true=验签成功，false=验签失败
     */
    public static boolean verifySignature(PublicKey publicKey, byte[] data, byte[] signature) {
        try {
            // 提取32字节核心公钥（跳过JCA对象的冗余信息）
            byte[] corePublicKey = extractPublicKeyCore(publicKey);
            // 直接调用高性能验签实现
            return fastVerify(corePublicKey, data, signature);
        } catch (Exception e) {
            log.error("Ed25519 验签异常", e);
            return false;
        }
    }


    /**
     * 字节数组转 Ed25519 公钥（使用ThreadLocal缓存KeyFactory提升性能）
     * @param publicKeyBytes X.509 编码的公钥字节（32字节核心+编码头）
     * @return Ed25519 公钥对象
     */
    public static PublicKey bytesToPublicKey(byte[] publicKeyBytes) {
        try {
            // 从ThreadLocal获取缓存的KeyFactory（避免重复创建）
            KeyFactory keyFactory = ED25519_KEY_FACTORY.get();

            // 处理32字节原始公钥：补全X.509固定头（12字节）
            byte[] x509Bytes;
            if (publicKeyBytes.length == 32) {
                // 复用类中已定义的X509_PUBLIC_HEADER常量，避免重复创建数组
                x509Bytes = new byte[X509_PUBLIC_HEADER.length + publicKeyBytes.length];
                System.arraycopy(X509_PUBLIC_HEADER, 0, x509Bytes, 0, X509_PUBLIC_HEADER.length);
                System.arraycopy(publicKeyBytes, 0, x509Bytes, X509_PUBLIC_HEADER.length, publicKeyBytes.length);
            } else {
                // 已为X.509编码（44字节），直接使用
                x509Bytes = publicKeyBytes;
            }

            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(x509Bytes);
            return keyFactory.generatePublic(keySpec);
        } catch (Exception e) {
            throw new RuntimeException("Ed25519 公钥转换失败", e);
        }
    }


    /**
     * 字节数组转 Ed25519 私钥（复用ThreadLocal缓存的KeyFactory）
     * @param privateKeyBytes PKCS#8 编码的私钥字节（32字节核心+编码头）
     * @return Ed25519 私钥对象
     */
    public static PrivateKey bytesToPrivateKey(byte[] privateKeyBytes) {
        try {
            // 复用同一个KeyFactory缓存（Ed25519算法通用）
            KeyFactory keyFactory = ED25519_KEY_FACTORY.get();
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
            return keyFactory.generatePrivate(keySpec);
        } catch (Exception e) {
            throw new RuntimeException("Ed25519 私钥转换失败", e);
        }
    }

    // ------------------------------ 公钥核心字节处理 ------------------------------

    /**
     * 从32字节核心公钥恢复公钥对象（自动补全X.509头部）
     * @param corePublicKey 32字节核心公钥
     * @return Ed25519公钥对象
     */
    public static PublicKey recoverPublicKeyFromCore(byte[] corePublicKey) {
        // 验证核心字节长度
        if (corePublicKey.length != CORE_KEY_LENGTH) {
            throw new IllegalArgumentException("核心公钥必须为32字节");
        }
        try {
            // 拼接X.509头部和核心字节，生成完整编码
            byte[] x509Encoded = new byte[X509_HEADER_LENGTH + CORE_KEY_LENGTH];
            System.arraycopy(X509_PUBLIC_HEADER, 0, x509Encoded, 0, X509_HEADER_LENGTH);
            System.arraycopy(corePublicKey, 0, x509Encoded, X509_HEADER_LENGTH, CORE_KEY_LENGTH);

            // 生成公钥对象
            KeyFactory keyFactory = getKeyFactory();
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(x509Encoded);
            return keyFactory.generatePublic(keySpec);
        } catch (Exception e) {
            throw new RuntimeException("从核心公钥恢复公钥失败", e);
        }
    }


    // ------------------------------ 私钥核心字节处理 ------------------------------

    /**
     * 从私钥对象中提取32字节核心字节（剔除PKCS#8头部）
     * @param privateKey Ed25519私钥对象
     * @return 32字节核心私钥
     */
    public static byte[] extractPrivateKeyCore(PrivateKey privateKey) {
        byte[] encoded = privateKey.getEncoded();
        // 验证编码长度（PKCS#8编码私钥应为48字节：16字节头 + 32字节核心）
        if (encoded.length != PKCS8_HEADER_LENGTH + CORE_KEY_LENGTH) {
            throw new IllegalArgumentException("无效的Ed25519私钥编码，长度应为48字节");
        }
        // 截取后32字节作为核心私钥
        byte[] core = new byte[CORE_KEY_LENGTH];
        System.arraycopy(encoded, PKCS8_HEADER_LENGTH, core, 0, CORE_KEY_LENGTH);
        return core;
    }

    /**
     * 从32字节核心私钥恢复私钥对象（自动补全PKCS#8头部）
     * @param corePrivateKey 32字节核心私钥
     * @return Ed25519私钥对象
     */
    public static PrivateKey recoverPrivateKeyFromCore(byte[] corePrivateKey) {
        // 验证核心字节长度
        if (corePrivateKey.length != CORE_KEY_LENGTH) {
            throw new IllegalArgumentException("核心私钥必须为32字节");
        }
        try {
            // 拼接PKCS#8头部和核心字节，生成完整编码
            byte[] pkcs8Encoded = new byte[PKCS8_HEADER_LENGTH + CORE_KEY_LENGTH];
            System.arraycopy(PKCS8_PRIVATE_HEADER, 0, pkcs8Encoded, 0, PKCS8_HEADER_LENGTH);
            System.arraycopy(corePrivateKey, 0, pkcs8Encoded, PKCS8_HEADER_LENGTH, CORE_KEY_LENGTH);

            // 生成私钥对象
            KeyFactory keyFactory = getKeyFactory();
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(pkcs8Encoded);
            return keyFactory.generatePrivate(keySpec);
        } catch (Exception e) {
            throw new RuntimeException("从核心私钥恢复私钥失败", e);
        }
    }


    // ------------------------------ 工具方法 ------------------------------

    /**
     * 获取Ed25519密钥工厂（优先JDK原生，降级BouncyCastle）
     */
    private static KeyFactory getKeyFactory() throws NoSuchAlgorithmException, NoSuchProviderException {
        try {
            return KeyFactory.getInstance("Ed25519");
        } catch (NoSuchAlgorithmException e) {
            return KeyFactory.getInstance("Ed25519", "BC");
        }
    }


    // 在 Ed25519Signer 中添加：
    /**
     * 从 Ed25519 私钥（32字节）派生公钥（32字节）
     * 核心逻辑：使用 Ed25519 算法从私钥计算公钥（非对称加密的核心步骤）
     */
    public static byte[] derivePublicKeyFromPrivateKey(byte[] privateKey) {
        if (privateKey.length != CORE_KEY_LENGTH) {
            throw new IllegalArgumentException("私钥必须为32字节");
        }
        // 使用 BouncyCastle 的 Ed25519 实现派生公钥
        Ed25519PrivateKeyParameters privateKeyParams = new Ed25519PrivateKeyParameters(privateKey, 0);
        Ed25519PublicKeyParameters publicKeyParams = privateKeyParams.generatePublicKey();
        return publicKeyParams.getEncoded();
    }

    // ------------------------------ 带缓存的签名/验签方法 ------------------------------

    /**
     * 带缓存的Ed25519签名：优先从缓存获取，未命中则签名并缓存
     * @param privateKey Ed25519私钥
     * @param data 待签名原始数据
     * @return 签名结果（64字节）
     */
    public static byte[] applySignatureWithCache(PrivateKey privateKey, byte[] data) {
        byte[] publicKeyBytes = derivePublicKeyFromPrivateKey(extractPrivateKeyCore(privateKey));
        String cacheKey = generateCacheKey(publicKeyBytes, data);

        byte[] cachedSignature = SIGNATURE_CACHE.getIfPresent(cacheKey);
        if (cachedSignature != null) {
            return cachedSignature;
        }

        // 调用修正后的签名方法
        byte[] signature = applySignature(privateKey, data);
        SIGNATURE_CACHE.put(cacheKey, signature);
        return signature;
    }


    /**
     * 带缓存的Ed25519验签：优先从缓存获取签名，未命中则执行验签
     * @param publicKey Ed25519公钥
     * @param data 原始数据
     * @param signature 签名结果（64字节）
     * @return true=验签成功，false=验签失败
     */
    public static boolean verifySignatureWithCache(PublicKey publicKey, byte[] data, byte[] signature) {
        byte[] publicKeyBytes = extractPublicKeyCore(publicKey);
        String cacheKey = generateCacheKey(publicKeyBytes, data);

        byte[] cachedSignature = SIGNATURE_CACHE.getIfPresent(cacheKey);
        if (cachedSignature != null) {
            return Arrays.equals(cachedSignature, signature);
        }

        // 调用修正后的验签方法
        return verifySignature(publicKey, data, signature);
    }


    // ------------------------------ 缓存辅助方法 ------------------------------

    /**
     * 生成缓存键：公钥哈希（前16字节） + 数据哈希（SHA-256前16字节），用冒号分隔
     * 注：哈希压缩是为了减少键的长度，避免内存占用过大
     */
    private static String generateCacheKey(byte[] publicKey, byte[] data) {
        return Hex.toHexString(publicKey) + ":" + Hex.toHexString(data);
    }

    /**
     * 从公钥对象中提取32字节核心公钥（剔除X.509头部）
     */
    public static byte[] extractPublicKeyCore(PublicKey publicKey) {
        byte[] encoded = publicKey.getEncoded();
        // X.509编码公钥应为44字节：12字节头 + 32字节核心
        if (encoded.length != X509_HEADER_LENGTH + CORE_KEY_LENGTH) {
            throw new IllegalArgumentException("无效的Ed25519公钥编码，长度应为44字节");
        }
        // 截取后32字节作为核心公钥
        byte[] core = new byte[CORE_KEY_LENGTH];
        System.arraycopy(encoded, X509_HEADER_LENGTH, core, 0, CORE_KEY_LENGTH);
        return core;
    }

    /**
     * 获取缓存统计信息（用于监控）
     */
    public static String getCacheStats() {
        CacheStats stats = SIGNATURE_CACHE.stats();
        return String.format(
                "签名缓存统计：命中率=%.2f%%, 总请求数=%d, 命中数=%d, 未命中数=%d",
                stats.hitRate() * 100,
                stats.requestCount(),
                stats.hitCount(),
                stats.missCount()
        );
    }

    /**
     * 手动清理缓存（如链重组时）
     */
    public static void clearCache() {
        SIGNATURE_CACHE.invalidateAll();
        log.info("签名缓存已清空");
    }


    /**
     * 高性能签名：直接调用BouncyCastle底层Ed25519实现
     */
    public static byte[] fastSign(byte[] privateKey, byte[] data) {
        org.bouncycastle.crypto.signers.Ed25519Signer signer = SIGNER_THREAD_LOCAL.get();
        Ed25519PrivateKeyParameters keyParams = new Ed25519PrivateKeyParameters(privateKey, 0);
        signer.init(true, keyParams);
        signer.update(data, 0, data.length);
        return signer.generateSignature();
    }

    /**
     * 高性能签名（支持ByteBuffer输入）：直接操作缓冲区数据，减少字节数组转换开销
     * @param privateKey 32字节Ed25519私钥
     * @param data 待签名数据的ByteBuffer（支持Heap/Direct ByteBuffer）
     * @return 64字节签名结果
     */
    public static byte[] fastSign(byte[] privateKey, ByteBuffer data) {
        org.bouncycastle.crypto.signers.Ed25519Signer signer = SIGNER_THREAD_LOCAL.get();
        Ed25519PrivateKeyParameters keyParams = new Ed25519PrivateKeyParameters(privateKey, 0);
        signer.init(true, keyParams);

        // 直接读取ByteBuffer数据，避免转换为byte[]（根据缓冲区类型优化）
        if (data.hasArray()) {
            // HeapByteBuffer：直接使用底层数组，减少拷贝
            byte[] array = data.array();
            int offset = data.arrayOffset() + data.position();
            int length = data.remaining();
            signer.update(array, offset, length);
        } else {
            // DirectByteBuffer：使用临时缓冲区批量读取（复用ThreadLocal缓存的数组减少创建）
            ThreadLocal<byte[]> directBufferCache = ThreadLocal.withInitial(() -> new byte[4096]); // 4KB默认缓存
            byte[] tempBuf = directBufferCache.get();
            int remaining = data.remaining();

            while (remaining > 0) {
                int readLength = Math.min(remaining, tempBuf.length);
                data.get(tempBuf, 0, readLength);
                signer.update(tempBuf, 0, readLength);
                remaining -= readLength;
            }
        }

        return signer.generateSignature();
    }

    /**
     * 高性能验签：直接调用BouncyCastle底层Ed25519实现
     */
    public static boolean fastVerify(byte[] publicKey, byte[] data, byte[] signature) {
        org.bouncycastle.crypto.signers.Ed25519Signer verifier = VERIFIER_THREAD_LOCAL.get();
        Ed25519PublicKeyParameters keyParams = new Ed25519PublicKeyParameters(publicKey, 0);
        verifier.init(false, keyParams);
        verifier.update(data, 0, data.length);
        return verifier.verifySignature(signature);
    }

    /**
     * 签名 / 验证的输入数据（data）若为频繁复用的对象，可改用ByteBuffer直接操作，减少System.arraycopy：
     * @param publicKey
     * @param data
     * @param signature
     * @return
     */
    public static boolean fastVerify(byte[] publicKey, ByteBuffer data, byte[] signature) {
        org.bouncycastle.crypto.signers.Ed25519Signer verifier = VERIFIER_THREAD_LOCAL.get();
        Ed25519PublicKeyParameters keyParams = new Ed25519PublicKeyParameters(publicKey, 0);
        verifier.init(false, keyParams);
        // 直接读取ByteBuffer，避免转换为byte[]
        byte[] buf = new byte[data.remaining()];
        data.get(buf); // 若data为DirectByteBuffer，可进一步优化
        verifier.update(buf, 0, buf.length);
        return verifier.verifySignature(signature);
    }



    // 批量签名（并行处理）
    public static List<byte[]> batchSign(List<byte[]> privateKeys, List<byte[]> dataList) throws InterruptedException, ExecutionException {
        int size = privateKeys.size();
        List<Future<byte[]>> futures = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            final byte[] key = privateKeys.get(i);
            final byte[] data = dataList.get(i);
            futures.add(EXECUTOR.submit(() -> fastSign(key, data)));
        }
        // 收集结果
        List<byte[]> signatures = new ArrayList<>(size);
        for (Future<byte[]> future : futures) {
            signatures.add(future.get());
        }
        return signatures;
    }

    // 批量验签（并行处理）
    public static List<Boolean> batchVerify(List<byte[]> publicKeys, List<byte[]> dataList, List<byte[]> signatures) throws InterruptedException, ExecutionException {
        int size = publicKeys.size();
        List<Future<Boolean>> futures = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            final byte[] pubKey = publicKeys.get(i);
            final byte[] data = dataList.get(i);
            final byte[] sig = signatures.get(i);
            futures.add(EXECUTOR.submit(() -> fastVerify(pubKey, data, sig)));
        }
        // 收集结果
        List<Boolean> results = new ArrayList<>(size);
        for (Future<Boolean> future : futures) {
            results.add(future.get());
        }
        return results;
    }


    // ------------------------------ 测试方法 ------------------------------

    /**
     * 清理线程局部变量（在线程池任务结束时调用，避免内存泄漏）
     */
    public static void clearThreadLocals() {
        JDK_SIGNER_THREAD_LOCAL.remove();
        BC_SIGNER_THREAD_LOCAL.remove();
        JDK_VERIFIER_THREAD_LOCAL.remove();
        BC_VERIFIER_THREAD_LOCAL.remove();
    }

    public static void main(String[] args) {
        //熵长度（字节）    熵长度（位）     校验和长度（位）    总比特数    单词数量（总比特数 ÷11）
        //16	         128	        4	             132	   12
        //20	         160	        5	             165	   15
        //24	         192	        6	             198	   18
        //28	         224	        7	             231	   21
        //32	         256	        8	             264	   24

        //12/15/18/21/24

        //路径 m/44'/501'/0'/0/0 → 地址 0 的私钥 A、公钥 A'
        //路径 m/44'/501'/0'/0/1 → 地址 1 的私钥 B、公钥 B'


        // 1. 生成助记词（固定助记词用于测试对比，实际使用时保持随机生成）
        List<String> mnemonic = generateMnemonic();
        System.out.println("===== 基础信息 =====");
        System.out.println("助记词: " + String.join(" ", mnemonic));
        System.out.println("---------------------");

        // 2. 生成基准密钥对（路径：m/44'/501'/0'/0/0）
        KeyInfo baseKey = getSolanaKeyPair(mnemonic, 0, 0);
        System.out.println("===== 基准密钥对（账户0，地址0） =====");
        System.out.println("派生路径: " + baseKey.getPath());
        System.out.println("私钥(hex): " + Hex.toHexString(baseKey.getPrivateKey()));
        System.out.println("公钥(hex): " + Hex.toHexString(baseKey.getPublicKey()));
        System.out.println("Solana地址: " + baseKey.getAddress());
        System.out.println("Solana地址: " + bytesToHex(Base58.decode(baseKey.getAddress())));

        System.out.println("---------------------");

        // 3. 验证公钥与地址的一致性
        byte[] corePriv = baseKey.getPrivateKey();
        byte[] corePub = Ed25519Signer.derivePublicKeyFromPrivateKey(corePriv);
        String addressFromPub = Base58.encode(corePub);
        System.out.println("===== 公钥与地址一致性验证 =====");
        System.out.println("公钥长度: " + corePub.length + "字节（应为32）");
        System.out.println("私钥长度: " + corePriv.length + "字节（应为32）");
        System.out.println("公钥派生地址: " + addressFromPub);
        System.out.println("地址一致性: " + addressFromPub.equals(baseKey.getAddress()) + "（应为true）");
        System.out.println("---------------------");

        // 4. 验证签名功能
        byte[] testData = Sha.applySHA256("123 Solana HD Wallet Test".getBytes(StandardCharsets.UTF_8));
        PrivateKey privateKey = Ed25519Signer.recoverPrivateKeyFromCore(corePriv);
        PublicKey publicKey = Ed25519Signer.recoverPublicKeyFromCore(corePub);
        byte[] signature = Ed25519Signer.applySignature(privateKey, testData);
        boolean verifyResult = Ed25519Signer.verifySignature(publicKey, testData, signature);
        System.out.println("===== 签名验证 =====");
        System.out.println("签名长度: " + signature.length + "字节（应为64）");
        System.out.println("验签结果: " + verifyResult + "（应为true）");
        System.out.println("---------------------");

        // 5. 改变账户索引（路径：m/44'/501'/1'/0/0）
        KeyInfo account1Key = getSolanaKeyPair(mnemonic, 1, 0);
        System.out.println("===== 改变账户索引（账户1，地址0） =====");
        System.out.println("派生路径: " + account1Key.getPath());
        System.out.println("Solana地址: " + account1Key.getAddress());
        System.out.println("与基准地址是否不同: " + !account1Key.getAddress().equals(baseKey.getAddress()) + "（应为true）");
        System.out.println("---------------------");

        // 6. 改变地址索引（路径：m/44'/501'/0'/0/1）
        KeyInfo address1Key = getSolanaKeyPair(mnemonic, 0, 1);
        System.out.println("===== 改变地址索引（账户0，地址1） =====");
        System.out.println("派生路径: " + address1Key.getPath());
        System.out.println("Solana地址: " + address1Key.getAddress());
        System.out.println("与基准地址是否不同: " + !address1Key.getAddress().equals(baseKey.getAddress()) + "（应为true）");
        System.out.println("与账户1地址是否不同: " + !address1Key.getAddress().equals(account1Key.getAddress()) + "（应为true）");
        System.out.println("---------------------");

        // 7. 验证同路径地址一致性（重复生成相同路径）
        KeyInfo baseKey2 = getSolanaKeyPair(mnemonic, 0, 0);
        KeyInfo account1Key2 = getSolanaKeyPair(mnemonic, 1, 0);
        KeyInfo address1Key2 = getSolanaKeyPair(mnemonic, 0, 1);
        System.out.println("===== 同路径地址一致性验证 =====");
        System.out.println("基准路径重复生成一致性: " + baseKey.getAddress().equals(baseKey2.getAddress()) + "（应为true）");
        System.out.println("账户1重复生成一致性: " + account1Key.getAddress().equals(account1Key2.getAddress()) + "（应为true）");
        System.out.println("地址1重复生成一致性: " + address1Key.getAddress().equals(address1Key2.getAddress()) + "（应为true）");


        PublicKey publicKey1 = Ed25519Signer.recoverPublicKeyFromCore(hexToBytes("291b960b5ca8591653bea10d11afd883b240616a02ba49acc82be4fcd3aa5dc3"));
        PrivateKey privateKey1 = Ed25519Signer.recoverPrivateKeyFromCore(hexToBytes("46405ee67af3e85c64ff84c6a6df9a3ac4739e71a2147cb02f4e36b70127487d"));

        byte[] signature1 = Ed25519Signer.applySignature(privateKey1, testData);
        boolean verifyResult1 = Ed25519Signer.verifySignature(publicKey1, testData, signature1);
        System.out.println("===== 签名验证 =====");
        System.out.println("签名长度: " + signature1.length + "字节（应为64）");
        System.out.println("验签结果: " + verifyResult1 + "（应为true）");
        System.out.println("签名一致性: " +bytesToHex(signature1));
    }
}
