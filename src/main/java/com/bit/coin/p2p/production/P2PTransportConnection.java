package com.bit.coin.p2p.production;

public record P2PTransportConnection(
        String peerId,
        long connectionId,
        String remoteAddress,
        boolean outbound
) {
}
