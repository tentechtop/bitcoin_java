package com.bit.coin.net;

import java.util.List;

public record SyncProgress(
        boolean running,
        String status,
        String mode,
        String message,
        long startedAtMillis,
        long updatedAtMillis,
        long finishedAtMillis,
        int localHeightAtStart,
        int localHeight,
        int networkHeight,
        int commonAncestorHeight,
        int startHeight,
        int targetHeight,
        int totalBlocks,
        int totalHeaders,
        int downloadedBlocks,
        int downloadedHeaders,
        int verifiedBlocks,
        int verifiedHeaders,
        int failedBlocks,
        int inFlightBlocks,
        int inFlightHeaders,
        double percent,
        double blocksPerSecond,
        long estimatedSecondsRemaining,
        int connectedPeerCount,
        int syncPeerCount,
        String bestPeerId,
        int bestPeerHeight,
        List<String> recentErrors,
        List<SyncPeerState> peers
) {
}
