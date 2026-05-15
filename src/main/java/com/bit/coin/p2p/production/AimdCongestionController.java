package com.bit.coin.p2p.production;

import java.util.concurrent.atomic.AtomicLong;

public class AimdCongestionController {
    public static final int DEFAULT_MSS = 1400;
    public static final long DEFAULT_MIN_CWND = 2L * DEFAULT_MSS;
    public static final long DEFAULT_INITIAL_CWND = 10L * DEFAULT_MSS;
    public static final long DEFAULT_MAX_CWND = 16L * 1024 * 1024;
    public static final long DEFAULT_SSTHRESH = 1024L * 1024;

    private final int mss;
    private final long minCwnd;
    private final long maxCwnd;
    private final AtomicLong congestionWindow;
    private final AtomicLong slowStartThreshold;
    private final AtomicLong inflightBytes = new AtomicLong();

    public AimdCongestionController() {
        this(DEFAULT_MSS, DEFAULT_MIN_CWND, DEFAULT_INITIAL_CWND, DEFAULT_MAX_CWND, DEFAULT_SSTHRESH);
    }

    public AimdCongestionController(int mss, long minCwnd, long initialCwnd, long maxCwnd, long slowStartThreshold) {
        if (mss <= 0 || minCwnd <= 0 || initialCwnd < minCwnd || maxCwnd < initialCwnd) {
            throw new IllegalArgumentException("invalid congestion control window configuration");
        }
        this.mss = mss;
        this.minCwnd = minCwnd;
        this.maxCwnd = maxCwnd;
        this.congestionWindow = new AtomicLong(initialCwnd);
        this.slowStartThreshold = new AtomicLong(Math.max(minCwnd, slowStartThreshold));
    }

    public boolean tryReserve(long bytes, long remoteReceiveWindow) {
        if (bytes <= 0 || remoteReceiveWindow <= 0) {
            return false;
        }
        while (true) {
            long inflight = inflightBytes.get();
            long allowed = Math.min(congestionWindow.get(), remoteReceiveWindow);
            if (bytes > allowed - inflight) {
                return false;
            }
            if (inflightBytes.compareAndSet(inflight, inflight + bytes)) {
                return true;
            }
        }
    }

    public void onAck(long ackedBytes) {
        if (ackedBytes <= 0) {
            return;
        }
        subtractInflight(ackedBytes);
        while (true) {
            long current = congestionWindow.get();
            long next;
            if (current < slowStartThreshold.get()) {
                next = current + Math.max(ackedBytes, mss);
            } else {
                long scaledAck = ackedBytes > Long.MAX_VALUE / mss ? Long.MAX_VALUE : ackedBytes * mss;
                next = current + Math.max(1, scaledAck / Math.max(current, mss));
            }
            next = Math.min(next, maxCwnd);
            if (next == current || congestionWindow.compareAndSet(current, next)) {
                return;
            }
        }
    }

    public void onLoss(long lostBytes) {
        subtractInflight(Math.max(0, lostBytes));
        halveWindow();
    }

    public void onTimeout() {
        halveWindow();
        congestionWindow.set(minCwnd);
        inflightBytes.set(0);
    }

    public long availableWindow(long remoteReceiveWindow) {
        long allowed = Math.min(congestionWindow.get(), Math.max(0, remoteReceiveWindow));
        return Math.max(0, allowed - inflightBytes.get());
    }

    public CongestionControlSnapshot snapshot(long remoteReceiveWindow) {
        return new CongestionControlSnapshot(
                congestionWindow.get(),
                slowStartThreshold.get(),
                inflightBytes.get(),
                availableWindow(remoteReceiveWindow),
                mss
        );
    }

    private void halveWindow() {
        while (true) {
            long current = congestionWindow.get();
            long next = Math.max(current / 2, minCwnd);
            slowStartThreshold.set(next);
            if (congestionWindow.compareAndSet(current, next)) {
                return;
            }
        }
    }

    private void subtractInflight(long bytes) {
        if (bytes <= 0) {
            return;
        }
        while (true) {
            long current = inflightBytes.get();
            long next = Math.max(0, current - bytes);
            if (inflightBytes.compareAndSet(current, next)) {
                return;
            }
        }
    }
}
