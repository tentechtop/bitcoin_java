package com.bit.coin.p2p.production;

public record P2PDisconnectResult(
        boolean success,
        P2PNodeStatus status,
        String peerId,
        String message
) {
}
