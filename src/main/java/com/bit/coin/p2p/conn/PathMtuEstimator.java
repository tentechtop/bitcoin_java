package com.bit.coin.p2p.conn;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于ACK和超时反馈估算当前路径可承载的UDP负载大小。
 */
public class PathMtuEstimator {

    // 以下三项均为 UDP 负载字节数，包含帧头和会话封装。
    public static final int MIN_DATAGRAM_BYTES = 1200;
    public static final int DEFAULT_DATAGRAM_BYTES = 1232;
    public static final int MAX_DATAGRAM_BYTES = 1429;
    private static final int PROBE_STEP_BYTES = 32;
    private static final long ACK_BYTES_BEFORE_PROBE = 64L * 1024;  // 尝试增大分片前须累计确认的字节数
    private static final long PROBE_INTERVAL_MS = 1000;

    private final int minPayloadBytes;
    private final int maxPayloadBytes;
    private final AtomicInteger payloadBytes;
    private final AtomicLong ackedBytesSinceProbe = new AtomicLong();
    private final AtomicInteger lossStreak = new AtomicInteger();
    private volatile long lastProbeMillis;

    public PathMtuEstimator() {
        this(MIN_DATAGRAM_BYTES, DEFAULT_DATAGRAM_BYTES, MAX_DATAGRAM_BYTES);
    }

    public PathMtuEstimator(int minDatagramBytes, int initialDatagramBytes, int maxDatagramBytes) {
        int headerBytes = QuicFrame.FIXED_HEADER_LENGTH + SessionCipher.OVERHEAD_BYTES;
        if (minDatagramBytes <= headerBytes
                || initialDatagramBytes < minDatagramBytes
                || maxDatagramBytes < initialDatagramBytes) {
            throw new IllegalArgumentException("invalid mtu estimator configuration");
        }
        this.minPayloadBytes = minDatagramBytes - headerBytes;
        this.maxPayloadBytes = maxDatagramBytes - headerBytes;
        this.payloadBytes = new AtomicInteger(initialDatagramBytes - headerBytes);
        this.lastProbeMillis = System.currentTimeMillis();
    }

    public int currentPayloadBytes() {
        return payloadBytes.get();
    }

    public int currentDatagramBytes() {
        return currentPayloadBytes() + QuicFrame.FIXED_HEADER_LENGTH + SessionCipher.OVERHEAD_BYTES;
    }

    /** 确认字节数和探测间隔均达标后，将分片负载增大一步。 */
    public void onAckedBytes(int ackedBytes) {
        if (ackedBytes <= 0) {
            return;
        }
        lossStreak.set(0);
        if (payloadBytes.get() >= maxPayloadBytes) {
            return;
        }
        long acked = ackedBytesSinceProbe.addAndGet(ackedBytes);
        long now = System.currentTimeMillis();
        if (acked < ACK_BYTES_BEFORE_PROBE || now - lastProbeMillis < PROBE_INTERVAL_MS) {
            return;
        }
        growPayload();
        ackedBytesSinceProbe.set(0);
        lastProbeMillis = now;
    }

    /** 连续两次疑似丢包后将负载缩小两步，收到确认会清零连续计数。 */
    public void onPotentialBlackHole() {
        int streak = lossStreak.incrementAndGet();
        if (streak < 2) {
            return;
        }
        shrinkPayload();
        ackedBytesSinceProbe.set(0);
        lastProbeMillis = System.currentTimeMillis();
        lossStreak.set(0);
    }

    private void growPayload() {
        while (true) {
            int current = payloadBytes.get();
            int next = Math.min(maxPayloadBytes, current + PROBE_STEP_BYTES);
            if (next == current || payloadBytes.compareAndSet(current, next)) {
                return;
            }
        }
    }

    private void shrinkPayload() {
        while (true) {
            int current = payloadBytes.get();
            int next = Math.max(minPayloadBytes, current - PROBE_STEP_BYTES * 2);
            if (next == current || payloadBytes.compareAndSet(current, next)) {
                return;
            }
        }
    }
}
