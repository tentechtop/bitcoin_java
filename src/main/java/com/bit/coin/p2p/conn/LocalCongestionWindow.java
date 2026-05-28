package com.bit.coin.p2p.conn;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单连接AIMD拥塞窗口。
 */
@Slf4j
public class LocalCongestionWindow {
    public static final int MSS = 1400;
    public static final int MIN_CWND = 2 * MSS;
    public static final int INITIAL_CWND = 10 * MSS;
    public static final int MAX_CWND = 10 * 1024 * 1024;

    private final AtomicInteger cwnd = new AtomicInteger(INITIAL_CWND);
    private final AtomicInteger slowStartThreshold = new AtomicInteger(1024 * 1024);

    public int get() {
        return cwnd.get();
    }

    public int getSlowStartThreshold() {
        return slowStartThreshold.get();
    }

    public void onAckedBytes(int ackedBytes) {
        onAckedBytes(ackedBytes, MSS);
    }

    public void onAckedBytes(int ackedBytes, int currentMss) {
        int mss = Math.max(1, currentMss);
        int safeAckedBytes = Math.max(ackedBytes, mss);
        int current;
        int next;
        do {
            current = cwnd.get();
            if (current < slowStartThreshold.get()) {
                next = current + safeAckedBytes;
            } else {
                long additiveIncrease = Math.max(1L, (long) safeAckedBytes * mss / Math.max(current, mss));
                next = current + (int) Math.min(additiveIncrease, Integer.MAX_VALUE - current);
            }
            next = Math.min(next, MAX_CWND);
            if (next == current) {
                break;
            }
        } while (!cwnd.compareAndSet(current, next));
        log.debug("[拥塞窗口ACK增长] ackedBytes={} mss={} cwnd={} ssthresh={}",
                safeAckedBytes, mss, cwnd.get(), slowStartThreshold.get());
    }

    public void onCongestionEvent() {
        reduceWindow(false);
    }

    public void onTimeout() {
        reduceWindow(true);
    }

    public void increaseSmoothly() {
        onAckedBytes(MSS);
    }

    public void decreaseSmoothly() {
        onCongestionEvent();
    }

    public void reset() {
        cwnd.set(INITIAL_CWND);
        slowStartThreshold.set(1024 * 1024);
        log.debug("[拥塞窗口重置] cwnd={} ssthresh={}", INITIAL_CWND, slowStartThreshold.get());
    }

    private void reduceWindow(boolean timeout) {
        int current;
        int next;
        do {
            current = cwnd.get();
            next = Math.max(current / 2, MIN_CWND);
            slowStartThreshold.set(next);
            if (timeout) {
                next = MIN_CWND;
            }
            if (next == current) {
                break;
            }
        } while (!cwnd.compareAndSet(current, next));
        log.debug("[拥塞窗口收缩] timeout={} cwnd={} ssthresh={}", timeout, cwnd.get(), slowStartThreshold.get());
    }
}
