package com.bit.coin.p2p.production;

public record FlowControlSnapshot(
        long localReceiveWindowBytes,
        long localMaxReceiveWindowBytes,
        long remoteReceiveWindowBytes,
        CongestionControlSnapshot congestion
) {
}
