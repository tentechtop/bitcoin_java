package com.bit.coin.utils;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;


public class TimeGenerator {
    // 安全随机数生成器，用于极端情况补充唯一性
    private static final SecureRandom RANDOM = new SecureRandom();
    // 时间戳与随机数的分隔符（避免数值碰撞）
    private static final long RANDOM_MASK = 0xFFFFL; // 16位随机数掩码
    private static final AtomicLong atomicLong = new AtomicLong(0);

    /**
     * 生成唯一的交易时间标识（长整型）
     * 格式：高48位为单调递增的纳秒时间 + 低16位为随机数
     * @return 唯一交易时间标识
     */
    public static long generateUniqueTransactionTime() {
        // 1. 获取当前纳秒级时间（精度高于毫秒，降低重复概率）
        long currentNano = System.nanoTime();

        // 2. 确保时间戳单调递增（解决系统时间回调或并发重复问题）
        long last;
        long current;
        do {
            last = atomicLong.get();
            // 若当前时间 <= 上次记录，使用上次+1（保证严格递增）
            current = currentNano > last ? currentNano : last + 1;
        } while (!atomicLong.compareAndSet(last, current));

        // 3. 生成16位随机数（65536种可能），进一步避免极端冲突
        long random = RANDOM.nextInt(65536) & RANDOM_MASK;

        // 4. 组合：高48位时间戳 + 低16位随机数（共64位长整型）
        return (current << 16) | random;
    }

    /**
     * 解析交易时间标识，提取原始时间戳（用于日志或排序）
     * @param uniqueTime 生成的唯一交易时间
     * @return 原始纳秒级时间戳
     */
    public static long parseTimestamp(long uniqueTime) {
        return uniqueTime >>> 16; // 右移16位，去除随机数部分
    }


}
