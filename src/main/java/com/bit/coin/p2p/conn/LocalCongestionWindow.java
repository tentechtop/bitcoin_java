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
    public static final int MSS = 1400;
    // 最小拥塞窗口（2×MTU，MTU=1400B，适配QUIC帧大小）
    public static final int MIN_CWND = 2 * MSS;
    public static final int INITIAL_CWND = 10 * MSS;
    // 最大拥塞窗口（10MB，避免无限制增大导致网络拥塞）
    public static final int MAX_CWND = 10 * 1024 * 1024;

    // 底层原子存储，保证并发安全
    private final AtomicInteger cwnd = new AtomicInteger(INITIAL_CWND);
    private final AtomicInteger slowStartThreshold = new AtomicInteger(1024 * 1024);

    // 核心方法1：获取当前拥塞窗口值（发送前调用）
    public int get() {
        return cwnd.get();
    }

    public int getSlowStartThreshold() {
        return slowStartThreshold.get();
    }

    public void onAckedBytes(int ackedBytes) {
        int safeAckedBytes = Math.max(ackedBytes, MSS);
        int current;
        int next;
        do {
            current = cwnd.get();
            if (current < slowStartThreshold.get()) {
                next = current + safeAckedBytes;
            } else {
                long additiveIncrease = Math.max(1L, (long) safeAckedBytes * MSS / Math.max(current, MSS));
                next = current + (int) additiveIncrease;
            }
            next = Math.min(next, MAX_CWND);
            if (next == current) {
                break;
            }
        } while (!cwnd.compareAndSet(current, next));
        log.debug("[拥塞窗口ACK增大] ackedBytes={} cwnd={} ssthresh={}",
                safeAckedBytes, cwnd.get(), slowStartThreshold.get());
    }

    public void onCongestionEvent() {
        int current;
        int next;
        do {
            current = cwnd.get();
            next = Math.max(current / 2, MIN_CWND);
            slowStartThreshold.set(next);
            if (next == current) {
                break;
            }
        } while (!cwnd.compareAndSet(current, next));
        log.debug("[拥塞窗口拥塞事件] cwnd={} ssthresh={}", cwnd.get(), slowStartThreshold.get());
    }

    // 核心方法2：平滑增大（连续收到ACK，网络通畅时调用）
    public void increaseSmoothly() {
        onAckedBytes(MSS);
    }

    // 核心方法3：平滑减小（丢包/超时，网络拥塞时调用）
    public void decreaseSmoothly() {
        onCongestionEvent();
    }

    // 核心方法4：强制重置（比如连接重建时）
    public void reset() {
        cwnd.set(INITIAL_CWND);
        slowStartThreshold.set(1024 * 1024);
        log.debug("[拥塞窗口重置] cwnd={} ssthresh={}", INITIAL_CWND, slowStartThreshold.get());
    }
}
