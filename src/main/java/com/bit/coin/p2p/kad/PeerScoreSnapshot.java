package com.bit.coin.p2p.kad;

public record PeerScoreSnapshot(
        String peerId,
        PeerQualityState state,
        double score,
        long successCount,
        long failureCount,
        long lookupHelpCount,
        int consecutiveFailures,
        long avgRttMillis,
        long lastSeenMillis,
        long lastSuccessMillis,
        long lastFailureMillis
) {
}
