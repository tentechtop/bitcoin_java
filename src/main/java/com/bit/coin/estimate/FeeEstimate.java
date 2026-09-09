package com.bit.coin.estimate;

import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.structure.tx.TransactionValidator;
import com.bit.coin.txpool.TxPoolStatusSnapshot;
import com.bit.coin.txpool.TxPoolTransactionSnapshot;

import java.math.BigInteger;
import java.util.List;

/**
 * 根据当前内存池的费率和字节积压估算手续费，不保证确认时间。
 * 使用本项目完整序列化字节（含 txId），与内存池中继和打包的计费口径一致。
 * 这是静态积压模型，不预测新交易流入，也不模拟祖先交易包的 CPFP 排序。
 */
public final class FeeEstimate {
    public static final long MIN_RELAY_FEE_RATE = 1L;
    public static final long BLOCK_CAPACITY_BYTES = 1024L * 1024L;
    public static final int DEFAULT_TARGET_BLOCKS = 3;
    public static final int MAX_TARGET_BLOCKS = 144;

    private FeeEstimate() {}

    public record Estimate(
            int targetBlocks,
            long txSize,
            long feeRate,
            long fee,
            String feeRateUnit,
            String feeUnit,
            String source,
            int sampleCount,
            long mempoolBytes,
            long timestampMillis
    ) {}

    public static Estimate estimate(TxPoolStatusSnapshot snapshot, int targetBlocks, long txSize) {
        validateRequest(targetBlocks, txSize);
        return estimate(snapshot, samples(snapshot), targetBlocks, txSize);
    }

    /** 三档使用同一个快照，避免池状态变化导致档位之间出现倒挂。 */
    public static List<Estimate> recommended(TxPoolStatusSnapshot snapshot, long txSize) {
        validateRequest(DEFAULT_TARGET_BLOCKS, txSize);
        List<TxPoolTransactionSnapshot> samples = samples(snapshot);
        return List.of(estimate(snapshot, samples, 1, txSize),
                estimate(snapshot, samples, 3, txSize), estimate(snapshot, samples, 6, txSize));
    }

    public static void validateRequest(int targetBlocks, long txSize) {
        if (targetBlocks < 1 || targetBlocks > MAX_TARGET_BLOCKS) {
            throw new IllegalArgumentException("targetBlocks must be between 1 and " + MAX_TARGET_BLOCKS);
        }
        if (txSize < 1 || txSize > TransactionValidator.MAX_TRANSACTION_BYTES) {
            throw new IllegalArgumentException("txSize exceeds the transaction byte limits");
        }
    }

    /**
     * 输入应已包含最终解锁脚本（可用等长占位数据）。无需私钥或 UTXO，且不修改交易。
     * 未签名交易若尚未预留签名空间，应由调用方提供签名后的大小给 estimate。
     */
    public static long transactionSize(Transaction tx) {
        if (tx == null || tx.getInputs() == null || tx.getInputs().isEmpty()
                || tx.getOutputs() == null || tx.getOutputs().isEmpty() || tx.isCoinbase()) {
            throw new IllegalArgumentException("A non-coinbase transaction is required");
        }
        long size = Transaction.TX_ID_SIZE + (long) tx.serializeWithOutTxId().length;
        validateRequest(DEFAULT_TARGET_BLOCKS, size);
        return size;
    }

    private static List<TxPoolTransactionSnapshot> samples(TxPoolStatusSnapshot snapshot) {
        // 正在挖矿的交易尚未确认，仍应计入积压；其他被锁定或不可打包的交易不计入。
        return snapshot.transactions().stream()
                .filter(tx -> (tx.available() || tx.packing()) && !tx.doubleSpend())
                .filter(tx -> tx.size() > 0 && tx.size() <= TransactionValidator.MAX_TRANSACTION_BYTES
                        && TransactionValidator.moneyRange(tx.fee()))
                // 交叉相乘比较有理数费率，避免 long 溢出及浮点数误差。
                .sorted((a, b) -> BigInteger.valueOf(b.fee()).multiply(BigInteger.valueOf(a.size()))
                        .compareTo(BigInteger.valueOf(a.fee()).multiply(BigInteger.valueOf(b.size()))))
                .toList();
    }

    private static Estimate estimate(TxPoolStatusSnapshot snapshot, List<TxPoolTransactionSnapshot> samples,
                                     int targetBlocks, long txSize) {
        long mempoolBytes = samples.stream().mapToLong(TxPoolTransactionSnapshot::size).sum();
        // 给待提交交易留出空间，再找到目标窗口中第一个放不下的费率档位。
        long remainingBytes = BLOCK_CAPACITY_BYTES * targetBlocks - txSize;
        long feeRate = MIN_RELAY_FEE_RATE;
        String source = "minimum-relay";
        for (TxPoolTransactionSnapshot sample : samples) {
            remainingBytes -= sample.size();
            if (remainingBytes < 0) {
                // 严格超过截断档位，避免与该档交易同费率时因排序规则而继续排队。
                feeRate = Math.max(MIN_RELAY_FEE_RATE, sample.fee() / sample.size() + 1);
                source = "mempool";
                break;
            }
        }
        // 超过货币范围时明确拒绝，而不是截断、溢出或返回无法支付的费用。
        if (feeRate > TransactionValidator.MAX_MONEY / txSize) {
            throw new IllegalArgumentException("Estimated fee exceeds the money range");
        }
        return new Estimate(targetBlocks, txSize, feeRate, feeRate * txSize, "sat/byte", "sat",
                source, samples.size(), mempoolBytes, snapshot.timestampMillis());
    }
}
