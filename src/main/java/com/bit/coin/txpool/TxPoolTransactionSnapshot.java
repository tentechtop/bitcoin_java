package com.bit.coin.txpool;

import java.util.List;

public record TxPoolTransactionSnapshot(
        String txId,
        long status,
        String lifecycle,
        List<String> flags,
        boolean available,
        boolean packing,
        boolean locked,
        boolean highPriority,
        boolean lowPriority,
        boolean rbfEnabled,
        boolean doubleSpend,
        long fee,
        long size,
        double feeRate,
        int inputCount,
        int outputCount,
        Long joinTime,
        Long broadcastTime,
        long ageSeconds,
        List<String> inputOutpoints,
        List<String> outputOutpoints,
        List<String> parents,
        List<String> children
) {
}
