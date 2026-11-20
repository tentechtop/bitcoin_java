package com.bit.coin.estimate;

import com.bit.coin.structure.tx.TransactionValidator;
import com.bit.coin.txpool.TxPoolStatusSnapshot;
import com.bit.coin.txpool.TxPoolTransactionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FeeEstimateTest {
    @org.junit.jupiter.api.BeforeEach
    void clearConfirmationHistory() {
        FeeEstimate.reorg(-1, Integer.MAX_VALUE);
    }

    @Test
    void emptyAndSparsePoolsUseMinimumRelayFee() {
        for (var pool : List.of(snapshot(List.of()), snapshot(List.of(sample(100_000, 100))))) {
            var quote = FeeEstimate.estimate(pool, 1, 250);
            assertEquals(1, quote.feeRate());
            assertEquals(250, quote.fee());
            assertEquals("minimum-relay", quote.source());
            assertEquals("sat/byte", quote.feeRateUnit());
            assertEquals("sat", quote.feeUnit());
            assertEquals(1234, quote.timestampMillis());
        }
    }

    @Test
    void recommendationsReflectByteBacklogAndAreMonotone() {
        List<TxPoolTransactionSnapshot> entries = new ArrayList<>();
        for (int i = 0; i < 2; i++) entries.add(sample(524_288, 40));
        for (int i = 0; i < 4; i++) entries.add(sample(524_288, 10));
        for (int i = 0; i < 6; i++) entries.add(sample(524_288, 2));
        var pool = snapshot(entries);

        var quotes = FeeEstimate.recommended(pool, 250);
        assertEquals(List.of(1, 3, 6), quotes.stream().map(FeeEstimate.Estimate::targetBlocks).toList());
        assertEquals(List.of(40L, 10L, 2L), quotes.stream().map(FeeEstimate.Estimate::feeRate).toList());
        assertEquals(List.of(10_000L, 2_500L, 500L), quotes.stream().map(FeeEstimate.Estimate::fee).toList());
        assertTrue(quotes.stream().allMatch(q -> q.sampleCount() == 12 && q.source().equals("mempool")));

        long previous = Long.MAX_VALUE;
        for (int target = 1; target <= FeeEstimate.MAX_TARGET_BLOCKS; target++) {
            long rate = FeeEstimate.estimate(pool, target, 250).feeRate();
            assertTrue(rate <= previous);
            assertTrue(rate >= FeeEstimate.MIN_RELAY_FEE_RATE);
            previous = rate;
        }
    }

    @Test
    void manySmallExpensiveTransactionsDoNotDistortTheByteCutoff() {
        List<TxPoolTransactionSnapshot> entries = new ArrayList<>();
        entries.add(sample(950_000, 2));
        for (int i = 0; i < 200; i++) entries.add(sample(500, 100));
        var quote = FeeEstimate.estimate(snapshot(entries), 1, 250);
        assertEquals(2, quote.feeRate());
        assertEquals(1_050_000, quote.mempoolBytes());
    }

    @Test
    void reservesSpaceForTheRequestedTransactionIncludingExactFit() {
        var pool = snapshot(List.of(sample(500_000, 9),
                sample(FeeEstimate.BLOCK_CAPACITY_BYTES - 500_000 - 250, 9)));
        assertEquals(1, FeeEstimate.estimate(pool, 1, 250).feeRate());
        assertEquals(9, FeeEstimate.estimate(pool, 1, 251).feeRate());
    }

    @Test
    void fractionalFeeRatesRoundUpUsingIntegerArithmetic() {
        var pool = snapshot(List.of(entry(600_000, 5_700_001, true, false, false),
                entry(600_000, 5_700_001, true, false, false)));
        assertEquals(10, FeeEstimate.estimate(pool, 1, 250).feeRate());
    }

    @Test
    void packingStillCountsWhileUnavailableAndInvalidEntriesDoNot() {
        var pool = snapshot(List.of(sample(600_000, 5),
                entry(600_000, 3_000_000, false, true, false),
                entry(600_000, 60_000_000, false, false, false),
                entry(600_000, 60_000_000, true, false, true),
                entry(0, 100, true, false, false),
                entry(500, -100, true, false, false),
                entry(500, TransactionValidator.MAX_MONEY + 1, true, false, false),
                sample(TransactionValidator.MAX_TRANSACTION_BYTES + 1L, 10)));
        var quote = FeeEstimate.estimate(pool, 1, 250);
        assertEquals(5, quote.feeRate());
        assertEquals(2, quote.sampleCount());
        assertEquals(1_200_000, quote.mempoolBytes());
    }

    @Test
    void rejectsInvalidRequestsAndUnpayableQuotes() {
        var empty = snapshot(List.of());
        for (int target : new int[]{Integer.MIN_VALUE, 0, 145, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> FeeEstimate.estimate(empty, target, 250));
        }
        for (long size : new long[]{Long.MIN_VALUE, 0, 1_000_001, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> FeeEstimate.estimate(empty, 1, size));
            assertThrows(IllegalArgumentException.class, () -> FeeEstimate.recommended(empty, size));
        }
        assertEquals(1_000_000, FeeEstimate.estimate(empty, 144, 1_000_000).fee());
        var expensive = snapshot(List.of(entry(600_000, TransactionValidator.MAX_MONEY, true, false, false)));
        assertThrows(IllegalArgumentException.class, () -> FeeEstimate.estimate(expensive, 1, 1_000_000));
    }

    @Test
    void historicalSamplesDistinguishTargetsAndMempoolPressureWins() {
        for (int i = 0; i < 10; i++) FeeEstimate.recordConfirmed(100, 101, 2_000, 100);
        for (int i = 0; i < 10; i++) FeeEstimate.recordConfirmed(100, 104, 5_000, 100);
        var empty = snapshot(List.of());
        assertEquals(20, FeeEstimate.estimate(empty, 1, 100).feeRate());
        assertEquals(20, FeeEstimate.estimate(empty, 3, 100).feeRate());
        assertEquals(50, FeeEstimate.estimate(empty, 6, 100).feeRate());
        assertEquals("historical", FeeEstimate.estimate(empty, 3, 100).source());

        FeeEstimate.reorg(102, 104);
        assertEquals(10, FeeEstimate.confirmationSampleCount());
        assertEquals(20, FeeEstimate.estimate(empty, 3, 100).feeRate());

        for (int i = 0; i < 4; i++) FeeEstimate.recordConfirmed(100, 101, 9_000, 100);
        assertEquals(20, FeeEstimate.estimate(empty, 1, 100).feeRate());
        FeeEstimate.recordConfirmed(100, 101, 9_000, 100);
        assertEquals(20, FeeEstimate.estimate(empty, 1, 100).feeRate());

        var backlog = snapshot(List.of(entry(FeeEstimate.BLOCK_CAPACITY_BYTES, 150, true, false, false)));
        assertEquals(20, FeeEstimate.estimate(backlog, 1, 100).feeRate());
        assertEquals("historical", FeeEstimate.estimate(backlog, 1, 100).source());
    }

    @Test
    void confirmationSamplesAreBoundedAndExpireByHeight() {
        for (int i = 0; i < 10_005; i++) FeeEstimate.recordConfirmed(1, 2, 100, 10);
        assertEquals(10_000, FeeEstimate.confirmationSampleCount());
        FeeEstimate.advanceHeight(2 + FeeEstimate.MAX_TARGET_BLOCKS + 1);
        assertEquals(0, FeeEstimate.confirmationSampleCount());
        assertEquals(1, FeeEstimate.estimate(snapshot(List.of()), 1, 100).feeRate());
    }

    private static TxPoolTransactionSnapshot sample(long size, long rate) {
        return entry(size, size * rate, true, false, false);
    }

    private static TxPoolTransactionSnapshot entry(long size, long fee, boolean available,
                                                    boolean packing, boolean doubleSpend) {
        // feeRate 特意不填写，估算器必须以整数 fee/size 为准。
        return new TxPoolTransactionSnapshot("tx", 0, "IN_POOL", List.of(), available, packing,
                !available, false, false, false, doubleSpend, fee, size, Double.NaN,
                1, 1, null, null, 0, List.of(), List.of(), List.of(), List.of());
    }

    private static TxPoolStatusSnapshot snapshot(List<TxPoolTransactionSnapshot> entries) {
        return new TxPoolStatusSnapshot(1234, 10_000, entries.size(), 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, List.copyOf(entries));
    }
}
