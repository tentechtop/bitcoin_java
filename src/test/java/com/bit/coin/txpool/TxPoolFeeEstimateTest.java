package com.bit.coin.txpool;

import com.bit.coin.estimate.FeeEstimate;
import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.structure.tx.TransactionStatusResolver;
import com.bit.coin.structure.tx.TxInput;
import com.bit.coin.structure.tx.TxOutput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static org.junit.jupiter.api.Assertions.*;

class TxPoolFeeEstimateTest {
    private final TxPoolImpl pool = new TxPoolImpl();

    @AfterEach
    void close() { pool.close(); }

    @Test
    void transactionEstimateUsesWireSizeWithoutResolvingUtxosOrMutatingTheTransaction() throws Exception {
        Transaction tx = transaction(0, TxInput.SCRIPT_SIG_LENGTH);
        tx.setFee(777);
        long quotedFee = pool.estimateTxFee(tx);
        assertEquals(209, quotedFee); // 32-byte txId + 10-byte envelope + 138-byte input + 29-byte output
        assertEquals(777, tx.getFee());
        assertNull(ReflectionTestUtils.getField(tx, "txId"));
        assertEquals(tx.serialize().length, quotedFee);
        assertEquals(0, pool.transactionCount());
    }

    @Test
    @SuppressWarnings("unchecked")
    void estimateReadsTheFullPoolBeyondTheHundredTransactionListingLimit() {
        Map<String, Transaction> entries = (Map<String, Transaction>) ReflectionTestUtils.getField(pool, "mempool");
        assertNotNull(entries);
        // Populate pool state directly: these are size fixtures, not admission/signature tests.
        for (int i = 0; i < 150; i++) {
            Transaction tx = transaction(i, 9_000);
            tx.setFee(FeeEstimate.transactionSize(tx) * 5);
            entries.put(bytesToHex(tx.getTxId()), tx);
        }
        assertEquals(100, pool.getAllTxs().length);
        var quote = pool.estimateFee(1, 250);
        assertEquals(150, quote.sampleCount());
        assertEquals(6, quote.feeRate());
        assertEquals(1_500, quote.fee());
        assertTrue(entries.values().stream().allMatch(tx ->
                TransactionStatusResolver.hasLifecycle(tx.getStatus(), TransactionStatusResolver.IN_POOL)));

        entries.clear();
        assertEquals(1, pool.estimateFee(1, 250).feeRate());
    }

    @Test
    void rejectsMissingMalformedAndCoinbaseTransactions() {
        assertThrows(IllegalArgumentException.class, () -> pool.estimateTxFee(null));
        assertThrows(IllegalArgumentException.class, () -> pool.estimateTxFee(new Transaction()));
        Transaction tx = transaction(0, 97);
        tx.getInputs().getFirst().setTxId(null);
        assertThrows(IllegalArgumentException.class, () -> pool.estimateTxFee(tx));
        Transaction coinbase = transaction(0, 97);
        coinbase.getInputs().getFirst().setTxId(new byte[32]);
        assertThrows(IllegalArgumentException.class, () -> pool.estimateTxFee(coinbase));
    }

    private static Transaction transaction(int index, int scriptLength) {
        Transaction tx = new Transaction();
        tx.setVersion(1);
        tx.setStatus(TransactionStatusResolver.setLifecycleStatus(0, TransactionStatusResolver.IN_POOL));
        TxInput input = new TxInput();
        byte[] parent = new byte[32];
        parent[0] = 1;
        input.setTxId(parent);
        input.setIndex(index);
        input.setScriptSig(new byte[scriptLength]);
        tx.setInputs(List.of(input));
        TxOutput output = new TxOutput();
        output.setValue(1_000);
        output.setScriptPubKey(new byte[20]);
        tx.setOutputs(List.of(output));
        return tx;
    }
}
