package com.bit.coin.blockchain;

import com.bit.coin.config.ParamsConfig;
import com.bit.coin.database.rocksDb.RocksDb;
import com.bit.coin.database.rocksDb.TableEnum;
import com.bit.coin.structure.block.Block;
import com.bit.coin.structure.tx.*;
import com.bit.coin.utils.SerializeUtils;

import java.io.*;
import java.util.*;
import java.util.function.Function;

/** A block's exact before/after images, including coinbase origin and height, for atomic replay and undo. */
public record UtxoTransition(Map<String, UTXO> before, Map<String, UTXO> after) {
    private static final int MAX_ENTRIES = 400_000;
    private static final int MAX_BYTES = 32_000_000;

    public static UtxoTransition validate(Block block, Function<byte[], UTXO> base,
                                          ParamsConfig.Params params, long medianTime) {
        return validate(block, base, params, medianTime, height -> { throw new IllegalArgumentException("Missing time-lock ancestry"); });
    }

    public static UtxoTransition validate(Block block, Function<byte[], UTXO> base, ParamsConfig.Params params,
                                         long medianTime, java.util.function.IntToLongFunction sourceMedianTimes) {
        Map<String, UTXO> before = new LinkedHashMap<>();
        Map<String, UTXO> after = new LinkedHashMap<>();
        Function<byte[], UTXO> view = key -> {
            String hex = SerializeUtils.bytesToHex(key);
            return after.containsKey(hex) ? after.get(hex) : base.apply(key);
        };
        Set<String> txIds = new HashSet<>();
        long fees = 0;
        long scriptBytes = 0;
        long signatureChecks = 0;
        for (int position = 0; position < block.getTransactions().size(); position++) {
            Transaction tx = block.getTransactions().get(position);
            if (tx == null || !txIds.add(SerializeUtils.bytesToHex(tx.getTxId())) || tx.isCoinbase() != (position == 0)) {
                throw new IllegalArgumentException("Duplicate transaction or invalid coinbase position");
            }
            Map<String, UTXO> sources = new HashMap<>();
            long inputValue = 0;
            if (!tx.isCoinbase()) {
                for (TxInput input : tx.getInputs()) {
                    byte[] key = UTXO.getKey(input.getTxId(), input.getIndex());
                    String hex = SerializeUtils.bytesToHex(key);
                    UTXO source = view.apply(key);
                    if (source == null || sources.putIfAbsent(hex, source) != null) throw new IllegalArgumentException("Missing or duplicate input");
                    if (source.isCoinbase() && block.getHeight() - source.getHeight() < params.getCoinbaseMaturity()) {
                        throw new IllegalArgumentException("Immature coinbase");
                    }
                    inputValue = Math.addExact(inputValue, source.getValue());
                    scriptBytes += source.getScript().length;
                    if (scriptBytes > ConsensusRules.MAX_BLOCK_BYTES) throw new IllegalArgumentException("Block script budget exceeded");
                }
            }
            tx.setUtxoMap(sources);
            try { signatureChecks += TransactionValidator.signatureCost(tx); }
            catch (IOException malformed) { throw new IllegalArgumentException("Malformed locking script", malformed); }
            if (signatureChecks > 20_000) throw new IllegalArgumentException("Block signature budget exceeded");
            if (!tx.validate() || !ConsensusRules.isFinal(tx, block.getHeight(), medianTime)
                    || !ConsensusRules.relativeLocksSatisfied(tx, block.getHeight(), medianTime, sourceMedianTimes)) throw new IllegalArgumentException("Invalid transaction");
            long outputValue = 0;
            for (TxOutput output : tx.getOutputs()) outputValue = Math.addExact(outputValue, output.getValue());
            if (!tx.isCoinbase()) {
                fees = Math.addExact(fees, inputValue - outputValue);
                if (!TransactionValidator.moneyRange(fees)) throw new IllegalArgumentException("Fee out of range");
                for (Map.Entry<String, UTXO> entry : sources.entrySet()) {
                    if (!before.containsKey(entry.getKey())) before.put(entry.getKey(), entry.getValue());
                    after.put(entry.getKey(), null);
                }
            }
            for (int index = 0; index < tx.getOutputs().size(); index++) {
                TxOutput output = tx.getOutputs().get(index);
                UTXO created = new UTXO();
                created.setTxId(tx.getTxId());
                created.setIndex(index);
                created.setValue(output.getValue());
                created.setScript(output.getScriptPubKey());
                created.setHeight(block.getHeight());
                created.setCoinbase(tx.isCoinbase());
                String hex = SerializeUtils.bytesToHex(created.getKey());
                if (view.apply(created.getKey()) != null || before.containsKey(hex)) throw new IllegalArgumentException("Output overwrites an existing outpoint");
                before.put(hex, null);
                after.put(hex, created);
            }
        }
        long coinbaseValue = 0;
        for (TxOutput output : block.getTransactions().getFirst().getOutputs()) coinbaseValue = Math.addExact(coinbaseValue, output.getValue());
        if (coinbaseValue > Math.addExact(ConsensusRules.subsidy(block.getHeight(), params), fees)) throw new IllegalArgumentException("Excessive subsidy");
        UtxoTransition transition = new UtxoTransition(before, after);
        transition.serialize(); // Enforce a deterministic bound before persistence or propagation.
        return transition;
    }

    public void overlay(Map<String, UTXO> view, boolean forward) {
        view.putAll(forward ? after : before);
    }

    public void appendOperations(List<RocksDb.DbOperation> operations, boolean forward) {
        Map<String, UTXO> from = forward ? before : after;
        Map<String, UTXO> to = forward ? after : before;
        for (String key : before.keySet()) {
            UTXO oldValue = from.get(key);
            UTXO value = to.get(key);
            if (oldValue != null && oldValue.getScript().length == 20) operations.add(new RocksDb.DbOperation(TableEnum.ADDR_TO_UTXO, oldValue.getAddressKey(), null, RocksDb.DbOperation.OpType.DELETE));
            operations.add(new RocksDb.DbOperation(TableEnum.TUTXO, SerializeUtils.hexToBytes(key), value == null ? null : value.serialize(),
                    value == null ? RocksDb.DbOperation.OpType.DELETE : RocksDb.DbOperation.OpType.INSERT));
            if (value != null && value.getScript().length == 20) operations.add(new RocksDb.DbOperation(TableEnum.ADDR_TO_UTXO, value.getAddressKey(), SerializeUtils.longToLittleEndian(value.getValue()), RocksDb.DbOperation.OpType.INSERT));
        }
    }

    public byte[] serialize() {
        if (before.size() > MAX_ENTRIES || !before.keySet().equals(after.keySet())) throw new IllegalArgumentException("Invalid UTXO transition");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(1);
            out.writeInt(before.size());
            for (String key : before.keySet()) {
                out.write(SerializeUtils.hexToBytes(key));
                writeValue(out, before.get(key));
                writeValue(out, after.get(key));
                if (bytes.size() > MAX_BYTES) throw new IllegalArgumentException("UTXO transition exceeds byte limit");
            }
            return bytes.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public static UtxoTransition deserialize(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IllegalStateException("Missing or oversized undo record");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 1) throw new IOException("Unsupported undo version");
            int count = in.readInt();
            if (count < 0 || count > MAX_ENTRIES) throw new IOException("Invalid undo count");
            Map<String, UTXO> before = new LinkedHashMap<>();
            Map<String, UTXO> after = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                byte[] key = new byte[36];
                in.readFully(key);
                String hex = SerializeUtils.bytesToHex(key);
                if (before.containsKey(hex)) throw new IOException("Duplicate undo outpoint");
                before.put(hex, readValue(in, key));
                after.put(hex, readValue(in, key));
            }
            if (in.available() != 0) throw new IOException("Trailing undo data");
            return new UtxoTransition(before, after);
        } catch (IOException e) { throw new IllegalStateException("Corrupt undo record", e); }
    }

    private static void writeValue(DataOutputStream out, UTXO value) throws IOException {
        byte[] bytes = value == null ? new byte[0] : value.serialize();
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static UTXO readValue(DataInputStream in, byte[] key) throws IOException {
        int length = in.readInt();
        if (length == 0) return null;
        if (length < 0 || length > 11_000 || length > in.available()) throw new IOException("Invalid undo value size");
        UTXO value = UTXO.deserialize(in.readNBytes(length));
        if (value == null || !Arrays.equals(key, value.getKey())) throw new IOException("Undo outpoint mismatch");
        return value;
    }
}
