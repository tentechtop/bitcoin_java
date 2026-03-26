package com.bit.coin.p2p.conn;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地拥塞窗口（cwnd）：自己控制的最大在途字节数
 * - 平滑调整，避免骤增骤减
 * - 有最小/最大值兜底
 * - 原子操作，并发安全
 */
@Slf4j
public class LocalCongestionWindow {
    // 最小拥塞窗口（2×MTU，MTU=1400B，适配QUIC帧大小）
    public static final int MIN_CWND = 2 * 1400;
    // 最大拥塞窗口（10MB，避免无限制增大导致网络拥塞）
    public static final int MAX_CWND = 10 * 1024 * 1024;
    // 平滑调整系数（每次调整不超过当前值的20%）
    private static final float ADJUST_FACTOR = 0.2f;

    // 底层原子存储，保证并发安全
    private final AtomicInteger cwnd = new AtomicInteger(MIN_CWND);

    // 核心方法1：获取当前拥塞窗口值（发送前调用）
    public int get() {
        return cwnd.get();
    }

    // 核心方法2：平滑增大（连续收到ACK，网络通畅时调用）
    public void increaseSmoothly() {
        int current, next;
        do {
            current = cwnd.get();
            // 平滑增大：当前值 + 当前值×20%
            next = current + (int) (current * ADJUST_FACTOR);
            // 兜底：不超过最大值
            next = Math.min(next, MAX_CWND);
            // 避免无意义的CAS（值没变化时退出）
            if (next == current) {
                break;
            }
        } while (!cwnd.compareAndSet(current, next));
        log.debug("[拥塞窗口增大] 新值={}B", cwnd.get());
    }

    // 核心方法3：平滑减小（丢包/超时，网络拥塞时调用）
    public void decreaseSmoothly() {
        int current, next;
        do {
            current = cwnd.get();
            // 平滑减小：当前值 - 当前值×20%
            next = current - (int) (current * ADJUST_FACTOR);
            // 兜底：不低于最小值
            next = Math.max(next, MIN_CWND);
            if (next == current) {
                break;
            }
        } while (!cwnd.compareAndSet(current, next));
        log.debug("[拥塞窗口减小] 新值={}B", cwnd.get());
    }

    // 核心方法4：强制重置（比如连接重建时）
    public void reset() {
        cwnd.set(MIN_CWND);
        log.debug("[拥塞窗口重置] 恢复最小值{}B", MIN_CWND);
    }
}
