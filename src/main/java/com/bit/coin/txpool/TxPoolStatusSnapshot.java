package com.bit.coin.txpool;

import java.util.List;

public record TxPoolStatusSnapshot(
        long timestampMillis,
        int capacity,
        int totalCount,
        int availableCount,
        int packingCount,
        int lockedCount,
        int highPriorityCount,
        int lowPriorityCount,
        int rbfCount,
        int doubleSpendCount,
        long totalFee,
        long totalSize,
        double averageFeeRate,
        int spentOutpointCount,
        int createdOutpointCount,
        int dependencyCount,
        int spendingUtxoCount,
        int processingUtxoCount,
        List<TxPoolTransactionSnapshot> transactions
) {
}
