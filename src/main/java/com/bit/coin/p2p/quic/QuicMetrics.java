package com.bit.coin.p2p.quic;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * QUIC网络框架监控指标类
 * 提供性能统计和可观测性支持
 */
@Slf4j
public class QuicMetrics {

    // ==================== 帧相关指标 ====================
    /** 发送的总帧数 */
    private static final AtomicLong totalFramesSent = new AtomicLong(0);
    /** 接收的总帧数 */
    private static final AtomicLong totalFramesReceived = new AtomicLong(0);
    /** 重传的总帧数 */
    private static final AtomicLong totalFramesRetransmitted = new AtomicLong(0);
    /** 发送失败的总帧数 */
    private static final AtomicLong totalFramesSendFailed = new AtomicLong(0);

    // ==================== 数据包相关指标 ====================
    /** 发送的总数据包数 */
    private static final AtomicLong totalDataPacketsSent = new AtomicLong(0);
    /** 接收的总数据包数 */
    private static final AtomicLong totalDataPacketsReceived = new AtomicLong(0);
    /** 发送失败的数据包数 */
    private static final AtomicLong totalDataPacketsFailed = new AtomicLong(0);

    // ==================== ACK相关指标 ====================
    /** 发送的单ACK帧数 */
    private static final AtomicLong totalSingleAckSent = new AtomicLong(0);
    /** 发送的批量ACK帧数 */
    private static final AtomicLong totalBatchAckSent = new AtomicLong(0);
    /** 接收的ACK帧数 */
    private static final AtomicLong totalAckReceived = new AtomicLong(0);

    // ==================== 连接相关指标 ====================
    /** 当前活跃连接数 */
    private static final AtomicInteger activeConnections = new AtomicInteger(0);
    /** 总建立连接数（累计） */
    private static final AtomicLong totalConnectionsCreated = new AtomicLong(0);
    /** 总关闭连接数（累计） */
    private static final AtomicLong totalConnectionsClosed = new AtomicLong(0);
    /** 连接过期数 */
    private static final AtomicLong totalConnectionsExpired = new AtomicLong(0);

    // ==================== PING/PONG相关指标 ====================
    /** 发送的PING帧数 */
    private static final AtomicLong totalPingSent = new AtomicLong(0);
    /** 发送的PONG帧数 */
    private static final AtomicLong totalPongSent = new AtomicLong(0);
    /** 接收的PING帧数 */
    private static final AtomicLong totalPingReceived = new AtomicLong(0);
    /** 接收的PONG帧数 */
    private static final AtomicLong totalPongReceived = new AtomicLong(0);

    // ==================== 错误相关指标 ====================
    /** 解码错误数 */
    private static final AtomicLong totalDecodeErrors = new AtomicLong(0);
    /** 编码错误数 */
    private static final AtomicLong totalEncodeErrors = new AtomicLong(0);
    /** 超时错误数 */
    private static final AtomicLong totalTimeoutErrors = new AtomicLong(0);

    // ==================== 性能相关指标 ====================
    /** 平均往返时间（毫秒） */
    private static volatile long averageRtt = 0;
    /** 最后一次往返时间（毫秒） */
    private static volatile long lastRtt = 0;
    /** 总RTT样本数 */
    private static final AtomicLong rttSampleCount = new AtomicLong(0);

    // ==================== 缓存相关指标 ====================
    /** 当前缓存的消息队列大小 */
    private static final AtomicInteger messageQueueSize = new AtomicInteger(0);

    /** 当前活跃的发送数据数 */
    private static final AtomicInteger activeSendDataCount = new AtomicInteger(0);

    /** 当前活跃的接收数据数 */
    private static final AtomicInteger activeReceiveDataCount = new AtomicInteger(0);

    // ==================== 统计定时器 ====================
    private static volatile ScheduledExecutorService metricsScheduler;

    /**
     * 初始化监控指标（启动定时统计任务）
     */
    public static synchronized void init() {
        if (metricsScheduler == null) {
            metricsScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "quic-metrics-scheduler");
                thread.setDaemon(true);
                return thread;
            });

            // 每60秒输出一次统计信息
            metricsScheduler.scheduleAtFixedRate(
                QuicMetrics::logMetrics,
                60,
                60,
                TimeUnit.SECONDS
            );

            log.info("[QUIC监控] 指标统计已启动");
        }
    }

    /**
     * 停止监控指标
     */
    public static synchronized void shutdown() {
        if (metricsScheduler != null) {
            metricsScheduler.shutdown();
            try {
                if (!metricsScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    metricsScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                metricsScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            metricsScheduler = null;
            log.info("[QUIC监控] 指标统计已停止");
        }
    }

    // ==================== 记录方法 ====================

    public static void recordFrameSent() {
        totalFramesSent.incrementAndGet();
    }

    public static void recordFrameReceived() {
        totalFramesReceived.incrementAndGet();
    }

    public static void recordFrameRetransmitted() {
        totalFramesRetransmitted.incrementAndGet();
    }

    public static void recordFrameSendFailed() {
        totalFramesSendFailed.incrementAndGet();
    }

    public static void recordDataPacketSent() {
        totalDataPacketsSent.incrementAndGet();
    }

    public static void recordDataPacketReceived() {
        totalDataPacketsReceived.incrementAndGet();
    }

    public static void recordDataPacketFailed() {
        totalDataPacketsFailed.incrementAndGet();
    }

    public static void recordSingleAckSent() {
        totalSingleAckSent.incrementAndGet();
    }

    public static void recordBatchAckSent() {
        totalBatchAckSent.incrementAndGet();
    }

    public static void recordAckReceived() {
        totalAckReceived.incrementAndGet();
    }

    public static void recordConnectionCreated() {
        totalConnectionsCreated.incrementAndGet();
        activeConnections.incrementAndGet();
    }

    public static void recordConnectionClosed() {
        totalConnectionsClosed.incrementAndGet();
        activeConnections.decrementAndGet();
    }

    public static void recordConnectionExpired() {
        totalConnectionsExpired.incrementAndGet();
    }

    public static void recordPingSent() {
        totalPingSent.incrementAndGet();
    }

    public static void recordPongSent() {
        totalPongSent.incrementAndGet();
    }

    public static void recordPingReceived() {
        totalPingReceived.incrementAndGet();
    }

    public static void recordPongReceived() {
        totalPongReceived.incrementAndGet();
    }

    public static void recordDecodeError() {
        totalDecodeErrors.incrementAndGet();
    }

    public static void recordEncodeError() {
        totalEncodeErrors.incrementAndGet();
    }

    public static void recordTimeoutError() {
        totalTimeoutErrors.incrementAndGet();
    }

    public static void recordRtt(long rttMs) {
        lastRtt = rttMs;
        long count = rttSampleCount.incrementAndGet();
        if (count == 1) {
            averageRtt = rttMs;
        } else {
            // 计算移动平均
            averageRtt = (averageRtt * (count - 1) + rttMs) / count;
        }
    }

    public static void updateMessageQueueSize(int size) {
        messageQueueSize.set(size);
    }

    public static void incrementSendDataCount() {
        activeSendDataCount.incrementAndGet();
    }

    public static void decrementSendDataCount() {
        activeSendDataCount.decrementAndGet();
    }

    public static void incrementReceiveDataCount() {
        activeReceiveDataCount.incrementAndGet();
    }

    public static void decrementReceiveDataCount() {
        activeReceiveDataCount.decrementAndGet();
    }

    // ==================== 获取指标方法 ====================

    public static long getTotalFramesSent() {
        return totalFramesSent.get();
    }

    public static long getTotalFramesReceived() {
        return totalFramesReceived.get();
    }

    public static long getTotalFramesRetransmitted() {
        return totalFramesRetransmitted.get();
    }

    public static int getActiveConnections() {
        return activeConnections.get();
    }

    public static long getAverageRtt() {
        return averageRtt;
    }

    public static long getLastRtt() {
        return lastRtt;
    }

    public static double getRetransmissionRate() {
        long sent = totalFramesSent.get();
        long retransmitted = totalFramesRetransmitted.get();
        return sent > 0 ? (double) retransmitted / sent : 0;
    }

    public static double getPacketLossRate() {
        long sent = totalDataPacketsSent.get();
        long failed = totalDataPacketsFailed.get();
        return sent > 0 ? (double) failed / sent : 0;
    }

    // ==================== 日志输出 ====================

    /**
     * 输出完整的监控指标
     */
    public static void logMetrics() {
        log.info("========================================");
        log.info("[QUIC监控指标]");
        log.info("----------------------------------------");
        log.info("【帧统计】");
        log.info("  发送帧数: {}", totalFramesSent.get());
        log.info("  接收帧数: {}", totalFramesReceived.get());
        log.info("  重传帧数: {}", totalFramesRetransmitted.get());
        log.info("  失败帧数: {}", totalFramesSendFailed.get());
        log.info("  重传率: {:.2%}", getRetransmissionRate());

        log.info("----------------------------------------");
        log.info("【数据包统计】");
        log.info("  发送数据包: {}", totalDataPacketsSent.get());
        log.info("  接收数据包: {}", totalDataPacketsReceived.get());
        log.info("  失败数据包: {}", totalDataPacketsFailed.get());
        log.info("  丢包率: {:.2%}", getPacketLossRate());

        log.info("----------------------------------------");
        log.info("【ACK统计】");
        log.info("  发送单ACK: {}", totalSingleAckSent.get());
        log.info("  发送批量ACK: {}", totalBatchAckSent.get());
        log.info("  接收ACK: {}", totalAckReceived.get());

        log.info("----------------------------------------");
        log.info("【连接统计】");
        log.info("  活跃连接: {}", activeConnections.get());
        log.info("  总建立连接: {}", totalConnectionsCreated.get());
        log.info("  总关闭连接: {}", totalConnectionsClosed.get());
        log.info("  过期连接: {}", totalConnectionsExpired.get());

        log.info("----------------------------------------");
        log.info("【PING/PONG统计】");
        log.info("  发送PING: {}", totalPingSent.get());
        log.info("  发送PONG: {}", totalPongSent.get());
        log.info("  接收PING: {}", totalPingReceived.get());
        log.info("  接收PONG: {}", totalPongReceived.get());

        log.info("----------------------------------------");
        log.info("【错误统计】");
        log.info("  解码错误: {}", totalDecodeErrors.get());
        log.info("  编码错误: {}", totalEncodeErrors.get());
        log.info("  超时错误: {}", totalTimeoutErrors.get());

        log.info("----------------------------------------");
        log.info("【性能统计】");
        log.info("  平均RTT: {}ms", averageRtt);
        log.info("  最后RTT: {}ms", lastRtt);
        log.info("  RTT样本数: {}", rttSampleCount.get());

        log.info("----------------------------------------");
        log.info("【资源统计】");
        log.info("  消息队列大小: {}", messageQueueSize.get());
        log.info("  活跃发送数据: {}", activeSendDataCount.get());
        log.info("  活跃接收数据: {}", activeReceiveDataCount.get());
        log.info("========================================");
    }

    /**
     * 重置所有统计指标（慎用）
     */
    public static void reset() {
        totalFramesSent.set(0);
        totalFramesReceived.set(0);
        totalFramesRetransmitted.set(0);
        totalFramesSendFailed.set(0);
        totalDataPacketsSent.set(0);
        totalDataPacketsReceived.set(0);
        totalDataPacketsFailed.set(0);
        totalSingleAckSent.set(0);
        totalBatchAckSent.set(0);
        totalAckReceived.set(0);
        totalConnectionsCreated.set(0);
        totalConnectionsClosed.set(0);
        totalConnectionsExpired.set(0);
        totalPingSent.set(0);
        totalPongSent.set(0);
        totalPingReceived.set(0);
        totalPongReceived.set(0);
        totalDecodeErrors.set(0);
        totalEncodeErrors.set(0);
        totalTimeoutErrors.set(0);
        averageRtt = 0;
        lastRtt = 0;
        rttSampleCount.set(0);
        messageQueueSize.set(0);
        activeSendDataCount.set(0);
        activeReceiveDataCount.set(0);
        log.warn("[QUIC监控] 所有指标已重置");
    }
}
