package com.bit.coin.p2p.protocol;

import com.bit.coin.utils.Ed25519Signer;
import com.bit.coin.utils.UInt256;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static com.bit.coin.utils.Ed25519Signer.fastVerify;
import static com.bit.coin.utils.SerializeUtils.hexToBytes;

@Data
public class RequestConnect {

    /**
     * 节点ID 32字节公钥
     */
    private byte[] peerId;

    /**
     * 节点版本20260303版本
     * 作用：协商协议兼容性，低版本节点可被拒绝/降级处理
     */
    private int version;

    /**
     * 协商加密信息 SecretKey 承载协商公钥
     */
    private byte[] sharedSecretPubKey;

    /**
     * 最新顶端区块hash
     */
    private byte[] latestHash;

    /**
     * 最新顶断区块高度
     */
    private int latestHeight;

    //顶端累计工作总量
    @JsonIgnore
    private UInt256 chainWork = UInt256.fromLong(0L);

    //签名
    private byte[] signature;

    // 空值默认填充数组（32字节0）
    private static final byte[] EMPTY_32_BYTES = new byte[32];
    // 签名空值默认填充数组（64字节0）
    private static final byte[] EMPTY_64_BYTES = new byte[64];

    /**
     * 预处理字段：将空字段替换为对应长度的0数组，长度不符自动修正
     * 核心：空值统一填充32字节0，保证后续序列化/签名逻辑不报错
     */
    private void preprocessFields() {
        // peerId：空值→32字节0，长度不符→修正为32字节（补0/截断）
        this.peerId = ensureLengthAndFillNull(this.peerId, 32);
        // sharedSecretPubKey：空值→32字节0
        this.sharedSecretPubKey = ensureLengthAndFillNull(this.sharedSecretPubKey, 32);
        // latestHash：空值→32字节0（核心需求）
        this.latestHash = ensureLengthAndFillNull(this.latestHash, 32);
        // chainWork：空值→默认0L的UInt256
        if (this.chainWork == null) {
            this.chainWork = UInt256.fromLong(0L);
        }
        // signature：空值→64字节0，长度不符→修正为64字节
        this.signature = ensureLengthAndFillNull(this.signature, 64);
    }

    /**
     * 通用字段处理方法：null填充为指定长度的0数组，长度不符自动修正
     * @param bytes 原始字节数组
     * @param targetLength 目标长度
     * @return 修正后的字节数组（非null，长度=targetLength）
     */
    private byte[] ensureLengthAndFillNull(byte[] bytes, int targetLength) {
        // 空值→返回对应长度的0数组
        if (bytes == null) {
            return targetLength == 32 ? EMPTY_32_BYTES : EMPTY_64_BYTES;
        }
        // 长度符合→直接返回
        if (bytes.length == targetLength) {
            return bytes;
        }
        // 长度不符→新建数组，补0/截断（保留前N字节，剩余补0）
        byte[] result = new byte[targetLength];
        System.arraycopy(bytes, 0, result, 0, Math.min(bytes.length, targetLength));
        return result;
    }

    /**
     * 序列化为二进制字节数组（大端序），适配P2P网络传输
     * 特性：空字段自动填充32字节0，长度不符自动修正，保证序列化不报错
     * @return 序列化后的字节数组
     * @throws IllegalArgumentException 预处理后仍非法（理论上不会触发）
     */
    public byte[] serialize() {
        // 第一步：预处理字段，空值填充32字节0，长度修正
        preprocessFields();

        // 第二步：最终校验（兜底，防止预处理逻辑异常）
        if (this.peerId.length != 32) {
            throw new IllegalArgumentException("peerId must be 32 bytes after preprocess");
        }
        if (this.sharedSecretPubKey.length != 32) {
            throw new IllegalArgumentException("sharedSecretPubKey must be 32 bytes after preprocess");
        }
        if (this.latestHash.length != 32) {
            throw new IllegalArgumentException("latestHash must be 32 bytes after preprocess");
        }
        if (this.signature.length != 64) {
            throw new IllegalArgumentException("signature must be 64 bytes after preprocess");
        }

        // 第三步：序列化写入
        ByteBuffer buffer = ByteBuffer.allocate(200);
        buffer.order(ByteOrder.BIG_ENDIAN); // 强制大端序（网络字节序）
        buffer.put(this.peerId);                // 32字节：节点ID
        buffer.putInt(this.version);            // 4字节：版本号
        buffer.put(this.sharedSecretPubKey);    // 32字节：协商公钥
        buffer.put(this.latestHash);            // 32字节：最新区块Hash（空则32字节0）
        buffer.putInt(this.latestHeight);       // 4字节：区块高度
        buffer.put(this.chainWork.toBytes());   // 32字节：累计工作总量
        buffer.put(this.signature);             // 64字节：签名

        return buffer.array();
    }

    // -------------------------- 反序列化方法 --------------------------
    /**
     * 从二进制字节数组反序列化为RequestConnect对象
     * @param data 序列化后的字节数组
     * @return RequestConnect实例
     * @throws IllegalArgumentException 数据非法时抛出
     */
    public static RequestConnect deserialize(byte[] data) {
        if (data == null || data.length != 200) {
            throw new IllegalArgumentException("Invalid data length for RequestConnect (must be 200 bytes)");
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.BIG_ENDIAN);

        RequestConnect request = new RequestConnect();

        // 读取32字节：peerId
        byte[] peerId = new byte[32];
        buffer.get(peerId);
        request.setPeerId(peerId);

        // 读取4字节：version
        request.setVersion(buffer.getInt());

        // 读取32字节：sharedSecretPubKey
        byte[] sharedSecretPubKey = new byte[32];
        buffer.get(sharedSecretPubKey);
        request.setSharedSecretPubKey(sharedSecretPubKey);

        // 读取32字节：latestHash（空则32字节0）
        byte[] latestHash = new byte[32];
        buffer.get(latestHash);
        request.setLatestHash(latestHash);

        // 读取4字节：latestHeight
        request.setLatestHeight(buffer.getInt());

        // 读取32字节：chainWork
        byte[] chainWorkBytes = new byte[32];
        buffer.get(chainWorkBytes);
        request.setChainWork(UInt256.fromBytes(chainWorkBytes));

        // 读取64字节：signature
        byte[] signature = new byte[64];
        buffer.get(signature);
        request.setSignature(signature);

        return request;
    }

    // -------------------------- 签名验证方法 --------------------------
    /**
     * 验证请求签名的合法性（防篡改、防伪造核心）
     * @return 签名是否合法
     */
    public boolean verifySignature() {
        // 预处理字段，保证待签名数据合法
        preprocessFields();

        if (this.signature.length != 64) {
            return false;
        }

        try {
            byte[] signatureData = getSignatureData();
            // 使用预处理后的peerId（32字节，空则0数组）验证签名
            return fastVerify(this.peerId, signatureData, this.signature);
        } catch (Exception e) {
            return false;
        }
    }

    // -------------------------- 获取签名数据方法 --------------------------
    /**
     * 获取待签名的原始数据（排除signature字段）
     * 特性：空字段自动填充32字节0，保证返回136字节的合法数据
     * @return 136字节的待签名数据（大端序）
     */
    public byte[] getSignatureData() {
        // 预处理字段，空值填充32字节0
        preprocessFields();

        ByteBuffer dataToSign = ByteBuffer.allocate(136);
        dataToSign.order(ByteOrder.BIG_ENDIAN);
        dataToSign.put(this.peerId);                // 32字节：节点ID（空则0）
        dataToSign.putInt(this.version);            // 4字节：版本号
        dataToSign.put(this.sharedSecretPubKey);    // 32字节：协商公钥（空则0）
        dataToSign.put(this.latestHash);            // 32字节：最新区块Hash（空则0）
        dataToSign.putInt(this.latestHeight);       // 4字节：区块高度
        dataToSign.put(this.chainWork.toBytes());   // 32字节：累计工作总量

        return dataToSign.array();
    }

    // -------------------------- 测试方法 --------------------------
    public static void main(String[] args) {
        // 测试用密钥
        String privateKeyHex = "3f3d0ba50617aeda45d570a7b80102b972fecc048b99ce97ffd87d386e043561";
        String publicKeyHex = "a0e33084712eaee563df61b26dbcf1a2fcc066a78e4c3d1d4097e9e3bee6c1b9";

        // 1. 构造测试对象（故意设置多个空字段，测试填充逻辑）
        RequestConnect original = new RequestConnect();
        original.setPeerId(hexToBytes(publicKeyHex)); // 正常32字节
        original.setVersion(11817);
        original.setSharedSecretPubKey(null); // 空值→自动填充32字节0
        original.setLatestHash(null);         // 空值→自动填充32字节0（核心需求）
        original.setLatestHeight(123456);
        original.setChainWork(null);          // 空值→默认0L的UInt256
        original.setSignature(null);          // 空值→自动填充64字节0

        // 2. 获取签名数据（测试空字段填充）
        byte[] signatureData = original.getSignatureData();
        System.out.println("待签名字节数组长度: " + signatureData.length); // 136
        System.out.println("latestHash是否为32字节0: " + isAllZero(original.getLatestHash())); // true

        // 3. 签名
        byte[] signature = Ed25519Signer.fastSign(hexToBytes(privateKeyHex), signatureData);
        original.setSignature(signature);

        // 4. 序列化（测试空字段处理）
        byte[] serialized = original.serialize();
        System.out.println("序列化后长度: " + serialized.length); // 200

        // 5. 反序列化
        RequestConnect deserialized = RequestConnect.deserialize(serialized);

        // 6. 验证结果
        System.out.println("版本号一致: " + (original.getVersion() == deserialized.getVersion())); // true
        System.out.println("PeerId长度: " + deserialized.getPeerId().length); // 32
        System.out.println("latestHash长度: " + deserialized.getLatestHash().length); // 32
        System.out.println("签名长度: " + deserialized.getSignature().length); // 64
        System.out.println("签名验证结果: " + deserialized.verifySignature()); // true
    }

    // 辅助测试方法：判断字节数组是否全为0
    private static boolean isAllZero(byte[] bytes) {
        if (bytes == null) return false;
        for (byte b : bytes) {
            if (b != 0) return false;
        }
        return true;
    }
}