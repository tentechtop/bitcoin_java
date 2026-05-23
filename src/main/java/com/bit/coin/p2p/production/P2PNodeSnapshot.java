package com.bit.coin.p2p.production;

public record P2PNodeSnapshot(
        String peerId,
        String multiAddress,
        String remoteAddress,
        String ipAddress,
        int port,
        long connectionId,
        boolean outbound,
        P2PNodeStatus status,
        boolean blacklisted,
        String blacklistReason,
        long lastConnectedAtMillis,
        long lastDisconnectedAtMillis,
        long lastErrorAtMillis,
        String lastErrorMessage,
        long sentBytes,
        long receivedBytes,
        double sendBytesPerSecond,
        double receiveBytesPerSecond,
        long lastRttMillis,
        FlowControlSnapshot flowControl,
        long transferErrorCount
) {
}
