package com.bit.coin.p2p.conn;

import java.util.concurrent.atomic.AtomicLong;

/** 所有对等节点共享内存配额，避免各节点的配额叠加后使进程内存用量失去上限。 */
public final class TransportMemoryBudget {

    private static final long LIMIT = 96L * 1024 * 1024;
    private static final AtomicLong RESERVED = new AtomicLong();

    private TransportMemoryBudget() {}

    /** 原子预留跨连接共享的字节额度；成功后由调用方负责归还。 */
    public static boolean reserve(int bytes) {
        if (bytes <= 0) {
            return false;
        }
        for (;;) {
            long current = RESERVED.get();
            if (current + bytes > LIMIT) {
                return false;
            }
            if (RESERVED.compareAndSet(current, current + bytes)) {
                return true;
            }
        }
    }

    public static void release(int bytes) {
        if (bytes > 0) {
            RESERVED.addAndGet(-bytes);
        }
    }

    public static long reservedBytes() {
        return RESERVED.get();
    }
}
