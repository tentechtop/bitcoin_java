package com.bit.coin.structure.block;

import com.bit.coin.proto.Structure;
import com.google.protobuf.ByteString;
import lombok.Data;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Data
public class BlockHeader {
    /**
     * 区块版本号（4字节）
     */
    private int version;//1

    /**
     * 前一区块哈希（256位，32字节）
     */
    private byte[] previousHash;//2

    /**
     * 默克尔根哈希（256位，32字节）
     */
    private byte[] merkleRoot;//3

    /**
     * Unix时间戳（秒级，4字节）
     * 可改为long但序列化时需截取低4字节 -> int
     */
    private long time;//4

    /**
     * 挖矿难度目标（压缩存储，4字节）
     */
    private int bits;//5

    /**
     * 挖矿随机数（4字节）
     */
    private int nonce;//6


    // 1. 私有化无参构造，禁止外部调用
    private BlockHeader() {}

    // 2. 显式定义全参构造方法，强制初始化所有字段
    public BlockHeader(int version, byte[] previousHash, byte[] merkleRoot, long time, int bits, int nonce) {
        this.version = version;

        // 校验并处理前一区块哈希：确保是32字节（不符合则替换为32字节空数组）
        this.previousHash = (previousHash != null && previousHash.length == 32)
                ? previousHash : new byte[32];

        // 校验并处理默克尔根哈希：确保是32字节（不符合则替换为32字节空数组）
        this.merkleRoot = (merkleRoot != null && merkleRoot.length == 32)
                ? merkleRoot : new byte[32];

        this.time = time;
        this.bits = bits;
        this.nonce = nonce;
    }



    /**
     * 序列化区块头（严格遵循比特币协议）
     * 字段顺序：version(4字节) → previousHash(32字节) → merkleRoot(32字节) → time(4字节) → bits(4字节) → nonce(4字节)
     * 所有数值字段采用小端字节序（Little-Endian）
     */
    public byte[] serialize() {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(80)) {
            // 1. 版本号：4字节、小端
            bos.write(intToLittleEndian(this.version));

            // 2. 前一区块哈希：32字节（为空则补32字节空数组）
            byte[] prevHash = (this.previousHash != null && this.previousHash.length == 32)
                    ? this.previousHash : new byte[32];
            bos.write(prevHash);

            // 3. 默克尔根哈希：32字节（为空则补32字节空数组）
            byte[] merkleHash = (this.merkleRoot != null && this.merkleRoot.length == 32)
                    ? this.merkleRoot : new byte[32];
            bos.write(merkleHash);

            // 4. 时间戳：4字节、小端（截取long的低4字节转为int）
            bos.write(intToLittleEndian((int) this.time));

            // 5. 难度目标：4字节、小端
            bos.write(intToLittleEndian(this.bits));

            // 6. 随机数：4字节、小端
            bos.write(intToLittleEndian(this.nonce));

            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("区块头序列化失败", e);
        }
    }

    /**
     * 反序列化字节数组为BlockHeader（严格遵循比特币协议）
     */
    public static BlockHeader deserialize(byte[] data) {
        if (data == null || data.length != 80) {
            throw new IllegalArgumentException("无效的区块头字节数组，长度必须为80");
        }

        try (ByteArrayInputStream bis = new ByteArrayInputStream(data)) {
            BlockHeader header = new BlockHeader();
            byte[] buffer = new byte[4];

            // 1. 版本号：读取4字节并转为小端int
            bis.read(buffer);
            header.setVersion(littleEndianToInt(buffer));

            // 2. 前一区块哈希：读取32字节
            byte[] prevHash = new byte[32];
            bis.read(prevHash);
            header.setPreviousHash(prevHash);

            // 3. 默克尔根哈希：读取32字节
            byte[] merkleHash = new byte[32];
            bis.read(merkleHash);
            header.setMerkleRoot(merkleHash);

            // 4. 时间戳：读取4字节并转为小端int
            bis.read(buffer);
            header.setTime(littleEndianToInt(buffer));

            // 5. 难度目标：读取4字节并转为小端int
            bis.read(buffer);
            header.setBits(littleEndianToInt(buffer));

            // 6. 随机数：读取4字节并转为小端int
            bis.read(buffer);
            header.setNonce(littleEndianToInt(buffer));

            return header;
        } catch (IOException e) {
            throw new RuntimeException("区块头反序列化失败", e);
        }
    }

    // ========================== 工具方法：小端字节序转换 ==========================
    /**
     * int转小端字节序字节数组（4字节）
     */
    private byte[] intToLittleEndian(int value) {
        return ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(value)
                .array();
    }

    /**
     * 小端字节序字节数组转int（4字节）
     */
    private static int littleEndianToInt(byte[] bytes) {
        if (bytes.length != 4) {
            throw new IllegalArgumentException("字节数组长度必须为4");
        }
        return ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt();
    }

}
