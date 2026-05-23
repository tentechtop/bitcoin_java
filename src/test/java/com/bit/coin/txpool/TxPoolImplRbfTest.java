package com.bit.coin.txpool;

import com.bit.coin.cache.UTXOCache;
import com.bit.coin.database.DataBase;
import com.bit.coin.database.rocksDb.TableEnum;
import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.structure.tx.TransactionStatusResolver;
import com.bit.coin.structure.tx.TxInput;
import com.bit.coin.structure.tx.TxOutput;
import com.bit.coin.structure.tx.UTXO;
import com.bit.coin.structure.tx.UTXOStatusResolver;
import com.bit.coin.utils.Ed25519Signer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyPair;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TxPoolImplRbfTest {
    private static final long FINAL_SEQUENCE = 0xFFFFFFFFL;
    private static final long RBF_SEQUENCE = 0xFFFFFFFDL;

    private TxPoolImpl txPool;
    private UTXO sourceUtxo;
    private byte[] privateKey;
    private byte[] publicKey;

    @BeforeEach
    void setUp() {
        txPool = new TxPoolImpl();

        DataBase dataBase = mock(DataBase.class);
        UTXOCache utxoCache = mock(UTXOCache.class);
        RoutingTable routingTable = mock(RoutingTable.class);

        sourceUtxo = sourceUtxo();
        when(dataBase.get(eq(TableEnum.TX_TO_BLOCK), any(byte[].class))).thenReturn(null);
        when(utxoCache.getUTXO(any(byte[].class))).thenAnswer(invocation -> {
            byte[] key = invocation.getArgument(0);
            return Arrays.equals(key, sourceUtxo.getKey()) ? sourceUtxo : null;
        });

        ReflectionTestUtils.setField(txPool, "dataBase", dataBase);
        ReflectionTestUtils.setField(txPool, "utxoCache", utxoCache);
        ReflectionTestUtils.setField(txPool, "routingTable", routingTable);

        KeyPair keyPair = Ed25519Signer.generateKeyPair();
        privateKey = Ed25519Signer.extractPrivateKeyCore(keyPair.getPrivate());
        publicKey = Ed25519Signer.extractPublicKeyCore(keyPair.getPublic());
    }

    @Test
    void explicitRbfSequenceMarksMempoolEntry() {
        Transaction tx = signedTx(RBF_SEQUENCE, 80_000L);

        assertTrue(txPool.addTx(tx));

        TxPoolTransactionSnapshot snapshot = txPool.getStatusSnapshot().transactions().getFirst();
        assertTrue(snapshot.rbfEnabled());
        assertTrue(snapshot.flags().contains("RBF_ENABLED"));
    }

    @Test
    void finalSequenceConflictIsRejectedAsDoubleSpend() {
        Transaction original = signedTx(FINAL_SEQUENCE, 80_000L);
        Transaction replacement = signedTx(FINAL_SEQUENCE, 60_000L);

        assertTrue(txPool.addTx(original));
        assertFalse(txPool.addTx(replacement));

        assertNotNull(txPool.getTx(bytesToHex(original.getTxId())));
        assertNull(txPool.getTx(bytesToHex(replacement.getTxId())));
        assertEquals(1, txPool.getStatusSnapshot().totalCount());
    }

    @Test
    void optInConflictIsAutomaticallyReplacedOnAddTx() {
        Transaction original = signedTx(RBF_SEQUENCE, 80_000L);
        Transaction replacement = signedTx(FINAL_SEQUENCE, 70_000L);

        assertTrue(txPool.addTx(original));
        assertTrue(txPool.addTx(replacement));

        assertNull(txPool.getTx(bytesToHex(original.getTxId())));
        assertNotNull(txPool.getTx(bytesToHex(replacement.getTxId())));
        assertEquals(1, txPool.getStatusSnapshot().totalCount());
        assertFalse(TransactionStatusResolver.hasFlag(replacement.getStatus(), TransactionStatusResolver.RBF_ENABLED));
    }

    @Test
    void optInReplacementRequiresFeeIncrease() {
        Transaction original = signedTx(RBF_SEQUENCE, 80_000L);
        Transaction replacement = signedTx(FINAL_SEQUENCE, 76_000L);

        assertTrue(txPool.addTx(original));
        assertFalse(txPool.addTx(replacement));

        assertNotNull(txPool.getTx(bytesToHex(original.getTxId())));
        assertNull(txPool.getTx(bytesToHex(replacement.getTxId())));
        assertEquals(1, txPool.getStatusSnapshot().totalCount());
    }

    private Transaction signedTx(long sequence, long outputValue) {
        Transaction tx = new Transaction();
        tx.setVersion(1);
        tx.setLockTime(0);

        TxInput input = new TxInput();
        input.setTxId(sourceUtxo.getTxId());
        input.setIndex(sourceUtxo.getIndex());
        input.setSequence(sequence);
        input.setScriptSig(new byte[TxInput.SCRIPT_SIG_LENGTH]);
        tx.getInputs().add(input);

        TxOutput output = new TxOutput();
        output.setValue(outputValue);
        output.setScriptPubKey(Arrays.copyOf(sourceUtxo.getScript(), sourceUtxo.getScript().length));
        tx.getOutputs().add(output);

        Map<String, UTXO> utxoMap = new HashMap<>();
        utxoMap.put(bytesToHex(sourceUtxo.getKey()), sourceUtxo);
        tx.setUtxoMap(utxoMap);
        tx.sign(privateKey, publicKey);
        tx.setTxId(tx.calculateTxId());
        return tx;
    }

    private UTXO sourceUtxo() {
        UTXO utxo = new UTXO();
        byte[] txId = new byte[32];
        Arrays.fill(txId, (byte) 7);
        byte[] script = new byte[20];
        Arrays.fill(script, (byte) 3);

        utxo.setTxId(txId);
        utxo.setIndex(0);
        utxo.setValue(100_000L);
        utxo.setScript(script);
        utxo.setCoinbase(false);
        utxo.setHeight(1);
        utxo.setStatus(UTXOStatusResolver.setLifecycleStatus(0L, UTXOStatusResolver.CONFIRMED_UNSPENT));
        return utxo;
    }
}
