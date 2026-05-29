package com.bit.coin.p2p.conn;

/**
 * 全局出站流量令牌桶，避免多个连接同时把UDP发送队列打满。
 */
public class P2PGlobalTrafficController {
    public static final long DEFAULT_BYTES_PER_SECOND = 16L * 1024 * 1024;
    public static final long DEFAULT_BURST_BYTES = 2L * 1024 * 1024;

    private final long bytesPerSecond;
    private final long burstBytes;
    private long availableBytes;
    private long lastRefillNanos;

    public P2PGlobalTrafficController(long bytesPerSecond, long burstBytes) {
        if (bytesPerSecond <= 0 || burstBytes <= 0) {
            throw new IllegalArgumentException("bytesPerSecond and burstBytes must be positive");
        }
        this.bytesPerSecond = bytesPerSecond;
        this.burstBytes = burstBytes;
        this.availableBytes = burstBytes;
        this.lastRefillNanos = System.nanoTime();
    }

    public static P2PGlobalTrafficController fromSystemProperties() {
        long bytesPerSecond = positiveLongProperty("bitcoin.p2p.global.sendBytesPerSecond", DEFAULT_BYTES_PER_SECOND);
        long burstBytes = positiveLongProperty("bitcoin.p2p.global.sendBurstBytes", DEFAULT_BURST_BYTES);
        return new P2PGlobalTrafficController(bytesPerSecond, burstBytes);
    }

    public synchronized int reserve(int requestedBytes) {
        if (requestedBytes <= 0) {
            return 0;
        }
        refill();
        int granted = (int) Math.min(requestedBytes, availableBytes);
        availableBytes -= granted;
        return granted;
    }

    public synchronized void refund(int bytes) {
        if (bytes <= 0) {
            return;
        }
        refill();
        availableBytes = Math.min(burstBytes, availableBytes + bytes);
    }

    public synchronized long availableBytes() {
        refill();
        return availableBytes;
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillNanos;
        if (elapsedNanos <= 0) {
            return;
        }
        long refillBytes = (long) Math.min(burstBytes, elapsedNanos * (bytesPerSecond / 1_000_000_000.0));
        if (refillBytes > 0) {
            availableBytes = Math.min(burstBytes, availableBytes + refillBytes);
            lastRefillNanos = now;
        }
    }

    private static long positiveLongProperty(String name, long defaultValue) {
        long value = Long.getLong(name, defaultValue);
        return value > 0 ? value : defaultValue;
    }
}
