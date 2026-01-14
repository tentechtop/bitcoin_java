package com.bit.coin.utils;


import com.bit.coin.structure.key.KeyInfo;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.crypto.MnemonicException;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.util.encoders.Hex;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

@Slf4j
public class Ed25519HDWallet {
    // Solana 的 BIP-44 路径参数
    private static final int BIP44_PURPOSE = 44;
    private static final int SOLANA_COIN_TYPE = 0;
    // Ed25519 密钥长度（私钥/公钥均为 32 字节）
    private static final int KEY_LENGTH = 32;
    // 助记词熵长度（12 个单词对应 128 位熵）
    private static final int MNEMONIC_ENTROPY_LENGTH = 16; // 16*8=128 bits

    // Ed25519 根密钥派生的 HMAC 盐值
    private static final byte[] ED25519_SEED = "ed25519 seed".getBytes(StandardCharsets.UTF_8);

    /**
     * 生成 12 个单词的助记词（BIP-39 标准）
     */
    public static List<String> generateMnemonic() {
        try {
            byte[] entropy = new byte[MNEMONIC_ENTROPY_LENGTH];
            new SecureRandom().nextBytes(entropy);
            return MnemonicCode.INSTANCE.toMnemonic(entropy);
        } catch (Exception e) {
            throw new RuntimeException("生成助记词失败", e);
        }
    }

    /**
     * 从助记词生成种子（BIP-39 标准）
     */
    public static byte[] generateSeed(List<String> mnemonic, String passphrase) {
        try {
            MnemonicCode.INSTANCE.check(mnemonic);
            return MnemonicCode.toSeed(mnemonic, passphrase);
        } catch (MnemonicException e) {
            throw new RuntimeException("生成种子失败", e);
        }
    }

    /**
     * 从种子生成 Ed25519 根私钥（BIP-32 标准）
     */
    public static byte[] generateRootPrivateKey(byte[] seed) {
        if (seed.length != 64) {
            throw new IllegalArgumentException("种子必须为 64 字节（512 位）");
        }
        HMac hmac = new HMac(new SHA512Digest());
        hmac.init(new KeyParameter(ED25519_SEED));
        hmac.update(seed, 0, seed.length);
        byte[] hmacResult = new byte[64]; // HMAC-SHA512 结果固定 64 字节
        hmac.doFinal(hmacResult, 0);
        return Arrays.copyOfRange(hmacResult, 0, KEY_LENGTH); // 前 32 字节为根私钥
    }

    /**
     * 派生 Solana 路径的子私钥（核心修正：正确处理 HMAC 结果）
     */
    public static byte[] deriveChildPrivateKey(byte[] parentPrivateKey, int index) {
        if (parentPrivateKey.length != KEY_LENGTH) {
            throw new IllegalArgumentException("父私钥必须为 32 字节");
        }

        // 1. 生成父公钥
        byte[] publicKey = Ed25519Signer.derivePublicKeyFromPrivateKey(parentPrivateKey);
        byte[] indexBytes = intToBytes(index);

        // 2. 构建 HMAC 输入（区分强化/非强化派生）
        byte[] hmacInput;
        if ((index & 0x80000000) != 0) { // 强化派生（带 ' 标记）
            hmacInput = new byte[1 + publicKey.length + indexBytes.length];
            hmacInput[0] = 0x00; // 固定前缀
            System.arraycopy(publicKey, 0, hmacInput, 1, publicKey.length);
            System.arraycopy(indexBytes, 0, hmacInput, 1 + publicKey.length, indexBytes.length);
        } else { // 非强化派生
            hmacInput = new byte[publicKey.length + indexBytes.length];
            System.arraycopy(publicKey, 0, hmacInput, 0, publicKey.length);
            System.arraycopy(indexBytes, 0, hmacInput, publicKey.length, indexBytes.length);
        }

        // 3. 计算 HMAC-SHA512（关键修正：先获取 64 字节完整结果，再截取前 32 字节）
        HMac hmac = new HMac(new SHA512Digest());
        hmac.init(new KeyParameter(parentPrivateKey));
        hmac.update(hmacInput, 0, hmacInput.length);
        byte[] hmacResult = new byte[64]; // 存储完整的 64 字节 HMAC 结果
        hmac.doFinal(hmacResult, 0);
        // 截取前 32 字节作为子私钥（符合 Ed25519 私钥长度）
        return Arrays.copyOfRange(hmacResult, 0, KEY_LENGTH);
    }

    /**
     * 生成 Solana 地址（公钥 Base58 编码）
     */
    public static String getSolanaAddress(byte[] publicKey) {
        if (publicKey.length != KEY_LENGTH) {
            throw new IllegalArgumentException("公钥必须为 32 字节");
        }
        return Base58.encode(publicKey);
    }

    /**
     * 从助记词生成 Solana 密钥对（完整流程）
     */
    public static KeyInfo getSolanaKeyPair(List<String> mnemonic, int accountIndex, int addressIndex) {
        byte[] seed = generateSeed(mnemonic, "");
        log.info("种子(hex): {}", Hex.toHexString(seed));

        byte[] rootPrivateKey = generateRootPrivateKey(seed);
        log.info("根私钥(hex): {}", Hex.toHexString(rootPrivateKey));

        // 派生路径：m/44'/501'/account'/0/addressIndex
        byte[] childKey = rootPrivateKey;
        childKey = deriveChildPrivateKey(childKey, BIP44_PURPOSE | 0x80000000); // 44'
        childKey = deriveChildPrivateKey(childKey, SOLANA_COIN_TYPE | 0x80000000); // 501'
        childKey = deriveChildPrivateKey(childKey, accountIndex | 0x80000000); // account'
        childKey = deriveChildPrivateKey(childKey, 0); // 0（非强化）
        childKey = deriveChildPrivateKey(childKey, addressIndex); // addressIndex（非强化）

        byte[] publicKey = Ed25519Signer.derivePublicKeyFromPrivateKey(childKey);
        String address = getSolanaAddress(publicKey);
        String path = String.format("m/44'/%d'/%d'/0/%d", SOLANA_COIN_TYPE, accountIndex, addressIndex);

        return new KeyInfo(childKey, publicKey, address, path);
    }

    /**
     * int 转 4 字节数组（大端序）
     */
    private static byte[] intToBytes(int value) {
        return new byte[]{
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        };
    }

    // 测试方法
    public static void main(String[] args) {
        List<String> mnemonic = generateMnemonic();
        System.out.println("助记词: " + String.join(" ", mnemonic));

        KeyInfo keyInfo = getSolanaKeyPair(mnemonic, 0, 0);
        System.out.println("派生路径: " + keyInfo.getPath());
        System.out.println("私钥(hex): " + Hex.toHexString(keyInfo.getPrivateKey()));
        System.out.println("公钥(hex): " + Hex.toHexString(keyInfo.getPublicKey()));
        System.out.println("Solana地址: " + keyInfo.getAddress());
        System.out.println("Solana地址长度"+keyInfo.getAddress().length());
        System.out.println("Solana地址: " +  Hex.toHexString(Base58.decode(keyInfo.getAddress())));

        // 验证地址一致性
        KeyInfo keyInfo2 = getSolanaKeyPair(mnemonic, 0, 0);
        System.out.println("地址一致性验证: " + keyInfo.getAddress().equals(keyInfo2.getAddress())); // true

        getSolanaKeyPair(mnemonic, 1, 0); // 改变账户
        getSolanaKeyPair(mnemonic, 0, 1); // 改变地址


        // 验证签名并计算耗时
        byte[] data = "测试Solana签名".getBytes(StandardCharsets.UTF_8);
        PrivateKey privateKey = Ed25519Signer.recoverPrivateKeyFromCore(keyInfo.getPrivateKey());
        PublicKey publicKey = Ed25519Signer.recoverPublicKeyFromCore(keyInfo.getPublicKey());

        // 计算签名耗时
        long signStartTime = System.nanoTime();
        byte[] signature = Ed25519Signer.applySignature(privateKey, data);
        long signEndTime = System.nanoTime();
        long signDurationNanos = signEndTime - signStartTime;
        double signDurationMillis = signDurationNanos / 1_000_000.0; // 转换为毫秒

        // 计算验证耗时
        long verifyStartTime = System.nanoTime();
        boolean verifyResult = Ed25519Signer.verifySignature(publicKey, data, signature);
        long verifyEndTime = System.nanoTime();
        long verifyDurationNanos = verifyEndTime - verifyStartTime;
        double verifyDurationMillis = verifyDurationNanos / 1_000_000.0; // 转换为毫秒

        System.out.println("签名耗时: " + signDurationMillis + " 毫秒 (" + signDurationNanos + " 纳秒)");
        System.out.println("验证耗时: " + verifyDurationMillis + " 毫秒 (" + verifyDurationNanos + " 纳秒)");
        System.out.println("签名验证结果: " + verifyResult); // true
    }
}