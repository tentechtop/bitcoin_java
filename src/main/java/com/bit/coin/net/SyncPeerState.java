package com.bit.coin.net;

public record SyncPeerState(
        String peerId,
        String address,
        int port,
        boolean online,
        int height,
        String hashHex,
        String chainWorkDecimal,
        String status,
        Integer assignedStartHeight,
        Integer assignedEndHeight,
        Integer lastSyncedHeight,
        int downloadedBlocks,
        int verifiedBlocks,
        int failedRequests,
        String lastError,
        long lastUpdatedAtMillis
) {
}
