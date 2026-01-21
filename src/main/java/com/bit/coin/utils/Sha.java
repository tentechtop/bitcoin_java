package com.bit.coin.utils;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.Security;

@Slf4j
public class Sha {
    // ThreadLocal存储每个线程独立的SHA-256实例
    private static final ThreadLocal<MessageDigest> SHA256_THREAD_LOCAL = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256", BouncyCastleProvider.PROVIDER_NAME);
        } catch (Exception e) {
            throw new RuntimeException("创建线程本地SHA-256实例失败", e);
        }
    });

    // ThreadLocal存储每个线程独立的SHA-512实例
    private static final ThreadLocal<MessageDigest> SHA512_THREAD_LOCAL = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-512", BouncyCastleProvider.PROVIDER_NAME);
        } catch (Exception e) {
            throw new RuntimeException("创建线程本地SHA-512实例失败", e);
        }
    });

    // ThreadLocal存储每个线程独立的SHA3-256实例
    private static final ThreadLocal<MessageDigest> SHA3_THREAD_LOCAL = ThreadLocal.withInitial(() -> {
        try {
            // SHA3-256在BouncyCastle中的标准名称是"SHA3-256"
            return MessageDigest.getInstance("SHA3-256", BouncyCastleProvider.PROVIDER_NAME);
        } catch (Exception e) {
            throw new RuntimeException("创建线程本地SHA3-256实例失败", e);
        }
    });

    private static final ThreadLocal<MessageDigest> RIPEMD160_THREAD_LOCAL = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("RIPEMD160", BouncyCastleProvider.PROVIDER_NAME);
        } catch (Exception e) {
            throw new RuntimeException("创建线程本地RIPEMD160实例失败", e);
        }
    });


    // ========== 新增 SHA3/Keccak 系列 ThreadLocal 实例 ==========
    // 标准SHA3-224 (NIST标准)
    private static final ThreadLocal<MessageDigest> SHA3_224_THREAD_LOCAL = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA3-224", BouncyCastleProvider.PROVIDER_NAME);
        } catch (Exception e) {
            throw new RuntimeException("创建线程本地SHA3-224实例失败", e);
        }
    });

    // 标准SHA3-256 (NIST标准)
    private static final ThreadLocal<MessageDigest> SHA3_256_THREAD_LOCAL = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA3-256", BouncyCastleProvider.PROVIDER_NAME);
        } catch (Exception e) {
            throw new RuntimeException("创建线程本地SHA3-256实例失败", e);
        }
    });

    // 标准SHA3-384 (NIST标准)
    private static final ThreadLocal<MessageDigest> SHA3_384_THREAD_LOCAL = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA3-384", BouncyCastleProvider.PROVIDER_NAME);
        } catch (Exception e) {
            throw new RuntimeException("创建线程本地SHA3-384实例失败", e);
        }
    });

    // 标准SHA3-512 (NIST标准)
    private static final ThreadLocal<MessageDigest> SHA3_512_THREAD_LOCAL = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA3-512", BouncyCastleProvider.PROVIDER_NAME);
        } catch (Exception e) {
            throw new RuntimeException("创建线程本地SHA3-512实例失败", e);
        }
    });

    // Keccak-256 (以太坊专用，区别于标准SHA3-256)
    private static final ThreadLocal<MessageDigest> KECCAK_256_THREAD_LOCAL = ThreadLocal.withInitial(() -> {
        try {
            // BouncyCastle中Keccak-256的标准名称是"Keccak-256"
            return MessageDigest.getInstance("Keccak-256", BouncyCastleProvider.PROVIDER_NAME);
        } catch (Exception e) {
            throw new RuntimeException("创建线程本地Keccak-256实例失败(以太坊专用)", e);
        }
    });




    // 静态代码块：确保BouncyCastle先注册
    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        // 提前验证BouncyCastle是否可用
        try {
            BouncyCastleProvider provider = (BouncyCastleProvider) Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
            if (provider == null) {
                throw new IllegalStateException("BouncyCastleProvider未注册，请检查依赖");
            }
            // 验证核心算法是否支持
            MessageDigest.getInstance("SHA-256", provider);
            MessageDigest.getInstance("SHA-512", provider);
            MessageDigest.getInstance("RIPEMD160", provider);

            // 验证新增的SHA3/Keccak系列算法
            MessageDigest.getInstance("SHA3-224", provider);
            MessageDigest.getInstance("SHA3-256", provider);
            MessageDigest.getInstance("SHA3-384", provider);
            MessageDigest.getInstance("SHA3-512", provider);
            MessageDigest.getInstance("Keccak-256", provider);



            // 提前触发ThreadLocal初始化（预热）
            SHA256_THREAD_LOCAL.get();
            SHA512_THREAD_LOCAL.get();
            SHA3_THREAD_LOCAL.get();
            RIPEMD160_THREAD_LOCAL.get();
            SHA3_224_THREAD_LOCAL.get();
            SHA3_256_THREAD_LOCAL.get();
            SHA3_384_THREAD_LOCAL.get();
            SHA3_512_THREAD_LOCAL.get();
            KECCAK_256_THREAD_LOCAL.get();
        } catch (Exception e) {
            throw new RuntimeException("哈希算法初始化验证失败：" + e.getMessage()
                    + "，请确保BouncyCastle依赖正确（建议版本1.68+）", e);
        }

    }



    /**
     * 线程安全的SHA-256计算（每个线程复用自己的实例）
     */
    public static byte[] applySHA256(byte[] data) {
        // 允许空数组（哈希计算空数组是合法的）
        data = data == null ? new byte[0] : data;
        MessageDigest digest = SHA256_THREAD_LOCAL.get();
        digest.reset();
        return digest.digest(data);
    }

    public static byte[][] batchApplySHA256(byte[][] dataList) {
        if (dataList == null || dataList.length == 0) {
            return new byte[0][];
        }
        MessageDigest digest = SHA256_THREAD_LOCAL.get();
        byte[][] results = new byte[dataList.length][];
        for (int i = 0; i < dataList.length; i++) {
            if (dataList[i] == null) {
                throw new IllegalArgumentException("批量输入中第" + i + "个数据为null");
            }
            // 移除digest.reset()，digest(data)会自动重置
            results[i] = digest.digest(dataList[i]);
        }
        return results;
    }

    public static byte[] applySHA256(ByteBuffer data) {
        if (data == null) {
            throw new IllegalArgumentException("输入数据不能为空");
        }
        MessageDigest digest = SHA256_THREAD_LOCAL.get();
        digest.reset();
        if (data.hasArray()) {
            // 堆缓冲区：直接使用底层数组
            digest.update(data.array(), data.arrayOffset() + data.position(), data.remaining());
        } else {
            // 堆外缓冲区：批量读取（复用ThreadLocal临时数组）
            ThreadLocal<byte[]> tempCache = ThreadLocal.withInitial(() -> new byte[4096]);
            byte[] temp = tempCache.get();
            int remaining = data.remaining();
            while (remaining > 0) {
                int len = Math.min(remaining, temp.length);
                data.get(temp, 0, len);
                digest.update(temp, 0, len);
                remaining -= len;
            }
        }
        return digest.digest();
    }

    /**
     * 线程安全的SHA-512计算
     */
    public static byte[] applySHA512(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("输入数据不能为空");
        }
        MessageDigest digest = SHA512_THREAD_LOCAL.get();
        digest.reset();
        return digest.digest(data);
    }

    /**
     * 线程安全的SHA3-256计算
     */
    public static byte[] applySha3(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("输入数据不能为空");
        }
        MessageDigest digest = SHA3_THREAD_LOCAL.get();
        digest.reset();
        return digest.digest(data); // 直接使用digest()，内部已包含update+digest
    }


    /**
     * 优化后的线程安全的RIPEMD160计算
     */
    public static byte[] applyRIPEMD160(byte[] data) {
        // 允许空输入，转为空数组处理（与其他哈希方法保持一致）
        data = data == null ? new byte[0] : data;
        // 复用线程本地的实例，避免重复创建
        MessageDigest digest = RIPEMD160_THREAD_LOCAL.get();
        digest.reset(); // 重置实例状态，避免上次计算残留影响
        return digest.digest(data);
    }



    /**
     * 手动清理线程本地资源（建议在线程池任务结束时调用）
     */
    public static void clearThreadLocals() {
        SHA256_THREAD_LOCAL.remove();
        SHA512_THREAD_LOCAL.remove();
        SHA3_THREAD_LOCAL.remove();
    }

    public static byte[] doubleSha256(byte[] serialized) {
        return applySHA256(applyRIPEMD160(serialized));
    }


    // ========== 新增 SHA3/Keccak 系列方法 ==========
    @Deprecated
    public static byte[] applySha3New(byte[] data) {
        return applySha3_256(data);
    }

    /**
     * 线程安全的标准SHA3-224计算（NIST标准）
     */
    public static byte[] applySha3_224(byte[] data) {
        data = data == null ? new byte[0] : data;
        MessageDigest digest = SHA3_224_THREAD_LOCAL.get();
        digest.reset();
        return digest.digest(data);
    }

    /**
     * 线程安全的标准SHA3-256计算（NIST标准）
     */
    public static byte[] applySha3_256(byte[] data) {
        data = data == null ? new byte[0] : data;
        MessageDigest digest = SHA3_256_THREAD_LOCAL.get();
        digest.reset();
        return digest.digest(data);
    }

    /**
     * 线程安全的标准SHA3-384计算（NIST标准）
     */
    public static byte[] applySha3_384(byte[] data) {
        data = data == null ? new byte[0] : data;
        MessageDigest digest = SHA3_384_THREAD_LOCAL.get();
        digest.reset();
        return digest.digest(data);
    }

    /**
     * 线程安全的标准SHA3-512计算（NIST标准）
     */
    public static byte[] applySha3_512(byte[] data) {
        data = data == null ? new byte[0] : data;
        MessageDigest digest = SHA3_512_THREAD_LOCAL.get();
        digest.reset();
        return digest.digest(data);
    }

    /**
     * 线程安全的Keccak-256计算（以太坊专用，区别于标准SHA3-256）
     * 以太坊黄皮书中的SHA3实际是Keccak-256，无后缀填充
     */
    public static byte[] applyKeccak256(byte[] data) {
        data = data == null ? new byte[0] : data;
        MessageDigest digest = KECCAK_256_THREAD_LOCAL.get();
        digest.reset();
        return digest.digest(data);
    }

    /**
     * 线程安全的Keccak-256计算（支持ByteBuffer输入，适配大内存场景）
     */
    public static byte[] applyKeccak256(ByteBuffer data) {
        if (data == null) {
            throw new IllegalArgumentException("输入数据不能为空");
        }
        MessageDigest digest = KECCAK_256_THREAD_LOCAL.get();
        digest.reset();

        if (data.hasArray()) {
            // 堆缓冲区：直接使用底层数组，避免拷贝
            digest.update(data.array(), data.arrayOffset() + data.position(), data.remaining());
        } else {
            // 堆外缓冲区：批量读取，减少系统调用
            ThreadLocal<byte[]> tempCache = ThreadLocal.withInitial(() -> new byte[4096]);
            byte[] temp = tempCache.get();
            int remaining = data.remaining();
            while (remaining > 0) {
                int len = Math.min(remaining, temp.length);
                data.get(temp, 0, len);
                digest.update(temp, 0, len);
                remaining -= len;
            }
        }
        return digest.digest();
    }
}