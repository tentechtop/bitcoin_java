package com.bit.coin.p2p.conn;

import lombok.extern.slf4j.Slf4j;

/**
 * 单连接拥塞窗口：按真实 ACK 字节驱动 AIMD，避免小 ACK 被放大导致突发发送。
 */
@Slf4j
public class LocalCongestionWindow {
    public static final int MSS = 1400;
    public static final int MIN_CWND = 2 * MSS;
    public static final int INITIAL_CWND = 10 * MSS;
    public static final int MAX_CWND = 10 * 1024 * 1024;

    private static final int DEFAULT_SLOW_START_THRESHOLD = 1024 * 1024;

    private int congestionWindowBytes = INITIAL_CWND;
    private int slowStartThresholdBytes = DEFAULT_SLOW_START_THRESHOLD;
    private long additiveIncreaseRemainder;

    public synchronized int get() {
        return congestionWindowBytes;
    }

    public synchronized int getSlowStartThreshold() {
        return slowStartThresholdBytes;
    }

    public void onAckedBytes(int ackedBytes) {
        onAckedBytes(ackedBytes, MSS);
    }

    public synchronized void onAckedBytes(int ackedBytes, int currentMss) {
        if (ackedBytes <= 0) {
            return;
        }

        int normalizedMss = normalizeMss(currentMss);
        int previousWindow = congestionWindowBytes;
        if (previousWindow < slowStartThresholdBytes) {
            growInSlowStart(ackedBytes);
        } else {
            growInCongestionAvoidance(ackedBytes, normalizedMss);
        }

        log.debug("[拥塞窗口ACK增长] ackedBytes={} mss={} cwnd={} ssthresh={}",
                ackedBytes, normalizedMss, congestionWindowBytes, slowStartThresholdBytes);
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

    public synchronized void reset() {
        congestionWindowBytes = INITIAL_CWND;
        slowStartThresholdBytes = DEFAULT_SLOW_START_THRESHOLD;
        additiveIncreaseRemainder = 0;
        log.debug("[拥塞窗口重置] cwnd={} ssthresh={}",
                congestionWindowBytes, slowStartThresholdBytes);
    }

    private void growInSlowStart(int ackedBytes) {
        long next = (long) congestionWindowBytes + ackedBytes;
        congestionWindowBytes = clampWindow(next);
        additiveIncreaseRemainder = 0;
    }

    private void growInCongestionAvoidance(int ackedBytes, int currentMss) {
        long numerator = safeMultiply(ackedBytes, currentMss) + additiveIncreaseRemainder;
        long increase = numerator / congestionWindowBytes;
        additiveIncreaseRemainder = numerator % congestionWindowBytes;
        if (increase <= 0) {
            return;
        }
        congestionWindowBytes = clampWindow((long) congestionWindowBytes + increase);
    }

    private synchronized void reduceWindow(boolean timeout) {
        int nextSlowStartThreshold = Math.max(congestionWindowBytes / 2, MIN_CWND);
        slowStartThresholdBytes = nextSlowStartThreshold;
        congestionWindowBytes = timeout ? MIN_CWND : nextSlowStartThreshold;
        additiveIncreaseRemainder = 0;
        log.debug("[拥塞窗口收缩] timeout={} cwnd={} ssthresh={}",
                timeout, congestionWindowBytes, slowStartThresholdBytes);
    }

    private int normalizeMss(int currentMss) {
        if (currentMss <= 0) {
            return MSS;
        }
        return Math.min(currentMss, MAX_CWND);
    }

    private int clampWindow(long candidate) {
        long positiveCandidate = Math.max(candidate, MIN_CWND);
        return (int) Math.min(positiveCandidate, MAX_CWND);
    }

    private long safeMultiply(int ackedBytes, int currentMss) {
        if (ackedBytes > Long.MAX_VALUE / currentMss) {
            return Long.MAX_VALUE - additiveIncreaseRemainder;
        }
        return (long) ackedBytes * currentMss;
    }
}
