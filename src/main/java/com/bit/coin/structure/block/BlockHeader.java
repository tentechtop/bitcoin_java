package com.bit.coin.structure.block;

import com.bit.coin.utils.HexByteArraySerializer;
import com.bit.coin.utils.Sha;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static com.bit.coin.utils.SerializeUtils.littleEndianToInt;
import static org.bouncycastle.util.Pack.intToLittleEndian;

@Data
public class BlockHeader {
    /**
     * 区块版本号（4字节）
     */
    private int version;//1

    /**
     * 前一区块哈希（256位，32字节）
     */
    @JsonSerialize(using = HexByteArraySerializer.class)
    private byte[] previousHash;//2

    /**
     * 默克尔根哈希（256位，32字节）
     */
    @JsonSerialize(using = HexByteArraySerializer.class)
    private byte[] merkleRoot;//3

    /**
     * Unix时间戳（秒级，4字节）
     * 可改为long但序列化时需截取低4字节 -> int
     */
    private long time;//4

    /**
     * 挖矿难度目标（压缩存储，4字节）
     * 当前区块难度目标的数值 无符号4字节
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
        this.previousHash = (previousHash != null && previousHash.length == 32) ? previousHash : new byte[32];
        // 校验并处理默克尔根哈希：确保是32字节（不符合则替换为32字节空数组）
        this.merkleRoot = (merkleRoot != null && merkleRoot.length == 32) ? merkleRoot : new byte[32];
        this.time = time;
        this.bits = bits;
        this.nonce = nonce;
    }


    /**
     * 从bits字段解析出目标难度值
     * bits格式：前1字节为指数(exponent)，后3字节为系数(coefficient)
     * 目标值 = 系数 * 256^(指数-3)
     * @return 目标难度值对应的BigInteger
     */
    @JsonIgnore
    public BigInteger getTargetFromBits() {
        // bits是4字节，以小端序存储
        // 在Java中，bits已经是int类型，我们需要从中提取指数和系数
        // 提取指数（最高位字节）
        int exponent = (this.bits >>> 24) & 0xFF;
        // 提取系数（低3个字节）
        int coefficient = this.bits & 0x00FFFFFF;
        // 计算目标值 = 系数 * 256^(指数-3)
        BigInteger target = BigInteger.valueOf(coefficient);
        // 计算指数偏移
        int shift = 8 * (exponent - 3);
        if (shift > 0) {
            // 左移，表示乘以256^(指数-3)
            target = target.shiftLeft(shift);
        } else if (shift < 0) {
            // 右移，表示除以256^(3-指数)
            // 在比特币中，指数小于3的情况很少，但理论上存在
            target = target.shiftRight(-shift);
        }
        // 如果shift=0，target保持不变
        return target;
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

    /**
     * 计算区块哈希（SHA256(SHA256(header))）
     */
    public byte[] calculateHash() {
        byte[] serialized = this.serialize();
        return reverseBytes(Sha.applySHA256(Sha.applySHA256(serialized)));
    }

    /**
     * 反转字节数组的顺序（如[1,2,3]转为[3,2,1]）
     *
     * @param bytes 源字节数组
     * @return 反转后的新字节数组
     */
    public static byte[] reverseBytes(byte[] bytes) {
        byte[] buf = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++)
            buf[i] = bytes[bytes.length - 1 - i];
        return buf;
    }

    public static BigInteger LARGEST_HASH = BigInteger.ONE.shiftLeft(256);
    //难度目标 0000ffffffff0000000000000000000000000000000000000000000000000000
    public static final String INIT_DIFFICULTY_TARGET_HEX = "0000ffffffff0000000000000000000000000000000000000000000000000000";
    private static final BigInteger DIFFICULTY_1_TARGET = new BigInteger(INIT_DIFFICULTY_TARGET_HEX, 16);



    /**
     * work = 2^256 / (target + 1)
     */

    public BigInteger getWork() {
        BigInteger currentTarget = getTargetFromBits();

        // 防御性判断：target不能为0（理论上不可能）
        if (currentTarget.signum() == 0) {
            return BigInteger.ZERO;
        }

        // 核心公式：work = 2^256 / (target + 1)
        BigInteger denominator = currentTarget.add(BigInteger.ONE);
        // 使用BigInteger的除法（向零取整，符合比特币核心实现）
        return LARGEST_HASH.divide(denominator);
    }


    /**
     * 难度倍数
     * @return
     */
    @JsonIgnore
    public BigInteger getDifficulty() {
        // 当前区块的目标难度
        BigInteger currentTarget = getTargetFromBits();
        if (currentTarget.signum() == 0) {
            // 目标难度为0的情况（理论上不可能，但做防御）
            // 返回一个极大值，表示工作量无穷大
            return BigInteger.valueOf(Long.MAX_VALUE);
        }
        // 工作量 = 初始难度目标 / 当前区块目标难度
        // 使用 BigInteger 的 divide 方法，它会执行整数除法（向零取整）
        return DIFFICULTY_1_TARGET.divide(currentTarget);
    }

    /**
     * 验证工作量证明
     * 验证区块哈希值是否小于等于目标难度值
     * 这是比特币PoW的核心验证逻辑
     *
     * @return 验证结果：true表示工作量证明有效，false表示无效
     */
    public boolean verifyProofOfWork() {
        // 计算区块哈希
        byte[] hash = this.calculateHash();
        // 比特币的哈希值在内存中是按照小端序存储的
        // 但在比较时需要转换为大整数，注意字节序
        // 我们将哈希字节数组转换为BigInteger（大端序）
        BigInteger hashValue = new BigInteger(1, hash);
        // 获取目标难度
        BigInteger target = getTargetFromBits();
        // 验证哈希值是否小于等于目标难度
        return hashValue.compareTo(target) <= 0;
    }

    /**
     * 验证工作量证明（增强版，包含更多错误检查和日志输出）
     *
     * @param verbose 是否输出详细验证信息
     * @return 验证结果
     */
    public boolean verifyProofOfWork(boolean verbose) {
        byte[] hash = this.calculateHash();
        BigInteger hashValue = new BigInteger(1, hash);
        BigInteger target = getTargetFromBits();
        boolean isValid = hashValue.compareTo(target) <= 0;
        if (verbose) {
            System.out.println("=== 工作量证明验证 ===");
            System.out.println("区块哈希: " + bytesToHex(hash));
            System.out.println("哈希值(大整数): " + hashValue.toString(16));
            System.out.println("目标难度(从bits解析): " + target.toString(16));
            System.out.println("目标bits: 0x" + Integer.toHexString(this.bits));
            System.out.println("哈希 <= 目标: " + isValid);

            if (!isValid) {
                System.out.println("❌ 验证失败: 区块哈希大于目标难度");
                System.out.println("哈希值: " + hashValue);
                System.out.println("目标值: " + target);
                System.out.println("差值: " + hashValue.subtract(target));
            } else {
                System.out.println("✅ 验证成功: 工作量证明有效");
            }
        }
        return isValid;
    }

    /**
     * 获取目标难度的十六进制字符串表示
     * 通常用于调试和显示
     *
     * @return 目标难度的十六进制字符串
     */
    @JsonIgnore
    public String getTargetString() {
        BigInteger target = getTargetFromBits();
        return target.toString(16);
    }

    /**
     * 将目标值转换为bits格式
     * bits格式: 前1字节为指数(exponent)，后3字节为系数(coefficient)
     * 目标值 = 系数 * 256^(指数-3)
     */
    public static int convertTargetToBits(BigInteger target) {
        // 特殊情况处理
        if (target.signum() == 0) {
            return 0;
        }

        // 将目标值转换为字节数组（大端序）
        byte[] targetBytes = target.toByteArray();

        // 去掉前导零
        int start = 0;
        while (start < targetBytes.length && targetBytes[start] == 0) {
            start++;
        }

        if (start == targetBytes.length) {
            return 0; // 目标值为0
        }

        int length = targetBytes.length - start;

        // 获取前三个有效字节（或更少）
        byte[] coefficientBytes = new byte[3];
        int exponent = length;

        if (length <= 3) {
            // 如果目标值长度小于等于3字节
            System.arraycopy(targetBytes, start, coefficientBytes, 3 - length, length);
            exponent = 3;
        } else {
            // 如果目标值长度大于3字节
            System.arraycopy(targetBytes, start, coefficientBytes, 0, 3);
        }

        // 将系数转换为整数
        int coefficient = ((coefficientBytes[0] & 0xFF) << 16) |
                ((coefficientBytes[1] & 0xFF) << 8) |
                (coefficientBytes[2] & 0xFF);

        // 计算bits
        int bits = (exponent << 24) | coefficient;

        return bits;
    }




    public static void main(String[] args) {
        System.out.println("=== 比特币区块挖矿测试 ===\n");

        // 1. 初始化创世区块参数
        int version = 1;

        // 前一个区块的哈希（创世区块前一个区块为空，用全0表示）
        byte[] previousHash = new byte[32];
        Arrays.fill(previousHash, (byte) 0);

        // 模拟的默克尔根（可以使用任意32字节数据，这里用全1模拟）
        byte[] merkleRoot = new byte[32];
        Arrays.fill(merkleRoot, (byte) 0xFF);

        // 当前时间戳
        long time = Instant.now().getEpochSecond();

        // 使用初始难度目标
        // 注意：这里需要将初始难度目标转换为bits格式
        //int bits = convertTargetToBits(new BigInteger(BlockHeader.INIT_DIFFICULTY_TARGET_HEX, 16));

        int bits = convertTargetToBits(new BigInteger(BlockHeader.INIT_DIFFICULTY_TARGET_HEX, 16).divide(BigInteger.valueOf(1)));


        System.out.println("初始难度目标: " + BlockHeader.INIT_DIFFICULTY_TARGET_HEX);
        System.out.println("对应的bits: 0x" + Integer.toHexString(bits));
        System.out.println("从bits解析回目标: 0x" + new BlockHeader(version, previousHash, merkleRoot, time, bits, 0).getTargetString());

        // 2. 创建区块头（初始nonce为0）
        BlockHeader blockHeader = new BlockHeader(version, previousHash, merkleRoot, time, bits, 0);

        // 3. 打印初始信息
        System.out.println("\n=== 开始挖矿 ===");
        System.out.println("区块版本: " + blockHeader.getVersion());
        System.out.println("前一区块哈希: " + bytesToHex(previousHash));
        System.out.println("默克尔根: " + bytesToHex(merkleRoot));
        System.out.println("时间戳: " + blockHeader.getTime() + " (" + Instant.ofEpochSecond(blockHeader.getTime()) + ")");
        System.out.println("目标bits: 0x" + Integer.toHexString(bits));
        System.out.println("初始nonce: 0x" + Integer.toHexString(blockHeader.getNonce()));

        // 4. 开始挖矿（寻找满足条件的nonce）
        boolean found = false;
        int maxNonce = 100_000_000; // 最大尝试次数
        long startTime = System.currentTimeMillis();

        System.out.println("\n开始搜索有效nonce...");

        for (int nonce = 0; nonce < maxNonce; nonce++) {
            blockHeader.setNonce(nonce);
            byte[] hash = blockHeader.calculateHash();
            BigInteger hashValue = new BigInteger(1, hash);
            BigInteger target = blockHeader.getTargetFromBits();

            // 检查哈希是否小于等于目标
            if (hashValue.compareTo(target) <= 0) {
                long endTime = System.currentTimeMillis();
                long elapsedTime = endTime - startTime;

                System.out.println("\n✅ 挖矿成功!");
                System.out.println("找到有效nonce: " + nonce + " (0x" + Integer.toHexString(nonce) + ")");
                System.out.println("尝试次数: " + (nonce + 1));
                System.out.println("耗时: " + elapsedTime + " 毫秒");
                System.out.println("哈希率: " + (nonce + 1) / (elapsedTime / 1000.0) + " 哈希/秒");
                System.out.println("区块哈希: " + bytesToHex(hash));
                System.out.println("哈希值(十进制): " + hashValue);
                System.out.println("目标值(十进制): " + target);
                System.out.println("工作量: " + blockHeader.getWork());

                found = true;
                break;
            }

            // 每隔100万次输出一次进度
            if (nonce > 0 && nonce % 1_000_000 == 0) {
                System.out.println("已尝试 " + (nonce / 1_000_000) + " 百万次, 当前nonce: " + nonce);
            }
        }

        if (!found) {
            System.out.println("\n❌ 在 " + maxNonce + " 次尝试内未找到有效nonce");
            // 即使没找到，也测试一下验证功能
            System.out.println("使用最后的nonce进行验证:");
        }

        // 5. 验证工作量证明
        System.out.println("\n=== 验证工作量证明 ===");

        boolean isValid = blockHeader.verifyProofOfWork(true);

        if (isValid) {
            System.out.println("\n✅ 验证结果: 工作量证明有效!");

            // 计算并显示实际难度
            BigInteger target = blockHeader.getTargetFromBits();
            System.out.println("区块哈希: " + bytesToHex(blockHeader.calculateHash()));
            System.out.println("目标值: 0x" + target.toString(16));
            System.out.println("难度目标: " + target);

            // 计算相对于初始难度的难度倍数
            BigInteger initialTarget = new BigInteger(BlockHeader.INIT_DIFFICULTY_TARGET_HEX, 16);
            BigInteger difficulty = initialTarget.divide(target);
            System.out.println("难度倍数: " + difficulty);

        } else {
            System.out.println("\n❌ 验证结果: 工作量证明无效!");

            // 输出详细信息帮助调试
            System.out.println("\n调试信息:");
            System.out.println("当前nonce: " + blockHeader.getNonce());
            System.out.println("区块哈希: " + bytesToHex(blockHeader.calculateHash()));
            BigInteger hashValue = new BigInteger(1, blockHeader.calculateHash());
            BigInteger target = blockHeader.getTargetFromBits();
            System.out.println("哈希值: " + hashValue);
            System.out.println("目标值: " + target);
            System.out.println("比较结果: " + hashValue.compareTo(target));

            // 计算哈希和目标值之间的差异
            if (hashValue.compareTo(target) > 0) {
                System.out.println("哈希值比目标值大: " + hashValue.subtract(target));
            }
        }

        // 6. 测试序列化和反序列化
        System.out.println("\n=== 测试序列化和反序列化 ===");
        byte[] serialized = blockHeader.serialize();
        System.out.println("序列化长度: " + serialized.length + " 字节 (应为80)");

        BlockHeader deserialized = BlockHeader.deserialize(serialized);
        System.out.println("版本号匹配: " + (deserialized.getVersion() == blockHeader.getVersion()));
        System.out.println("前一区块哈希匹配: " + Arrays.equals(deserialized.getPreviousHash(), blockHeader.getPreviousHash()));
        System.out.println("时间戳匹配: " + (deserialized.getTime() == blockHeader.getTime()));
        System.out.println("bits匹配: " + (deserialized.getBits() == blockHeader.getBits()));
        System.out.println("nonce匹配: " + (deserialized.getNonce() == blockHeader.getNonce()));

        // 验证反序列化后的区块
        boolean deserializedValid = deserialized.verifyProofOfWork(false);
        System.out.println("反序列化区块验证结果: " + (deserializedValid ? "✅ 有效" : "❌ 无效"));
    }

    public BlockHeader clone() {
        BlockHeader blockHeaderCopy = new BlockHeader();
        blockHeaderCopy.setVersion(this.getVersion());
        blockHeaderCopy.setPreviousHash(this.getPreviousHash());
        blockHeaderCopy.setMerkleRoot(this.getMerkleRoot());
        blockHeaderCopy.setTime(this.getTime() );
        blockHeaderCopy.setBits(this.getBits());
        blockHeaderCopy.setNonce(this.getNonce());
        return blockHeaderCopy;
    }


    @JsonIgnore
    public byte[] getHash() {
        return calculateHash();
    }
}
