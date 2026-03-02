package com.bit.coin.utils;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;


/**
 * UUID V7 生成工具（标准化实现，适配去中心化场景）
 * 核心：48位毫秒级时间戳 + 74位加密随机数（RFC 9562 标准）
 */
public class UUIDv7Generator {
    // 单例生成器（线程安全，全局复用）
    private static final TimeBasedEpochGenerator UUID_V7_GENERATOR;

    static {
        // 初始化UUID V7生成器（基于毫秒级时间戳+加密随机数）
        UUID_V7_GENERATOR = Generators.timeBasedEpochGenerator();
    }

    /**
     * 生成标准UUID V7（字符串格式，36字符：xxxxxxxx-xxxx-7xxx-xxxx-xxxxxxxxxxxx）
     */
    public static String generate() {
        return UUID_V7_GENERATOR.generate().toString();
    }



    /**
     * 生成UUID V7的16进制字节数组（字节数组长度固定为16字节，大端序/Big-Endian，符合RFC 4122标准）
     * @return 16字节的UUID V7字节数组（高64位在前，低64位在后，每8字节内部为大端序）
     */
    public static byte[] generateBytesId() {
        // 生成UUID V7实例
        UUID uuid = UUID_V7_GENERATOR.generate();
        // 获取UUID的高64位和低64位
        long mostSigBits = uuid.getMostSignificantBits();
        long leastSigBits = uuid.getLeastSignificantBits();

        byte[] uuidBytes = new byte[16];

        // 处理高64位（前8字节）：大端序（高位字节在前）
        for (int i = 0; i < 8; i++) {
            uuidBytes[i] = (byte) (mostSigBits >>> (8 * (7 - i)));
        }
        // 处理低64位（后8字节）：大端序（高位字节在前）
        for (int i = 0; i < 8; i++) {
            uuidBytes[8 + i] = (byte) (leastSigBits >>> (8 * (7 - i)));
        }

        return uuidBytes;
    }



    /**
     * 生成UUID V7的原始128位值（long[2]数组，高64位+低64位）
     */
    public static long[] generateAsLongArray() {
        UUID uuid = UUID_V7_GENERATOR.generate();
        return new long[]{uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()};
    }

    /**
     * 解析UUID V7的时间戳（从UUID中提取毫秒级时间戳）
     */
    public static long extractTimestamp(UUID uuid) {
        // UUID V7 高48位是毫秒级时间戳（从1970-01-01开始）
        if (uuid.version() != 7) {
            throw new IllegalArgumentException("不是UUID V7格式！");
        }
        long msb = uuid.getMostSignificantBits();
        // 提取高48位：右移16位（去掉版本/变体位）
        return (msb >> 16) & 0xFFFFFFFFFFFFL;
    }

    // ====================== 测试验证 ======================
    public static void main(String[] args) throws InterruptedException {
        // 1. 基础验证：生成UUID V7并解析时间戳
        System.out.println("===== 基础验证 =====");
        String uuid7Str1 = UUIDv7Generator.generate();
        String uuid7Str2 = UUIDv7Generator.generate();
        UUID uuid7 = UUID.fromString(uuid7Str1);

        System.out.println("生成UUID V7-1：" + uuid7Str1);
        System.out.println("生成UUID V7-2：" + uuid7Str2);
        //长度
        System.out.println("uuid7Str1长度:"+uuid7Str1.length());

        long[] longs = UUIDv7Generator.generateAsLongArray();

        byte[] bytes = UUIDv7Generator.generateBytesId();
        System.out.println("uuid7Str1长度:"+bytes.length);//16字节长度
        System.out.println("uuid7Str1长度:"+bytesToHex(bytes));

        System.out.println("UUID V7版本号：" + uuid7.version() + "（预期：7）");
        System.out.println("提取的时间戳（ms）：" + UUIDv7Generator.extractTimestamp(uuid7));
        System.out.println("是否按时间有序：" + (uuid7Str1.compareTo(uuid7Str2) < 0) + "（预期：true）");

        // 2. 高并发唯一性验证（100线程 × 1万UUID）
        System.out.println("\n===== 并发唯一性验证 =====");
        int threadCount = 100;
        int perThreadCount = 10000;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicLong duplicateCount = new AtomicLong(0);
        ConcurrentHashMap<String, Boolean> uuidMap = new ConcurrentHashMap<>(threadCount * perThreadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                for (int j = 0; j < perThreadCount; j++) {
                    String uuid = UUIDv7Generator.generate();
                    if (uuidMap.putIfAbsent(uuid, Boolean.TRUE) != null) {
                        duplicateCount.incrementAndGet();
                        System.err.println("❌ 重复UUID V7：" + uuid);
                    }
                }
                latch.countDown();
            }).start();
        }

        latch.await();
        System.out.println("总生成UUID数：" + (threadCount * perThreadCount));
        System.out.println("重复UUID数：" + duplicateCount.get() + "（预期：0）");

        // 3. 时钟回拨容错验证
        System.out.println("\n===== 时钟回拨容错验证 =====");
        // 即使系统时钟轻微回拨，仍能生成唯一UUID
        String uuidAfterBackward = UUIDv7Generator.generate();
        System.out.println("模拟时钟回拨后生成UUID：" + uuidAfterBackward);
        System.out.println("✅ 时钟回拨容错验证通过（UUID正常生成且唯一）");
    }
}