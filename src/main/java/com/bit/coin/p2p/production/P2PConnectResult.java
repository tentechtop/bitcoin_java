package com.bit.coin.p2p.production;

public record P2PConnectResult(
        boolean success,
        P2PNodeStatus status,
        String peerId,
        String message,
        long connectionId
) {
    public static P2PConnectResult success(String peerId, long connectionId) {
        return new P2PConnectResult(true, P2PNodeStatus.CONNECTED, peerId, "connected", connectionId);
    }

    public static P2PConnectResult failure(P2PNodeStatus status, String peerId, String message) {
        return new P2PConnectResult(false, status, peerId == null ? "" : peerId, message, 0);
    }
}
