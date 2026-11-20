package com.bit.coin.txpool;

import com.bit.coin.database.DataBase;
import com.bit.coin.database.rocksDb.TableEnum;
import com.bit.coin.estimate.FeeEstimate;
import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.structure.tx.TransactionStatusResolver;
import com.bit.coin.structure.tx.TxInput;
import com.bit.coin.structure.tx.TxOutput;
import com.bit.coin.structure.tx.UTXO;
import com.bit.coin.utils.Ed25519Signer;
import com.bit.coin.utils.Sha;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyPair;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TxPoolFeeEstimateTest {

    private final TxPoolImpl pool = new TxPoolImpl();

    @AfterEach
    void close() {
        pool.close();
    }

    @Test
    void confirmedTransactionsCreateHistoryForSingleAndRecommendedQuotes() {
        FeeEstimate.reorg(-1, Integer.MAX_VALUE);
        for (int i = 0; i < 5; i++) {
            FeeEstimate.recordConfirmed(100, 101, 1_000, 100);
        }
        var single = pool.estimateFee(1, 250);
        assertEquals(0, single.sampleCount());
        assertEquals(10, single.feeRate());
        assertEquals("historical", single.source());
        assertEquals(List.of(10L, 10L, 10L), pool.recommendedFeeEstimates(250).stream()
                .map(FeeEstimate.Estimate::feeRate).toList());
    }

    @Test
    void transactionEstimateUsesWireSizeWithoutResolvingUtxosOrMutatingTheTransaction() throws Exception {
        FeeEstimate.reorg(-1, Integer.MAX_VALUE);
        Transaction tx = transaction(0, TxInput.SCRIPT_SIG_LENGTH);
        tx.setFee(777);
        long quotedFee = pool.estimateTxFee(tx);
        assertEquals(tx.serialize().length, quotedFee);
        assertEquals(777, tx.getFee());
        assertNotNull(ReflectionTestUtils.getField(tx, "txId"));
        assertEquals(0, pool.transactionCount());
    }

    @Test
    @SuppressWarnings("unchecked")
    void estimateReadsTheFullPoolBeyondTheHundredTransactionListingLimit() {
        FeeEstimate.reorg(-1, Integer.MAX_VALUE);
        Map<String, Transaction> entries = (Map<String, Transaction>) ReflectionTestUtils.getField(pool, "mempool");
        assertNotNull(entries);
        // 直接填充交易池以构造所需规模的测试数据，省去入池检查和签名验证。
        for (int i = 0; i < 150; i++) {
            Transaction tx = transaction(i, 9_000);
            tx.setFee(FeeEstimate.transactionSize(tx) * 5);
            entries.put(bytesToHex(tx.getTxId()), tx);
        }
        assertEquals(100, pool.getAllTxs().length);
        var quote = pool.estimateFee(1, 250);
        assertEquals(150, quote.sampleCount());
        assertEquals(5, quote.feeRate());
        assertEquals(1_250, quote.fee());
        assertTrue(entries.values().stream().allMatch(
                tx -> TransactionStatusResolver.hasLifecycle(tx.getStatus(), TransactionStatusResolver.IN_POOL)));

        entries.clear();
        FeeEstimate.reorg(-1, Integer.MAX_VALUE);
        assertEquals(1, pool.estimateFee(1, 250).feeRate());
    }

    @Test
    void revalidateChecksTransactionsBeyondTheListingLimit() {
        Map<String, byte[]> confirmed = new HashMap<>();
        DataBase database = mock(DataBase.class);
        when(database.get(eq(TableEnum.TUTXO), any(byte[].class)))
                .thenAnswer(call -> confirmed.get(bytesToHex(call.getArgument(1))));
        ReflectionTestUtils.setField(pool, "dataBase", database);

        KeyPair owner = Ed25519Signer.generateKeyPair();
        byte[] publicKey = Ed25519Signer.extractPublicKeyCore(owner.getPublic());
        byte[] privateKey = Ed25519Signer.extractPrivateKeyCore(owner.getPrivate());
        byte[] lock = Sha.applyRIPEMD160(Sha.applySHA256(publicKey));
        Map<String, UTXO> sources = new HashMap<>();
        for (int i = 0; i < 101; i++) {
            Transaction tx = transaction(i, TxInput.SCRIPT_SIG_LENGTH);
            UTXO source = new UTXO();
            source.setTxId(tx.getInputs().getFirst().getTxId());
            source.setIndex(i);
            source.setValue(2_000);
            source.setScript(lock);
            String key = bytesToHex(source.getKey());
            confirmed.put(key, source.serialize());
            tx.setUtxoMap(Map.of(key, source));
            tx.sign(privateKey, publicKey);
            tx.setTxId(tx.calculateTxId());
            assertTrue(pool.addTx(tx));
            sources.put(bytesToHex(tx.getTxId()), source);
        }
        Transaction[] listed = pool.getAllTxs();
        assertEquals(100, listed.length);
        for (Transaction tx : listed) {
            sources.remove(bytesToHex(tx.getTxId()));
        }
        assertEquals(1, sources.size());
        var unlisted = sources.entrySet().iterator().next();
        byte[] missingKey = unlisted.getValue().getKey();
        assertNotNull(confirmed.remove(bytesToHex(missingKey)));
        clearInvocations(database);

        pool.revalidate();

        verify(database).get(TableEnum.TUTXO, missingKey);
        assertNull(pool.getTx(unlisted.getKey()));
        assertEquals(100, pool.transactionCount());
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

    @SuppressWarnings("unchecked")
    private Map<String, Transaction> poolEntries() {
        return (Map<String, Transaction>) ReflectionTestUtils.getField(pool, "mempool");
    }
}
