package com.bit.coin.p2p.conn;

public record QuicConnectionStats(
        String peerId,
        long connectionId,
        String remoteAddress,
        boolean outbound,
        boolean expired,
        long lastSeen,
        long idleMillis,
        long sentBytes,
        long receivedBytes,
        double sendBytesPerSecond,
        double receiveBytesPerSecond,
        long lastRttMillis,
        int remoteReceiveWindow,
        int localReceiveWindow,
        int congestionWindow,
        int slowStartThreshold,
        double pendingQueueMB,
        double sentUnAckedMB,
        int sentUnAckedBytes
) {
}
