package com.bit.coin.p2p.conn;

import java.util.concurrent.atomic.AtomicLong;

/** Shared across all peers, so per-peer bounds cannot be multiplied into unbounded process memory. */
public final class TransportMemoryBudget {
    private static final long LIMIT = 96L * 1024 * 1024;
    private static final AtomicLong RESERVED = new AtomicLong();
    private TransportMemoryBudget() {}
    public static boolean reserve(int bytes) {
        if (bytes <= 0) return false;
        for (;;) {
            long current = RESERVED.get();
            if (current + bytes > LIMIT) return false;
            if (RESERVED.compareAndSet(current, current + bytes)) return true;
        }
    }
    public static void release(int bytes) { if (bytes > 0) RESERVED.addAndGet(-bytes); }
    public static long reservedBytes() { return RESERVED.get(); }
}
