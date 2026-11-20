package com.bit.coin.estimate;

import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.structure.tx.TransactionValidator;
import com.bit.coin.txpool.TxPoolStatusSnapshot;
import com.bit.coin.txpool.TxPoolTransactionSnapshot;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 根据当前内存池积压和本节点近期确认样本估算手续费，不保证确认时间。
 * 使用本项目完整序列化字节（含 txId），与内存池中继和打包的计费口径一致。
 * 这是静态积压模型，不预测新交易流入，也不模拟祖先交易包的 CPFP 排序。
 */
public final class FeeEstimate {

    public static final long MIN_RELAY_FEE_RATE = 1L;  // 本地中继和估费使用的最低费率，单位为聪/字节
    public static final long BLOCK_CAPACITY_BYTES = 1024L * 1024L;  // 本地每块留给池内交易的打包预算，估费也使用此字节数
    public static final int DEFAULT_TARGET_BLOCKS = 3;  // 默认期望在多少个区块内确认
    public static final int MAX_TARGET_BLOCKS = 144;  // 确认目标的块数上限，也限定确认样本可回溯的高度差
    private static final int MAX_CONFIRMATION_SAMPLES = 10_000;  // 确认样本数超出上限时淘汰最旧记录
    private static final int MIN_CONFIRMATION_SAMPLES = 5;  // 目标窗口内样本不足此数时退回最低费率

    private static final Deque<ConfirmationSample> CONFIRMATION_SAMPLES = new ArrayDeque<>();

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
            long timestampMillis) {}

    private record ConfirmationSample(int delayBlocks, long feeRate, int blockHeight) {}

    /** 交易池中观察到的交易在主链上确认时，记录其确认样本。 */
    public static synchronized void recordConfirmed(int arrivalHeight, int confirmedHeight, long fee, long size) {
        if (arrivalHeight < 0 || confirmedHeight <= arrivalHeight || size <= 0
                || size > TransactionValidator.MAX_TRANSACTION_BYTES || !TransactionValidator.moneyRange(fee)) {
            return;
        }
        long feeRate = fee == 0 ? 0 : fee / size + (fee % size == 0 ? 0 : 1);
        feeRate = Math.max(MIN_RELAY_FEE_RATE, feeRate);
        CONFIRMATION_SAMPLES.addLast(new ConfirmationSample(confirmedHeight - arrivalHeight, feeRate, confirmedHeight));
        while (CONFIRMATION_SAMPLES.size() > MAX_CONFIRMATION_SAMPLES) {
            CONFIRMATION_SAMPLES.removeFirst();
        }
    }

    /** 移除已断开分支的观测样本，并清理过期的确认记录。 */
    public static synchronized void reorg(int forkHeight, int currentHeight) {
        CONFIRMATION_SAMPLES.removeIf(sample -> sample.blockHeight() > forkHeight);
        prune(currentHeight);
    }

    public static synchronized void advanceHeight(int currentHeight) {
        prune(currentHeight);
    }

    private static void prune(int currentHeight) {
        int oldestHeight = Math.max(0, currentHeight - MAX_TARGET_BLOCKS);
        CONFIRMATION_SAMPLES.removeIf(sample -> sample.blockHeight() < oldestHeight);
    }

    public static synchronized int confirmationSampleCount() {
        return CONFIRMATION_SAMPLES.size();
    }

    /** 取在目标块数内确认的样本费率中位数，单位为聪/字节。 */
    private static synchronized long historicalFeeRate(int targetBlocks) {
        List<Long> qualifiedRates = CONFIRMATION_SAMPLES.stream()
                .filter(sample -> sample.delayBlocks() <= targetBlocks)
                .map(ConfirmationSample::feeRate)
                .sorted()
                .toList();
        if (qualifiedRates.size() < MIN_CONFIRMATION_SAMPLES) {
            return MIN_RELAY_FEE_RATE;
        }
        int index = qualifiedRates.size() / 2;
        return Math.max(MIN_RELAY_FEE_RATE, qualifiedRates.get(index));
    }

    /** 结合池内积压和近期确认费率，估算指定字节数交易的总费用。 */
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
        long historicalFeeRate = historicalFeeRate(targetBlocks);
        long mempoolFeeRate = MIN_RELAY_FEE_RATE;
        boolean hasMempoolPressure = false;
        long remainingBytes = BLOCK_CAPACITY_BYTES * targetBlocks - txSize;
        for (TxPoolTransactionSnapshot sample : samples) {
            if (remainingBytes < sample.size()) {
                long baseRate = sample.fee() / sample.size();
                mempoolFeeRate = Math.max(MIN_RELAY_FEE_RATE,
                        baseRate + (sample.fee() % sample.size() == 0 ? 0 : 1));
                hasMempoolPressure = true;
                break;
            }
            remainingBytes -= sample.size();
        }
        long feeRate = Math.max(historicalFeeRate, mempoolFeeRate);
        String source = hasMempoolPressure && mempoolFeeRate > historicalFeeRate ? "mempool"
                : historicalFeeRate > MIN_RELAY_FEE_RATE ? "historical" : "minimum-relay";
        // 超过货币范围时明确拒绝，而不是截断、溢出或返回无法支付的费用。
        if (feeRate > TransactionValidator.MAX_MONEY / txSize) {
            throw new IllegalArgumentException("Estimated fee exceeds the money range");
        }
        return new Estimate(targetBlocks, txSize, feeRate, feeRate * txSize, "sat/byte", "sat",
                source, samples.size(), mempoolBytes, snapshot.timestampMillis());
    }
}
