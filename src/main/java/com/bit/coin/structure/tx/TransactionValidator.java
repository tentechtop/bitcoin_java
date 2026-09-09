package com.bit.coin.structure.tx;

import com.bit.coin.structure.script.Script;
import com.bit.coin.structure.script.ScriptExecutor;
import com.bit.coin.utils.Ed25519Signer;
import com.bit.coin.utils.SerializeUtils;
import com.bit.coin.utils.Sha;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Deterministic transaction rules shared by block validation and the mempool. */
public final class TransactionValidator {
    public static final long MAX_MONEY = 21_000_000L * 100_000_000L;
    public static final int MAX_TRANSACTION_BYTES = 1_000_000;
    public static final int MAX_INPUTS = 10_000;
    public static final int MAX_OUTPUTS = 10_000;
    public static final int MAX_SCRIPT_BYTES = 10_000;
    public static final int MAX_SIGNATURE_CHECKS = 1_000;

    private TransactionValidator() {}

    public static boolean validate(Transaction tx) {
        try {
            if (tx == null || (tx.getVersion() != 0 && tx.getVersion() != 1 && tx.getVersion() != 101)
                    || tx.getLockTime() < 0 || tx.getLockTime() > 0xFFFFFFFFL
                    || tx.getInputs() == null || tx.getInputs().isEmpty() || tx.getInputs().size() > MAX_INPUTS
                    || tx.getOutputs() == null || tx.getOutputs().isEmpty() || tx.getOutputs().size() > MAX_OUTPUTS) {
                return false;
            }
            long outputValue = 0;
            for (TxOutput output : tx.getOutputs()) {
                if (output == null || !moneyRange(output.getValue()) || output.getScriptPubKey() == null
                        || output.getScriptPubKey().length == 0 || output.getScriptPubKey().length > MAX_SCRIPT_BYTES
                        || (tx.getVersion() <= 1 && output.getScriptPubKey().length != 20)) {
                    return false;
                }
                outputValue = Math.addExact(outputValue, output.getValue());
                if (!moneyRange(outputValue)) return false;
            }
            Set<String> inputs = new HashSet<>();
            for (TxInput input : tx.getInputs()) {
                if (input == null || input.getTxId() == null || input.getTxId().length != 32 || input.getIndex() < 0
                        || input.getSequence() < 0 || input.getSequence() > 0xFFFFFFFFL
                        || input.getScriptSig() == null || input.getScriptSig().length > MAX_SCRIPT_BYTES
                        || !inputs.add(SerializeUtils.bytesToHex(UTXO.getKey(input.getTxId(), input.getIndex())))) {
                    return false;
                }
            }
            if (tx.serialize().length > MAX_TRANSACTION_BYTES || !Arrays.equals(tx.getTxId(), tx.calculateTxId())) {
                return false;
            }
            if (tx.isCoinbase()) return true; // Subsidy and maturity require the candidate chain context.
            if (tx.getUtxoMap() == null) return false;
            if (signatureCost(tx) > MAX_SIGNATURE_CHECKS) return false;
            long inputValue = 0;
            for (int i = 0; i < tx.getInputs().size(); i++) {
                TxInput input = tx.getInputs().get(i);
                if (Arrays.equals(input.getTxId(), new byte[32])) return false;
                String key = SerializeUtils.bytesToHex(UTXO.getKey(input.getTxId(), input.getIndex()));
                UTXO source = tx.getUtxoMap().get(key);
                if (source == null || !moneyRange(source.getValue()) || source.getScript() == null
                        || source.getScript().length == 0 || source.getScript().length > MAX_SCRIPT_BYTES
                        || !Arrays.equals(source.getKey(), UTXO.getKey(input.getTxId(), input.getIndex()))) return false;
                inputValue = Math.addExact(inputValue, source.getValue());
                if (!moneyRange(inputValue)) return false;

                // The spent output determines its locking rules; the spender cannot choose another verifier.
                if (source.getScript().length == 20) {
                    if (input.getScriptSig().length != TxInput.SCRIPT_SIG_LENGTH) return false;
                    byte[] publicKey = input.getPublicKey();
                    if (!Arrays.equals(source.getScript(), Sha.applyRIPEMD160(Sha.applySHA256(publicKey)))) return false;
                    byte type = input.getSignatureType();
                    if (!validSignatureType(type)) return false;
                    byte[] message = Sha.applySHA256(tx.prepareDataForSigning(i, source.getScript(), type));
                    if (!Ed25519Signer.fastVerify(publicKey, message, input.getSignature())) return false;
                } else {
                    if (tx.getVersion() != 101) return false;
                    Script unlocking = input.getScriptSig().length == 97 ? Script.deserialize(input.getScriptSig())
                            : Script.deserializeProgram(input.getScriptSig());
                    Script locking = Script.deserializeProgram(source.getScript());
                    // Unlocking code may only push values, never change control flow around the locking code.
                    for (Script.ScriptElement element : unlocking.getElements()) {
                        if (element.isOpCode() && element.getOpCode() != Script.OP_0
                                && (element.getOpCode() < Script.OP_1 || element.getOpCode() > Script.OP_16)) return false;
                    }
                    if (!new ScriptExecutor().execute(ScriptExecutor.combineScripts(unlocking, locking), tx, i)) return false;
                }
            }
            return inputValue >= outputValue;
        } catch (Exception malformed) {
            return false;
        }
    }

    public static int memoryWeight(Transaction tx) {
        try {
            long weight = tx.serialize().length + 256L * (tx.getInputs().size() + tx.getOutputs().size()) + 512;
            if (tx.getUtxoMap() != null) for (UTXO source : tx.getUtxoMap().values())
                weight += 256L + (source.getScript() == null ? 0 : source.getScript().length);
            return (int) Math.min(Integer.MAX_VALUE, weight);
        } catch (Exception invalid) { return Integer.MAX_VALUE; }
    }

    public static boolean moneyRange(long amount) {
        return amount >= 0 && amount <= MAX_MONEY;
    }

    /** Conservative bound, including branches not taken and unordered multisig matching attempts. */
    public static int signatureCost(Transaction tx) throws java.io.IOException {
        if (tx.isCoinbase()) return 0;
        int cost = 0;
        for (TxInput input : tx.getInputs()) {
            UTXO source = tx.getUtxoMap().get(UTXO.getKeyStr(input.getTxId(), input.getIndex()));
            if (source == null || source.getScript() == null) throw new IllegalArgumentException("Missing source");
            if (source.getScript().length == 20) { cost++; continue; }
            for (var element : Script.deserializeProgram(source.getScript()).getElements()) {
                if (!element.isOpCode()) continue;
                int op = element.getOpCode();
                if (op == Script.OP_CHECKSIG || op == Script.OP_CHECKSIGVERIFY) cost++;
                if (op == Script.OP_CHECKMULTISIG || op == Script.OP_CHECKMULTISIGVERIFY) cost += 400;
            }
        }
        return cost;
    }

    public static boolean validSignatureType(byte type) {
        int unsigned = Byte.toUnsignedInt(type);
        int base = unsigned & 0x1f;
        return (unsigned & ~0x83) == 0 && base >= 1 && base <= 3;
    }
}
