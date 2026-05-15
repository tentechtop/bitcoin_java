package com.bit.coin.p2p.kad;

public record RoutingTableHealthSnapshot(
        int totalPeers,
        int onlinePeers,
        int candidatePeers,
        int goodPeers,
        int questionablePeers,
        int badPeers,
        int bucketCount,
        int nonEmptyBuckets,
        int sparseBuckets,
        double averageBucketFill,
        long lookupSuccessCount,
        long lookupFailureCount,
        int adaptiveAlpha
) {
}
