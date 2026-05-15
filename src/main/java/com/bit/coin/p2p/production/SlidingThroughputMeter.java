package com.bit.coin.p2p.production;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

public class SlidingThroughputMeter {
    private final long windowMillis;
    private final LongSupplier nowMillis;
    private final Deque<Sample> samples = new ArrayDeque<>();
    private long bytesInWindow;

    public SlidingThroughputMeter(Duration window) {
        this(window, System::currentTimeMillis);
    }

    SlidingThroughputMeter(Duration window, LongSupplier nowMillis) {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
        this.windowMillis = window.toMillis();
        this.nowMillis = nowMillis;
    }

    public synchronized void recordBytes(long bytes) {
        if (bytes <= 0) {
            prune(nowMillis.getAsLong());
            return;
        }
        long now = nowMillis.getAsLong();
        samples.addLast(new Sample(now, bytes));
        bytesInWindow += bytes;
        prune(now);
    }

    public synchronized double bytesPerSecond() {
        long now = nowMillis.getAsLong();
        prune(now);
        if (samples.isEmpty()) {
            return 0;
        }
        long firstSampleAt = samples.peekFirst().atMillis();
        long measuredMillis = Math.max(1, Math.min(windowMillis, now - firstSampleAt));
        return bytesInWindow * 1000.0 / measuredMillis;
    }

    public synchronized long bytesInWindow() {
        prune(nowMillis.getAsLong());
        return bytesInWindow;
    }

    private void prune(long now) {
        long cutoff = now - windowMillis;
        while (!samples.isEmpty() && samples.peekFirst().atMillis() < cutoff) {
            bytesInWindow -= samples.removeFirst().bytes();
        }
        if (bytesInWindow < 0) {
            bytesInWindow = 0;
        }
    }

    private record Sample(long atMillis, long bytes) {
    }
}
