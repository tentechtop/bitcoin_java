package com.bit.coin.structure.tx;

import com.bit.coin.structure.script.Script;
import com.bit.coin.utils.Ed25519Signer;
import com.bit.coin.utils.SerializeUtils;
import com.bit.coin.utils.Sha;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.Arrays;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class TransactionValidationTest {

    private final KeyPair owner = Ed25519Signer.generateKeyPair();

    private Transaction transaction(int count, boolean scripted) throws Exception {
        Transaction tx = new Transaction();
        tx.setVersion(scripted ? 101 : 1);
        tx.setUtxoMap(new HashMap<>());
        byte[] hash = Sha.applyRIPEMD160(Sha.applySHA256(Ed25519Signer.extractPublicKeyCore(owner.getPublic())));
        byte[] lock = hash;
        if (scripted) {
            Script script = new Script();
            script.addOpCode(Script.OP_DUP);
            script.addOpCode(Script.OP_HASH160);
            script.addData(hash);
            script.addOpCode(Script.OP_EQUALVERIFY);
            script.addOpCode(Script.OP_CHECKSIG);
            lock = script.serialize();
        }
        for (int i = 0; i < count; i++) {
            UTXO source = new UTXO();
            byte[] id = new byte[32];
            Arrays.fill(id, (byte) (i + 1));
            source.setTxId(id);
            source.setValue(100_000L);
            source.setScript(lock);
            tx.getUtxoMap().put(SerializeUtils.bytesToHex(source.getKey()), source);
            TxInput input = new TxInput();
            input.setTxId(id);
            input.setScriptSig(new byte[97]);
            tx.getInputs().add(input);
        }
        TxOutput output = new TxOutput();
        output.setValue(90_000L);
        output.setScriptPubKey(hash);
        tx.getOutputs().add(output);
        sign(tx, owner);
        return tx;
    }

    private void sign(Transaction tx, KeyPair signer) {
        tx.sign(Ed25519Signer.extractPrivateKeyCore(signer.getPrivate()),
                Ed25519Signer.extractPublicKeyCore(signer.getPublic()));
        tx.setTxId(tx.calculateTxId());
    }

    @Test
    void onlyTheOutputOwnerCanSpend() throws Exception {
        Transaction tx = transaction(1, false);
        assertTrue(tx.validate());
        sign(tx, Ed25519Signer.generateKeyPair());
        assertFalse(tx.validate());
    }

    @Test
    void changingVersionCannotBypassOwnership() throws Exception {
        Transaction tx = transaction(1, false);
        tx.setVersion(101);
        sign(tx, Ed25519Signer.generateKeyPair());
        assertFalse(tx.validate());
    }

    @Test
    void everyScriptedInputMustPass() throws Exception {
        Transaction tx = transaction(2, true);
        assertTrue(tx.validate());
        tx.getInputs().get(1).getScriptSig()[0] ^= 1;
        tx.setTxId(tx.calculateTxId());
        assertFalse(tx.validate());
    }

    @Test
    void scriptedTransactionsStillCheckAmountsAndIdentity() throws Exception {
        Transaction tx = transaction(1, true);
        tx.getOutputs().getFirst().setValue(100_001L);
        sign(tx, owner);
        assertFalse(tx.validate());
        tx = transaction(1, true);
        tx.setTxId(new byte[32]);
        assertFalse(tx.validate());
    }

    @Test
    void duplicateInputsAreRejected() throws Exception {
        Transaction tx = transaction(1, false);
        tx.getInputs().add(tx.getInputs().getFirst());
        sign(tx, owner);
        assertFalse(tx.validate());
    }

    @Test
    void signatureCommitsToSequence() throws Exception {
        Transaction tx = transaction(1, false);
        tx.getInputs().getFirst().setSequence(1);
        tx.setTxId(tx.calculateTxId());
        assertFalse(tx.validate());
    }

    @Test
    void amountAndShapeLimitsFailClosed() throws Exception {
        Transaction tx = transaction(1, false);
        tx.getOutputs().getFirst().setValue(Long.MAX_VALUE);
        sign(tx, owner);
        assertFalse(tx.validate());
        tx.getInputs().set(0, null);
        assertFalse(tx.validate());
        assertFalse(new Transaction().validate());
    }

    @Test
    void unsupportedSignatureTypesAreRejected() throws Exception {
        Transaction tx = transaction(1, false);
        tx.getInputs().getFirst().getScriptSig()[64] = 0x11;
        tx.setTxId(tx.calculateTxId());
        assertFalse(tx.validate());
    }

    @Test
    void strictSerializationRejectsTrailingDataAndOversizedCounts() throws Exception {
        Transaction tx = transaction(1, false);
        assertTrue(Transaction.deserialize(tx.serialize()).calculateTxId().length == 32);
        byte[] trailing = Arrays.copyOf(tx.serialize(), tx.serialize().length + 1);
        assertThrows(IllegalArgumentException.class, () -> Transaction.deserialize(trailing));
        byte[] bomb = new byte[64];
        bomb[32] = 1;
        bomb[36] = (byte) 0xfe;
        Arrays.fill(bomb, 37, 41, (byte) 0x7f);
        assertThrows(IllegalArgumentException.class, () -> Transaction.deserialize(bomb));
    }
}
