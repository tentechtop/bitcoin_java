package com.bit.coin.utils;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.Security;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;


/**
 * 基于Curve25519密钥交换和AES-GCM对称加密的实现（线程优化版）
 */
public class ECCWithAESGCM {

    // 静态加载BouncyCastle加密提供者（类加载时执行一次）
    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    // 线程局部变量存储Cipher实例，避免重复创建
    private static final ThreadLocal<Cipher> GCM_CIPHER = ThreadLocal.withInitial(() -> {
        try {
            return Cipher.getInstance("AES/GCM/NoPadding", BouncyCastleProvider.PROVIDER_NAME);
        } catch (Exception e) {
            throw new RuntimeException("初始化AES-GCM Cipher失败", e);
        }
    });

    // 线程局部变量存储SecureRandom，避免多线程竞争
    private static final ThreadLocal<SecureRandom> SECURE_RANDOM = ThreadLocal.withInitial(SecureRandom::new);

    // 线程局部变量存储X25519密钥对生成器
    private static final ThreadLocal<X25519KeyPairGenerator> KEY_PAIR_GENERATOR = ThreadLocal.withInitial(() -> {
        X25519KeyPairGenerator generator = new X25519KeyPairGenerator();
        generator.init(new X25519KeyGenerationParameters(SECURE_RANDOM.get()));
        return generator;
    });

    // AES-GCM相关参数
    private static final int GCM_IV_LENGTH = 12; // GCM推荐IV长度12字节
    private static final int GCM_TAG_LENGTH = 128; // 认证标签长度128位
    // 固定盐值（实际应用中可考虑通过安全方式交换）
    private static final byte[] HKDF_SALT;

    static {
        // 初始化固定盐值（类加载时生成一次）
        HKDF_SALT = new byte[32];
        new SecureRandom().nextBytes(HKDF_SALT);
    }

    /**
     * 生成Curve25519密钥对（私钥+公钥）
     * @return 包含私钥和公钥的数组 [私钥, 公钥]
     */
    public static byte[][] generateCurve25519KeyPair() {
        // 使用线程局部的生成器，避免重复初始化
        var keyPair = KEY_PAIR_GENERATOR.get().generateKeyPair();
        X25519PrivateKeyParameters privateKey = (X25519PrivateKeyParameters) keyPair.getPrivate();
        X25519PublicKeyParameters publicKey = (X25519PublicKeyParameters) keyPair.getPublic();
        return new byte[][]{
                privateKey.getEncoded(),
                publicKey.getEncoded()
        };
    }


    /**
     * 使用Curve25519进行密钥交换，生成共享密钥
     * @param privateKey 本地私钥（字节数组）
     * @param publicKey 对方公钥（字节数组）
     * @return 共享密钥（32字节）
     */
    public static byte[] generateSharedSecret(byte[] privateKey, byte[] publicKey) {
        X25519PrivateKeyParameters localPrivate = new X25519PrivateKeyParameters(privateKey, 0);
        X25519PublicKeyParameters remotePublic = new X25519PublicKeyParameters(publicKey, 0);

        byte[] sharedSecret = new byte[32];
        localPrivate.generateSecret(remotePublic, sharedSecret, 0);
        return sharedSecret;
    }


    /**
     * 使用HKDF从共享密钥派生AES-256密钥（确定性算法，确保双方一致）
     */
    public static SecretKey deriveAesKey(byte[] sharedSecret) throws Exception {
        // 使用HKDF算法派生密钥
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(sharedSecret, HKDF_SALT, "AES-GCM-256".getBytes(StandardCharsets.UTF_8)));

        byte[] aesKeyBytes = new byte[32]; // 256位AES密钥
        hkdf.generateBytes(aesKeyBytes, 0, 32);

        return new SecretKeySpec(aesKeyBytes, "AES");
    }

    /**
     * 使用AES-GCM加密数据
     * @param key AES密钥
     * @param plaintext 明文数据
     * @return 加密后的数据（格式：IV + 密文 + 认证标签）
     * @throws Exception 加密异常
     */
    public static byte[] aesGcmEncrypt(SecretKey key, byte[] plaintext) throws Exception {
        // 从线程局部获取随机数生成器
        SecureRandom random = SECURE_RANDOM.get();

        // 生成随机IV
        byte[] iv = new byte[GCM_IV_LENGTH];
        random.nextBytes(iv);

        // 初始化GCM参数
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);

        // 从线程局部获取Cipher并初始化
        Cipher cipher = GCM_CIPHER.get();
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec);

        // 执行加密（包含认证标签）
        byte[] ciphertext = cipher.doFinal(plaintext);

        // 拼接IV和密文（IV不需要加密，用于解密）
        return Arrays.concatenate(iv, ciphertext);
    }


    /**
     * 使用AES-GCM解密数据
     * @param key AES密钥
     * @param encryptedData 加密数据（格式：IV + 密文 + 认证标签）
     * @return 解密后的明文
     * @throws Exception 解密异常（包含认证失败）
     */
    public static byte[] aesGcmDecrypt(SecretKey key, byte[] encryptedData) throws Exception {
        // 分离IV和密文
        byte[] iv = Arrays.copyOfRange(encryptedData, 0, GCM_IV_LENGTH);
        byte[] ciphertext = Arrays.copyOfRange(encryptedData, GCM_IV_LENGTH, encryptedData.length);

        // 初始化GCM参数
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);

        // 从线程局部获取Cipher并初始化
        Cipher cipher = GCM_CIPHER.get();
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec);

        // 执行解密（自动验证标签）
        return cipher.doFinal(ciphertext);
    }


    // 测试示例
    public static void main(String[] args) throws Exception {
        // 1. 预生成密钥对（只生成一次，避免影响性能统计）
        byte[][] aliceKeys = generateCurve25519KeyPair();
        byte[] alicePrivateKey = aliceKeys[0];
        byte[] alicePublicKey = aliceKeys[1];

        //打印长度
        System.out.println("alicePrivateKey长度:"+alicePrivateKey.length);
        System.out.println("alicePublicKey长度:"+alicePublicKey.length);

        byte[][] bobKeys = generateCurve25519KeyPair();
        byte[] bobPrivateKey = bobKeys[0];
        byte[] bobPublicKey = bobKeys[1];

        // 2. 计算共享密钥
        byte[] aliceShared = generateSharedSecret(alicePrivateKey, bobPublicKey);
        byte[] bobShared = generateSharedSecret(bobPrivateKey, alicePublicKey);
        System.out.println("共享密钥是否一致: " + Arrays.areEqual(aliceShared, bobShared));
        System.out.println("共享密钥:"+bytesToHex(aliceShared));

        // 3. 派生AES密钥（只派生一次，复用密钥）
        SecretKey aliceAesKey = deriveAesKey(aliceShared);
        SecretKey bobAesKey = deriveAesKey(bobShared);

        // 待加密的消息（固定内容，避免内容变化影响性能）
        String originalMessage = "这是一条使用Curve25519和AES-GCM加密的消息11";
        byte[] messageBytes = originalMessage.getBytes(StandardCharsets.UTF_8);

        // 4. 循环测试10000次加密解密
        int loopCount = 10000;
        long totalTimeNanos = 0;

        for (int i = 0; i < loopCount; i++) {
            // 记录开始时间
            long start = System.nanoTime();

            // 加密
            byte[] encrypted = aesGcmEncrypt(aliceAesKey, messageBytes);
            // 解密
            byte[] decryptedBytes = aesGcmDecrypt(bobAesKey, encrypted);

            // 验证解密结果（确保正确性，可选）
            if (!Arrays.areEqual(decryptedBytes, messageBytes)) {
                throw new RuntimeException("第" + i + "次加密解密结果不一致");
            }

            // 累计耗时（纳秒）
            totalTimeNanos += System.nanoTime() - start;
        }

        // 计算平均时间
        double avgNanos = (double) totalTimeNanos / loopCount;
        double avgMicros = avgNanos / 1000;  // 转换为微秒
        double avgMillis = avgMicros / 1000;  // 转换为毫秒

        // 输出统计结果
        System.out.println("\n===== 性能统计 =====");
        System.out.println("循环次数: " + loopCount + "次");
        System.out.println("总耗时: " + (totalTimeNanos / 1_000_000) + "毫秒");
        System.out.println("单次加密解密平均耗时:");
        System.out.println("  - " + String.format("%.2f", avgMillis) + " 毫秒");
        System.out.println("  - " + String.format("%.2f", avgMicros) + " 微秒");
    }

    //public static void main(String[] args) throws Exception {
    //    // A节点生成自己的密钥对
    //    byte[][] aKeys = ECCWithAESGCM.generateCurve25519KeyPair();
    //    byte[] aPrivate = aKeys[0];
    //    byte[] aPublic = aKeys[1];
    //
    //    // B节点生成自己的密钥对（模拟，实际B节点独立生成）
    //    byte[][] bKeys = ECCWithAESGCM.generateCurve25519KeyPair();
    //    byte[] bPrivate = bKeys[0];
    //    byte[] bPublic = bKeys[1];
    //
    //    // A节点仅用自己的私钥 + B的公钥，协商共享密钥
    //    byte[] aShared = ECCWithAESGCM.generateSharedSecret(aPrivate, bPublic);
    //    SecretKey aAesKey = ECCWithAESGCM.deriveAesKey(aShared);
    //
    //    // 验证：A的AES密钥与B的AES密钥一致
    //    byte[] bShared = ECCWithAESGCM.generateSharedSecret(bPrivate, aPublic);
    //    SecretKey bAesKey = ECCWithAESGCM.deriveAesKey(bShared);
    //    System.out.println("A/B AES密钥是否一致：" + Arrays.areEqual(aAesKey.getEncoded(), bAesKey.getEncoded())); // true
    //
    //    // A节点用协商出的AES密钥加密数据，B可解密
    //    String msg = "A节点加密的消息";
    //    byte[] encrypted = ECCWithAESGCM.aesGcmEncrypt(aAesKey, msg.getBytes(StandardCharsets.UTF_8));
    //    byte[] decrypted = ECCWithAESGCM.aesGcmDecrypt(bAesKey, encrypted);
    //    System.out.println("解密结果：" + new String(decrypted)); // 输出：A节点加密的消息
    //}
}