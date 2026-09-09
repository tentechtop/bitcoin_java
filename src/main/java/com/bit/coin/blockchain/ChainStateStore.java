package com.bit.coin.blockchain;

import com.bit.coin.config.ParamsConfig;
import com.bit.coin.database.DataBase;
import com.bit.coin.database.rocksDb.RocksDb.DbOperation;
import com.bit.coin.structure.block.Block;
import com.bit.coin.structure.block.BlockHeader;
import com.bit.coin.structure.tx.*;
import com.bit.coin.utils.SerializeUtils;
import com.bit.coin.utils.UInt256;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;

import static com.bit.coin.blockchain.BlockChainServiceImpl.*;
import static com.bit.coin.database.rocksDb.TableEnum.*;
import static com.bit.coin.database.rocksDb.RocksDb.DbOperation.OpType.*;

/** Prepares all mutations before one durable commit. Callers serialize chain-state writers. */
public final class ChainStateStore {
    public static final byte[] REVISION_KEY = "CONSENSUS_REVISION".getBytes(StandardCharsets.UTF_8);
    private final DataBase database;
    private final Function<byte[], Block> blocks;
    private final Clock clock;

    public ChainStateStore(DataBase database, Function<byte[], Block> blocks) {
        this(database, blocks, Clock.systemUTC());
    }

    public ChainStateStore(DataBase database, Function<byte[], Block> blocks, Clock clock) {
        this.database = database;
        this.blocks = blocks;
        this.clock = clock;
    }

    public record Accepted(boolean mainChanged, List<Block> disconnected, List<Block> connected) {}

    public void initialize(Block genesis, ParamsConfig.Params params) {
        List<DbOperation> operations = new OperationBuffer();
        UtxoTransition delta = UtxoTransition.validate(genesis, key -> null, params, 0);
        appendBlock(operations, genesis, delta);
        delta.appendOperations(operations, true);
        appendIndexes(operations, genesis, delta, true);
        appendTip(operations, genesis);
        operations.add(new DbOperation(CHAIN, REVISION_KEY, integer(ConsensusRules.REVISION), INSERT));
        commit(operations);
    }

    public Accepted accept(Block block, Block tip, ParamsConfig.Params params) {
        Block parent = blocks.apply(block.getBlockHeader().getPreviousHash());
        if (parent == null) throw new IllegalArgumentException("Missing parent");
        block.setHeight(Math.addExact(parent.getHeight(), 1));
        validateHeader(block, parent, params);
        if (block.getTransactions() == null || block.getTransactions().isEmpty()
                || block.getTransactions().size() > ConsensusRules.MAX_BLOCK_TRANSACTIONS
                || block.getTxCount() != block.getTransactions().size()
                || block.serialize().length > ConsensusRules.MAX_BLOCK_BYTES) throw new IllegalArgumentException("Invalid block size");
        if (!Arrays.equals(block.getBlockHeader().getMerkleRoot(), calculateMerkleRoot(block.getTransactions()))) {
            throw new IllegalArgumentException("Invalid Merkle root");
        }
        // Height commitment makes coinbase identities distinct across subsidy periods.
        Transaction coinbase = block.getTransactions().getFirst();
        if (!coinbase.isCoinbase() || coinbase.getInputs().getFirst().getScriptSig() == null
                || coinbase.getInputs().getFirst().getScriptSig().length < 4
                || SerializeUtils.littleEndianToInt(Arrays.copyOf(coinbase.getInputs().getFirst().getScriptSig(), 4)) != block.getHeight()) {
            throw new IllegalArgumentException("Coinbase must commit to candidate height");
        }
        BigInteger work = parent.getChainWork().toBigInteger().add(block.getBlockHeader().getWork());
        if (work.bitLength() > 256) throw new IllegalArgumentException("Chain work overflow");
        block.setChainWork(UInt256.fromBigInteger(work));

        List<Block> disconnected = new ArrayList<>();
        List<Block> connected = new ArrayList<>();
        long retainedBlockBytes = 0;
        Block oldCursor = tip;
        Block newCursor = parent;
        while (!Arrays.equals(oldCursor.getHash(), newCursor.getHash())) {
            Block retained = oldCursor.getHeight() >= newCursor.getHeight() ? oldCursor : newCursor;
            retainedBlockBytes += blockWeight(retained);
            if (disconnected.size() + connected.size() >= 512 || retainedBlockBytes > 64L * 1024 * 1024)
                throw new IllegalStateException("Reorganization exceeds automatic resource budget; resynchronize into a new directory");
            if (oldCursor.getHeight() >= newCursor.getHeight()) {
                disconnected.add(oldCursor);
                oldCursor = parentOf(oldCursor);
            } else {
                connected.add(newCursor);
                newCursor = parentOf(newCursor);
            }
        }
        Collections.reverse(connected);
        Map<String, UTXO> overlay = new HashMap<>();
        long overlayBytes = 0;
        for (Block old : disconnected) { UtxoTransition d = delta(old); overlayBytes += transitionWeight(d); requireOverlayBudget(overlayBytes); d.overlay(overlay, false); }
        for (Block next : connected) { UtxoTransition d = delta(next); overlayBytes += transitionWeight(d); requireOverlayBudget(overlayBytes); d.overlay(overlay, true); }
        Map<Integer, Block> branch = new HashMap<>();
        for (Block next : connected) branch.put(next.getHeight(), next);
        int commonHeight = oldCursor.getHeight();
        Map<Integer, Long> sourceTimes = new HashMap<>();
        UtxoTransition candidate = UtxoTransition.validate(block, key -> {
            String hex = SerializeUtils.bytesToHex(key);
            if (overlay.containsKey(hex)) return overlay.get(hex);
            byte[] bytes = database.get(TUTXO, key);
            return bytes == null ? null : UTXO.deserialize(bytes);
        }, params, ConsensusRules.medianTime(parent, this::header), sourceHeight -> sourceTimes.computeIfAbsent(sourceHeight, height -> {
            int targetHeight = Math.min(parent.getHeight(), Math.max(0, height - 1));
            Block ancestor = targetHeight > commonHeight ? branch.get(targetHeight)
                    : header(database.get(HEIGHT_TO_HASH, targetHeight));
            if (ancestor == null) throw new IllegalStateException("Missing relative-lock ancestry");
            return ConsensusRules.medianTime(ancestor, this::header);
        }));

        List<DbOperation> operations = new OperationBuffer();
        appendBlock(operations, block, candidate);
        boolean mainChanged = block.getChainWork().compareTo(tip.getChainWork()) > 0;
        if (mainChanged) {
            for (Block old : disconnected) {
                UtxoTransition undo = delta(old);
                undo.appendOperations(operations, false);
                appendIndexes(operations, old, undo, false);
            }
            for (Block next : connected) {
                UtxoTransition redo = delta(next);
                redo.appendOperations(operations, true);
                appendIndexes(operations, next, redo, true);
            }
            candidate.appendOperations(operations, true);
            appendIndexes(operations, block, candidate, true);
            appendTip(operations, block);
        }
        commit(operations);
        connected.add(block);
        return new Accepted(mainChanged, List.copyOf(disconnected), List.copyOf(connected));
    }

    private void validateHeader(Block block, Block parent, ParamsConfig.Params params) {
        BlockHeader header = block.getBlockHeader();
        BigInteger target = ConsensusRules.target(header.getBits());
        if (target.compareTo(ConsensusRules.target((int) params.getPowLimitBits())) > 0
                || header.getBits() != ConsensusRules.expectedBits(parent, header.getTime(), params, this::header)
                || !Arrays.equals(block.getHash(), header.calculateHash())
                || !header.verifyProofOfWork(false)
                || header.getTime() <= ConsensusRules.medianTime(parent, this::header)
                || header.getTime() > clock.instant().getEpochSecond() + 7200
                || header.getTime() > 0xFFFFFFFFL) throw new IllegalArgumentException("Invalid contextual block header");
    }

    private Block header(byte[] hash) {
        if (hash == null) return null;
        byte[] encoded = database.get(TBLOCK_HEADER, hash);
        if (encoded == null) return null;
        Block block = new Block(); block.setHash(hash); block.setBlockHeader(BlockHeader.deserialize(encoded));
        byte[] height = database.get(HASH_TO_HEIGHT, hash);
        if (height == null || height.length != 4) throw new IllegalStateException("Missing ancestor height");
        block.setHeight(ByteBuffer.wrap(height).getInt());
        return block;
    }
    private static long blockWeight(Block block) {
        long weight = 512;
        for (Transaction tx : block.getTransactions()) weight += TransactionValidator.memoryWeight(tx);
        return weight;
    }
    private static long transitionWeight(UtxoTransition delta) {
        long bytes = 0;
        for (UTXO value : delta.before().values()) bytes += 256L + (value == null ? 0 : value.getScript().length);
        for (UTXO value : delta.after().values()) bytes += 256L + (value == null ? 0 : value.getScript().length);
        return bytes;
    }
    private static void requireOverlayBudget(long bytes) {
        if (bytes > 64L * 1024 * 1024) throw new IllegalStateException("Reorganization state exceeds automatic memory budget; resynchronize");
    }
    private static final class OperationBuffer extends ArrayList<DbOperation> {
        private long bytes;
        @Override public boolean add(DbOperation operation) {
            bytes += 192L + operation.key.length + (operation.value == null ? 0 : operation.value.length);
            if (bytes > 256L * 1024 * 1024 || size() >= 1_000_000)
                throw new IllegalStateException("Atomic write exceeds automatic memory budget; manual recovery required");
            return super.add(operation);
        }
    }

    private Block parentOf(Block block) {
        if (block.getHeight() == 0) throw new IllegalArgumentException("Branches do not share genesis");
        Block parent = blocks.apply(block.getBlockHeader().getPreviousHash());
        if (parent == null || parent.getHeight() != block.getHeight() - 1) throw new IllegalStateException("Corrupt ancestor index");
        return parent;
    }

    private UtxoTransition delta(Block block) {
        return UtxoTransition.deserialize(database.get(BLOCK_UNDO, block.getHash()));
    }

    private static void appendBlock(List<DbOperation> operations, Block block, UtxoTransition delta) {
        operations.add(new DbOperation(TBLOCK_HEADER, block.getHash(), block.getBlockHeader().serialize(), INSERT));
        operations.add(new DbOperation(TBLOCK_BODY, block.getHash(), block.getBlockBody().serialize(), INSERT));
        operations.add(new DbOperation(HASH_TO_HEIGHT, block.getHash(), integer(block.getHeight()), INSERT));
        operations.add(new DbOperation(HASH_TO_CHAIN_WORK, block.getHash(), block.getChainWork().toBytes(), INSERT));
        operations.add(new DbOperation(BLOCK_UNDO, block.getHash(), delta.serialize(), INSERT));
    }

    private static void appendTip(List<DbOperation> operations, Block block) {
        operations.add(new DbOperation(CHAIN, MAIN_CHAIN_TIP_HEIGHT_KEY, integer(block.getHeight()), INSERT));
        operations.add(new DbOperation(CHAIN, MAIN_CHAIN_TIP_HASH_KEY, block.getHash(), INSERT));
    }

    private static void appendIndexes(List<DbOperation> operations, Block block, UtxoTransition delta, boolean forward) {
        DbOperation.OpType type = forward ? INSERT : DELETE;
        operations.add(new DbOperation(HEIGHT_TO_HASH, integer(block.getHeight()), forward ? block.getHash() : null, type));
        Map<String, UTXO> sources = new HashMap<>(delta.before());
        for (UTXO output : block.getUTXOs()) sources.put(SerializeUtils.bytesToHex(output.getKey()), output);
        for (int i = 0; i < block.getTransactions().size(); i++) {
            Transaction tx = block.getTransactions().get(i);
            operations.add(new DbOperation(TX_TO_BLOCK, tx.getTxId(), forward ? tx.getBlockIndex(block.getHash(), i) : null, type));
            Set<String> scripts = new HashSet<>();
            for (TxOutput output : tx.getOutputs()) scripts.add(SerializeUtils.bytesToHex(output.getScriptPubKey()));
            if (!tx.isCoinbase()) for (TxInput input : tx.getInputs()) {
                UTXO source = sources.get(SerializeUtils.bytesToHex(UTXO.getKey(input.getTxId(), input.getIndex())));
                if (source == null) throw new IllegalStateException("Undo record is missing an input");
                scripts.add(SerializeUtils.bytesToHex(source.getScript()));
            }
            for (String script : scripts) {
                byte[] prefix = SerializeUtils.hexToBytes(script);
                // Public-key address queries index compact locks only. Arbitrary script bytes
                // must not create keys under somebody else's chosen 20-byte address prefix.
                if (prefix.length != 20) continue;
                byte[] key = Arrays.copyOf(prefix, prefix.length + 32);
                System.arraycopy(tx.getTxId(), 0, key, prefix.length, 32);
                operations.add(new DbOperation(ADDR_TO_TX, key, forward ? new byte[0] : null, type));
            }
        }
    }

    private void commit(List<DbOperation> operations) {
        if (!database.dataTransaction(operations)) throw new IllegalStateException("Durable chain-state commit failed");
    }

    private static byte[] integer(int value) { return ByteBuffer.allocate(4).putInt(value).array(); }
}
