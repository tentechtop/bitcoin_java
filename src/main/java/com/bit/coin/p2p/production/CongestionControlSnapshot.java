package com.bit.coin.p2p.production;

public record CongestionControlSnapshot(
        long congestionWindowBytes,
        long slowStartThresholdBytes,
        long inflightBytes,
        long availableBytes,
        int mssBytes
) {
}
