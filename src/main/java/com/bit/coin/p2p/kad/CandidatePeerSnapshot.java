package com.bit.coin.p2p.kad;

public record CandidatePeerSnapshot(
        String peerId,
        String multiAddress,
        String sourcePeerId,
        long firstSeenMillis,
        long lastSeenMillis,
        int attempts,
        long nextAttemptAtMillis,
        String lastFailureReason
) {
}
