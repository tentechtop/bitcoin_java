package com.bit.coin.txpool;

import com.bit.coin.database.DataBase;
import com.bit.coin.database.rocksDb.TableEnum;
import com.bit.coin.structure.tx.*;
import com.bit.coin.utils.Ed25519Signer;
import com.bit.coin.utils.Sha;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.util.*;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TxPoolCapacityTest {
    private final KeyPair owner = Ed25519Signer.generateKeyPair();
    private final byte[] publicKey = Ed25519Signer.extractPublicKeyCore(owner.getPublic());
    private final byte[] lock = Sha.applyRIPEMD160(Sha.applySHA256(publicKey));
    private final Map<String, byte[]> confirmed = new HashMap<>();
    private TxPoolImpl pool;
    private int sourceId;

    @AfterEach void close() { if (pool != null) pool.close(); }

    private void openPool(int capacity, long bytes) {
        pool = new TxPoolImpl(capacity, bytes);
        DataBase database = mock(DataBase.class);
        when(database.get(eq(TableEnum.TUTXO), any(byte[].class)))
                .thenAnswer(call -> confirmed.get(bytesToHex(call.getArgument(1))));
        ReflectionTestUtils.setField(pool, "dataBase", database);
    }

    private UTXO source() {
        UTXO source = new UTXO();
        source.setTxId(ByteBuffer.allocate(32).putInt(++sourceId).array());
        source.setValue(100_000); source.setScript(lock); source.setHeight(1);
        confirmed.put(bytesToHex(source.getKey()), source.serialize());
        return source;
    }

    private UTXO output(Transaction tx) {
        UTXO source = new UTXO(); source.setTxId(tx.getTxId());
        source.setValue(tx.getOutputs().getFirst().getValue()); source.setScript(lock);
        return source;
    }

    private Transaction spend(UTXO source, long... amounts) {
        return spend(List.of(source), amounts);
    }

    private Transaction spend(List<UTXO> sources, long... amounts) {
        Transaction tx = new Transaction(); tx.setVersion(1);
        Map<String, UTXO> inputs = new HashMap<>();
        for (UTXO source : sources) {
            TxInput input = new TxInput(); input.setTxId(source.getTxId()); input.setIndex(source.getIndex());
            input.setSequence(0xFFFFFFFDL); input.setScriptSig(new byte[TxInput.SCRIPT_SIG_LENGTH]);
            tx.getInputs().add(input);
            inputs.put(bytesToHex(source.getKey()), source);
        }
        for (long amount : amounts) {
            TxOutput output = new TxOutput(); output.setValue(amount); output.setScriptPubKey(lock);
            tx.getOutputs().add(output);
        }
        tx.setUtxoMap(inputs);
        tx.sign(Ed25519Signer.extractPrivateKeyCore(owner.getPrivate()), publicKey);
        tx.setTxId(tx.calculateTxId());
        return tx;
    }

    @Test void fullPoolKeepsAllCandidateAncestorsAndEvictsAnUnrelatedTransaction() {
        openPool(3, 64L * 1024 * 1024);
        Transaction root = spend(source(), 98_000);
        Transaction parent = spend(output(root), 96_000);
        Transaction unrelated = spend(source(), 90_000);
        assertTrue(pool.addTx(root)); assertTrue(pool.addTx(parent)); assertTrue(pool.addTx(unrelated));
        Transaction child = spend(output(parent), 50_000);

        assertTrue(pool.addTx(child));
        assertNotNull(pool.getTx(root.getTxId())); assertNotNull(pool.getTx(parent.getTxId()));
        assertNotNull(pool.getTx(child.getTxId())); assertNull(pool.getTx(unrelated.getTxId()));
        assertEquals(3, pool.transactionCount());
        assertEquals(List.of(bytesToHex(root.getTxId()), bytesToHex(parent.getTxId()), bytesToHex(child.getTxId())),
                Arrays.stream(pool.getBestTxs()).map(tx -> bytesToHex(tx.getTxId())).toList());
    }

    @Test void rejectsChildWithoutChangingPoolWhenOnlyItsAncestorsCanBeEvicted() {
        openPool(2, 64L * 1024 * 1024);
        Transaction root = spend(source(), 98_000), parent = spend(output(root), 96_000);
        assertTrue(pool.addTx(root)); assertTrue(pool.addTx(parent));
        long beforeBytes = pool.estimatedMemoryBytes();
        Transaction child = spend(output(parent), 50_000);

        assertFalse(pool.addTx(child));
        assertNotNull(pool.getTx(root.getTxId())); assertNotNull(pool.getTx(parent.getTxId()));
        assertNull(pool.getTx(child.getTxId())); assertEquals(2, pool.transactionCount());
        assertEquals(beforeBytes, pool.estimatedMemoryBytes());
        assertEquals(1, pool.getStatusSnapshot().dependencyCount());
    }

    @Test void insufficientMemoryDoesNotPartiallyEvictUnrelatedTransactions() {
        Transaction parent = spend(source(), 98_000), unrelated = spend(source(), 90_000);
        long limit = (long) TransactionValidator.memoryWeight(parent) + TransactionValidator.memoryWeight(unrelated);
        openPool(10, limit);
        assertTrue(pool.addTx(parent)); assertTrue(pool.addTx(unrelated));
        Transaction child = spend(output(parent), 20_000, 20_000);

        assertFalse(pool.addTx(child));
        assertNotNull(pool.getTx(parent.getTxId())); assertNotNull(pool.getTx(unrelated.getTxId()));
        assertNull(pool.getTx(child.getTxId())); assertEquals(2, pool.transactionCount());
        assertEquals(limit, pool.estimatedMemoryBytes());
    }

    @Test void plansMultipleEvictionsWhenOneCannotFreeEnoughMemory() {
        Transaction first = spend(source(), 98_000), second = spend(source(), 97_000), retained = spend(source(), 96_000);
        long limit = 3L * TransactionValidator.memoryWeight(first);
        openPool(10, limit);
        assertTrue(pool.addTx(first)); assertTrue(pool.addTx(second)); assertTrue(pool.addTx(retained));
        Transaction candidate = spend(source(), 10_000, 10_000);

        assertTrue(pool.addTx(candidate));
        assertNull(pool.getTx(first.getTxId())); assertNull(pool.getTx(second.getTxId()));
        assertNotNull(pool.getTx(retained.getTxId())); assertNotNull(pool.getTx(candidate.getTxId()));
        assertEquals(2, pool.transactionCount());
        assertEquals((long) TransactionValidator.memoryWeight(retained) + TransactionValidator.memoryWeight(candidate), pool.estimatedMemoryBytes());
        assertTrue(pool.estimatedMemoryBytes() <= limit);
    }

    @Test void evictionRemovesDescendantsAndReleasesTheirInputIndexes() {
        openPool(3, 64L * 1024 * 1024);
        Transaction root = spend(source(), 98_000), child = spend(output(root), 90_000), unrelated = spend(source(), 90_000);
        assertTrue(pool.addTx(root)); assertTrue(pool.addTx(child)); assertTrue(pool.addTx(unrelated));
        Transaction candidate = spend(source(), 20_000);

        assertTrue(pool.addTx(candidate));
        assertNull(pool.getTx(root.getTxId())); assertNull(pool.getTx(child.getTxId()));
        assertNotNull(pool.getTx(unrelated.getTxId())); assertNotNull(pool.getTx(candidate.getTxId()));
        assertEquals(0, pool.getStatusSnapshot().dependencyCount());
        assertTrue(pool.addTx(root), "Eviction must release the original confirmed input for future admission");
    }

    @Test void doesNotEvictHighFeeDescendantPackageForLowerPackageRateCandidate() {
        openPool(2, 64L * 1024 * 1024);
        Transaction parent = spend(source(), 99_000);
        Transaction child = spend(output(parent), 10_000);
        assertTrue(pool.addTx(parent));
        assertTrue(pool.addTx(child));
        Transaction candidate = spend(source(), 70_000);

        assertFalse(pool.addTx(candidate));
        assertNotNull(pool.getTx(parent.getTxId()));
        assertNotNull(pool.getTx(child.getTxId()));
        assertNull(pool.getTx(candidate.getTxId()));
    }

    @Test void doesNotEvictTransactionsBeingPacked() {
        openPool(2, 64L * 1024 * 1024);
        Transaction root = spend(source(), 98_000), child = spend(output(root), 90_000);
        assertTrue(pool.addTx(root)); assertTrue(pool.addTx(child));
        assertEquals(2, pool.extractTxsForBlock().length);
        Transaction candidate = spend(source(), 10_000);

        assertFalse(pool.addTx(candidate));
        assertEquals(2, pool.transactionCount()); assertEquals(2, pool.getStatusSnapshot().packingCount());
        assertNotNull(pool.getTx(root.getTxId())); assertNotNull(pool.getTx(child.getTxId()));
        assertNull(pool.getTx(candidate.getTxId()));
    }

    @Test void repricesTheOtherParentAfterSelectingTheirSharedChild() {
        Transaction cheap = spend(source(), 99_000), expensive = spend(source(), 84_000);
        Transaction shared = spend(List.of(output(cheap), output(expensive)), 175_000);
        Transaction unrelated = spend(source(), 88_000);
        List<Transaction> original = List.of(cheap, expensive, shared, unrelated);
        long limit = original.stream().mapToLong(TransactionValidator::memoryWeight).sum();
        openPool(10, limit);
        original.forEach(tx -> assertTrue(pool.addTx(tx)));

        // Both candidates need more space than the cheap parent and its shared child can free.
        // The other parent's rate rises above the unrelated transaction's when the child is deducted.
        UTXO input = source();
        long[] amounts = new long[9];
        Arrays.fill(amounts, 8_500);
        Transaction lowerBid = spend(input, amounts);
        assertTrue(TransactionValidator.memoryWeight(lowerBid) >
                TransactionValidator.memoryWeight(cheap) + TransactionValidator.memoryWeight(shared));
        assertFalse(pool.addTx(lowerBid));
        original.forEach(tx -> assertNotNull(pool.getTx(tx.getTxId())));
        assertEquals(4, pool.transactionCount());
        assertEquals(limit, pool.estimatedMemoryBytes());
        assertEquals(2, pool.getStatusSnapshot().dependencyCount());

        Arrays.fill(amounts, 1_000);
        Transaction higherBid = spend(input, amounts);
        assertTrue(pool.addTx(higherBid));
        assertNull(pool.getTx(cheap.getTxId()));
        assertNull(pool.getTx(shared.getTxId()));
        assertNull(pool.getTx(unrelated.getTxId()));
        assertNotNull(pool.getTx(expensive.getTxId()));
        assertNotNull(pool.getTx(higherBid.getTxId()));
        assertEquals(2, pool.transactionCount());
        assertEquals((long) TransactionValidator.memoryWeight(expensive) + TransactionValidator.memoryWeight(higherBid),
                pool.estimatedMemoryBytes());
    }

    @Test void candidateMustBeatTheEvictionRateWithItsOwnFee() {
        openPool(2, 64L * 1024 * 1024);
        Transaction parent = spend(source(), 50_000), unrelated = spend(source(), 99_000);
        assertTrue(pool.addTx(parent)); assertTrue(pool.addTx(unrelated));

        assertFalse(pool.addTx(spend(output(parent), 49_000)));
        assertNotNull(pool.getTx(parent.getTxId())); assertNotNull(pool.getTx(unrelated.getTxId()));
        assertTrue(pool.addTx(spend(output(parent), 48_999)));
        assertNull(pool.getTx(unrelated.getTxId()));
    }
}
