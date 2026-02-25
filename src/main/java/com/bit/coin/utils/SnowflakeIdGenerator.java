package com.bit.coin.utils;

import lombok.extern.slf4j.Slf4j;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class SnowflakeIdGenerator {
    // ====================== 雪花算法核心常量 ======================
    private static final int SIGN_BIT = 1;
    private static final int TIMESTAMP_BIT = 41;
    private static final int NODE_ID_BIT = 2;
    private static final int SEQUENCE_BIT = 22 - NODE_ID_BIT;

    private static final long MAX_NODE_ID = (1L << NODE_ID_BIT) - 1;    // 511
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BIT) - 1;  // 8191

    private static final int NODE_ID_SHIFT = SEQUENCE_BIT;
    private static final int TIMESTAMP_SHIFT = NODE_ID_BIT + SEQUENCE_BIT;

    private static final long START_TIMESTAMP = 1735689600000L;
    private static final long TIME_BACKWARD_THRESHOLD = 5L;

    // ====================== 静态缓存节点ID（核心修改） ======================
    // 缓存的节点ID，类加载时预热生成，所有实例复用
    private static volatile Long CACHED_NODE_ID = null;
    // 安全随机单例（避免重复创建耗时）
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    // 静态锁对象（保证节点ID初始化线程安全）
    private static final Object NODE_ID_LOCK = new Object();

    // ====================== 实例变量 ======================
    private final long nodeId;
    private volatile long lastTimestamp = -1L;
    private long sequence = 0L; // 同步块内操作，无需原子类

    // ====================== 静态预热（程序启动时执行） ======================
    static {
        // 类加载时主动预热生成节点ID
        generateAndCacheNodeId();
    }

    // ====================== 构造方法 ======================
    public SnowflakeIdGenerator(long nodeId) {
        if (nodeId < 0 || nodeId > MAX_NODE_ID) {
            throw new IllegalArgumentException(
                    String.format("Node ID must be between 0 and %d (current: %d)", MAX_NODE_ID, nodeId)
            );
        }
        this.nodeId = nodeId;
    }

    public SnowflakeIdGenerator() {
        // 复用静态缓存的节点ID
        this.nodeId = CACHED_NODE_ID;
    }

    // ====================== 核心方法 ======================
    public synchronized long nextId() {
        long currentTimestamp = System.currentTimeMillis();

        // 1. 处理时钟回拨
        if (currentTimestamp < lastTimestamp) {
            long timeDiff = lastTimestamp - currentTimestamp;
            if (timeDiff > TIME_BACKWARD_THRESHOLD) {
                throw new RuntimeException(
                        String.format("Clock moved backwards! Refuse generate ID for %dms", timeDiff)
                );
            }
            // 轻微回拨，等待时钟追上
            while (currentTimestamp < lastTimestamp) {
                currentTimestamp = System.currentTimeMillis();
            }
        }

        // 2. 处理同一毫秒内的序列号
        if (currentTimestamp == lastTimestamp) {
            sequence++;
            // 序列号溢出，等待下一毫秒
            if (sequence > MAX_SEQUENCE) {
                while (currentTimestamp <= lastTimestamp) {
                    currentTimestamp = System.currentTimeMillis();
                }
                sequence = 0L; // 重置序列号
            }
        } else {
            // 新的毫秒，重置序列号
            sequence = 0L;
        }

        // 3. 更新上次时间戳
        lastTimestamp = currentTimestamp;

        // 4. 拼接64位ID
        return ((currentTimestamp - START_TIMESTAMP) << TIMESTAMP_SHIFT)
                | (nodeId << NODE_ID_SHIFT)
                | sequence;
    }

    // ====================== 静态方法：生成并缓存节点ID（仅执行一次） ======================
    private static void generateAndCacheNodeId() {
        // 双重检查锁，避免多线程重复初始化
        if (CACHED_NODE_ID != null) {
            return;
        }

        synchronized (NODE_ID_LOCK) {
            if (CACHED_NODE_ID != null) {
                return;
            }

            long nodeId = 0L;
            boolean macSuccess = false;

            // 第一步：尝试从MAC地址生成节点ID
            try {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                while (interfaces.hasMoreElements()) {
                    NetworkInterface ni = interfaces.nextElement();
                    // 跳过回环/虚拟/未启用的网卡
                    if (ni.isLoopback() || ni.isVirtual() || !ni.isUp()) {
                        continue;
                    }
                    byte[] mac = ni.getHardwareAddress();
                    if (mac != null && mac.length > 0) {
                        // MAC地址转长整型（避免溢出）
                        for (int i = 0; i < Math.min(mac.length, 8); i++) {
                            nodeId |= ((long) (mac[i] & 0xFF)) << (8 * i);
                        }
                        nodeId = Math.abs(nodeId % (MAX_NODE_ID + 1));
                        macSuccess = true;
                        break;
                    }
                }
            } catch (SocketException e) {
                log.error("获取MAC地址失败（Socket异常），将使用安全随机生成节点ID：{}", e.getMessage());
            }

            // 第二步：MAC获取失败，用安全随机生成
            if (!macSuccess) {
                // SecureRandom生成0-511的非负整数
                nodeId = Math.abs(SECURE_RANDOM.nextLong()) % (MAX_NODE_ID + 1);
                log.info("ℹ使用安全随机生成节点ID：{}", nodeId);
            } else {
                log.info("ℹ从MAC地址生成节点ID：{}", nodeId);
            }

            // 缓存节点ID（所有实例复用）
            CACHED_NODE_ID = nodeId;
        }
    }

    // ====================== 测试方法 ======================
    public static void main(String[] args) throws InterruptedException {
        // 验证节点ID复用：创建多个实例，节点ID一致
        SnowflakeIdGenerator generator1 = new SnowflakeIdGenerator();
        SnowflakeIdGenerator generator2 = new SnowflakeIdGenerator();
        System.out.println("✅ 实例1节点ID：" + generator1.nodeId);
        System.out.println("✅ 实例2节点ID：" + generator2.nodeId);
        System.out.println("✅ 两个实例节点ID是否一致：" + (generator1.nodeId == generator2.nodeId));

        // 1. 测试1毫秒内生成能力
        long startNs = System.nanoTime();
        long endNs = startNs + 1_000_000; // 严格1毫秒窗口
        int count = 0;
        while (System.nanoTime() < endNs) {
            generator1.nextId();
            count++;
        }
        System.out.println("✅ 每毫秒生成ID数量：" + count + "（要求≥4960）");

        // 2. 测试并发生成（10线程，各生成1万ID，严格检测重复）
        int threadCount = 100;
        int perThreadCount = 10000;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicLong duplicateCount = new AtomicLong(0);
        java.util.concurrent.ConcurrentHashMap<Long, Boolean> idMap =
                new java.util.concurrent.ConcurrentHashMap<>(threadCount * perThreadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                for (int j = 0; j < perThreadCount; j++) {
                    long id = generator1.nextId();
                    if (idMap.putIfAbsent(id, Boolean.TRUE) != null) {
                        duplicateCount.incrementAndGet();
                        System.err.println("❌ 重复ID：" + id);
                    }
                }
                latch.countDown();
            }).start();
        }
        latch.await();
        System.out.println("✅ 并发生成" + (threadCount * perThreadCount) + "个ID完成，重复ID数量：" + duplicateCount.get());


        long l = generator1.nextId();
        System.out.println("✅ 生成的ID：" + l);

        // 3. 测试时间回拨处理
        try {
            java.lang.reflect.Field field = SnowflakeIdGenerator.class.getDeclaredField("lastTimestamp");
            field.setAccessible(true);
            field.set(generator1, System.currentTimeMillis() + 10);
            generator1.nextId();
        } catch (Exception e) {
            System.out.println("✅ 捕获时间回拨异常：" + e.getMessage());
        }
    }
}