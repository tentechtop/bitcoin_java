package com.bit.coin.blockchain;

import com.bit.coin.config.ParamsConfig;
import com.bit.coin.database.DataBase;
import com.bit.coin.database.rocksDb.PageResult;
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

/**
 * 先准备区块、索引和 UTXO 的全部变更，再一次性提交到持久化存储。
 * 调用方负责串行执行链状态写入操作，以免不同候选区块同时基于同一链顶准备状态。
 */
public final class ChainStateStore {

    public static final byte[] REVISION_KEY = "CONSENSUS_REVISION".getBytes(StandardCharsets.UTF_8);
    private final DataBase database;
    private final Function<byte[], Block> blocks;
    private final Clock clock;
    private BlockchainManager manager;

    public ChainStateStore(DataBase database, Function<byte[], Block> blocks) {
        this(database, blocks, Clock.systemUTC());
    }

    public ChainStateStore(DataBase database, Function<byte[], Block> blocks, Clock clock) {
        this.database = database;
        this.blocks = blocks;
        this.clock = clock;
    }

    /** disconnected 按旧顶端向共同祖先排列；connected 按共同祖先之后的正向顺序排列。 */
    public record Accepted(boolean mainChanged, List<Block> disconnected, List<Block> connected) {}

    /** 读取与当前 UTXO 对应的已提交位置，作为启动选链时撤销或重放的起点。 */
    Block loadAppliedTip(ParamsConfig.Params params) {
        Block genesis = createGenesisBlock(params);
        byte[] existing = database.get(HEIGHT_TO_HASH, 0);
        Block applied = genesis;
        if (existing == null) {
            if (database.get(CHAIN, REVISION_KEY) != null || database.get(CHAIN, MAIN_CHAIN_TIP_HASH_KEY) != null) {
                throw new IllegalStateException("Missing genesis height index; reindex before starting");
            }
            initialize(genesis, params);
        } else {
            if (!Arrays.equals(database.get(CHAIN, REVISION_KEY), integer(ConsensusRules.REVISION))) {
                throw new IllegalStateException("Incompatible consensus revision; see docs/OPERATIONS.md");
            }
            if (!Arrays.equals(existing, genesis.getHash())) {
                throw new IllegalStateException("Database genesis mismatch");
            }
            byte[] hash = database.get(CHAIN, MAIN_CHAIN_TIP_HASH_KEY);
            applied = hash == null ? null : storedBlock(hash);
            if (applied == null || !Arrays.equals(applied.getHash(), hash)
                    || !Arrays.equals(database.get(CHAIN, MAIN_CHAIN_TIP_HEIGHT_KEY), integer(applied.getHeight()))
                    || !Arrays.equals(database.get(HEIGHT_TO_HASH, applied.getHeight()), hash)) {
                throw new IllegalStateException("Inconsistent chain tip; restore or reindex before starting");
            }
            delta(applied);
        }
        return applied;
    }

    BlockchainManager readBlockIndex(ParamsConfig.Params params, byte[] preferredTip) {
        // 只流式校验持久化的区块头，不把整张头表复制到 Java 堆。
        database.iterate(TBLOCK_HEADER, (key, value) -> {
            BlockHeader header = BlockHeader.deserialize(value);
            if (!Arrays.equals(key, header.calculateHash())
                    || new BigInteger(1, key).compareTo(header.getTargetFromBits()) > 0) {
                throw new IllegalStateException("Invalid stored block header");
            }
            return true;
        });
        String bestHash = bestStoredCompleteHash(preferredTip, params.getGenesisHash());
        var index = new BlockchainManager(HexFormat.of().formatHex(params.getGenesisHash()), this, params);
        index.loadStoredIndex(bestHash);
        return index;
    }

    private String bestStoredCompleteHash(byte[] preferredTip, byte[] genesisHash) {
        String preferred = preferredTip == null ? null : HexFormat.of().formatHex(preferredTip);
        String best = null;
        BigInteger bestWork = BigInteger.ZERO;
        int bestHeight = -1;
        Map<String, BigInteger> computedWork = new HashMap<>();
        byte[] cursor = null;
        PageResult<byte[]> page;
        do {
            page = database.pageKey(TBLOCK_BODY, 1000, cursor);
            for (byte[] key : page.getData()) {
                String candidate = HexFormat.of().formatHex(key);
                BigInteger work = storedOrComputedWork(key, computedWork);
                int candidateHeight = storedHeight(key);
                boolean preferredCandidate = candidate.equals(preferred);
                boolean preferredBest = candidate.equals(best) || best != null && best.equals(preferred);
                boolean betterTie = preferredCandidate && !preferredBest
                        || preferredCandidate == preferredBest
                                && (candidateHeight > bestHeight
                                        || candidateHeight == bestHeight && (best == null || candidate.compareTo(best) > 0));
                if (best == null || work.compareTo(bestWork) > 0 || work.equals(bestWork) && betterTie) {
                    best = candidate;
                    bestWork = work;
                    bestHeight = candidateHeight;
                }
            }
            cursor = page.getLastKey();
        } while (!page.isLastPage());
        if (best == null) {
            throw new IllegalStateException("No complete block stored");
        }
        if (!hasCompleteBodyChain(SerializeUtils.hexToBytes(best), genesisHash)) {
            throw new IllegalStateException("Stored complete block does not connect to genesis");
        }
        return best;
    }

    private boolean hasCompleteBodyChain(byte[] tip, byte[] genesisHash) {
        byte[] cursor = tip;
        for (int depth = 0; depth <= 10_000_000; depth++) {
            if (!storedBodyExists(cursor)) {
                return false;
            }
            if (Arrays.equals(cursor, genesisHash)) {
                return true;
            }
            BlockHeader header = storedHeader(cursor);
            if (header == null) {
                return false;
            }
            cursor = header.getPreviousHash();
        }
        throw new IllegalStateException("Excessive stored ancestry");
    }

    private BigInteger storedOrComputedWork(byte[] hash, Map<String, BigInteger> computedWork) {
        Deque<byte[]> path = new ArrayDeque<>();
        byte[] cursor = hash;
        BigInteger work = null;
        while (work == null) {
            String key = HexFormat.of().formatHex(cursor);
            BigInteger cached = computedWork.get(key);
            if (cached != null) {
                work = cached;
                break;
            }
            byte[] workBytes = database.get(HASH_TO_CHAIN_WORK, cursor);
            if (workBytes != null) {
                work = UInt256.fromBytes(workBytes).toBigInteger();
                break;
            }
            byte[] encodedHeader = database.get(TBLOCK_HEADER, cursor);
            if (encodedHeader == null) {
                work = BigInteger.ZERO;
                break;
            }
            BlockHeader header = BlockHeader.deserialize(encodedHeader);
            path.push(cursor);
            byte[] parent = header.getPreviousHash();
            if (isZeroHash(parent)) {
                work = BigInteger.ZERO;
                break;
            }
            cursor = parent;
        }
        while (!path.isEmpty()) {
            byte[] current = path.pop();
            BlockHeader header = BlockHeader.deserialize(database.get(TBLOCK_HEADER, current));
            work = work.add(header.getWork());
            if (computedWork.size() < 8_192) {
                computedWork.put(HexFormat.of().formatHex(current), work);
            }
        }
        return work;
    }

    private static boolean isZeroHash(byte[] hash) {
        if (hash == null || hash.length != 32) {
            return false;
        }
        for (byte value : hash) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    /** 离线重建也从完整分支中选链，源库的链尖只用于同工作量时保留原分支。 */
    public byte[] bestStoredHash(ParamsConfig.Params params) {
        byte[] appliedHash = database.get(CHAIN, MAIN_CHAIN_TIP_HASH_KEY);
        var index = readBlockIndex(params, appliedHash);
        if (appliedHash != null && !index.hasFullBlock(appliedHash)) {
            throw new IllegalStateException("Missing original block on the previously applied chain");
        }
        return index.getMainTipSnapshot().hash();
    }

    Block storedBlock(byte[] hash) {
        Block block = blocks.apply(hash);
        if (block == null) {
            throw new IllegalStateException("Missing stored block");
        }
        return block;
    }

    BlockHeader storedHeader(byte[] hash) {
        byte[] encoded = database.get(TBLOCK_HEADER, hash);
        return encoded == null ? null : BlockHeader.deserialize(encoded);
    }

    byte[] storedHashAtHeight(int height) {
        return height < 0 ? null : database.get(HEIGHT_TO_HASH, height);
    }

    int storedHeight(byte[] hash) {
        byte[] encoded = database.get(HASH_TO_HEIGHT, hash);
        return encoded == null || encoded.length != Integer.BYTES ? -1 : ByteBuffer.wrap(encoded).getInt();
    }

    BigInteger storedChainWork(byte[] hash) {
        byte[] encoded = database.get(HASH_TO_CHAIN_WORK, hash);
        return encoded == null ? BigInteger.ZERO : UInt256.fromBytes(encoded).toBigInteger();
    }

    boolean storedBodyExists(byte[] hash) {
        return database.get(TBLOCK_BODY, hash) != null;
    }

    List<BlockchainManager.HeaderSegmentSummary> loadHeaderSummaries() {
        List<BlockchainManager.HeaderSegmentSummary> result = new ArrayList<>();
        database.iterate(CHECKPOINT, (key, value) -> {
            if (key == null || key.length != 12 || value == null || (value.length != 108 && value.length != 109)
                    || ByteBuffer.wrap(key).getInt() != 0x48445253) {
                return true;
            }
            ByteBuffer encoded = ByteBuffer.wrap(value);
            int start = encoded.getInt();
            int end = encoded.getInt();
            int count = encoded.getInt();
            byte[] startHash = new byte[32];
            byte[] endHash = new byte[32];
            byte[] root = new byte[32];
            encoded.get(startHash).get(endHash).get(root);
            result.add(new BlockchainManager.HeaderSegmentSummary(start, end, count,
                    SerializeUtils.bytesToHex(startHash), SerializeUtils.bytesToHex(endHash), root,
                    value.length == 109 && encoded.get() != 0));
            return true;
        });
        return result;
    }

    /** 保存区块段摘要。原始区块头仍保留在 TBLOCK_HEADER。 */
    void commitHeaderSummary(BlockchainManager.HeaderSegmentSummary summary) {
        commitHeaderSummaries(List.of(summary));
    }

    void commitHeaderSummaries(Collection<BlockchainManager.HeaderSegmentSummary> summaries) {
        List<DbOperation> operations = new ArrayList<>(summaries.size());
        for (BlockchainManager.HeaderSegmentSummary summary : summaries) {
            operations.add(summaryInsert(summary));
        }
        if (!operations.isEmpty() && !database.dataTransaction(operations)) {
            throw new IllegalStateException("Could not persist header segment summary");
        }
    }

    void replaceHeaderSummaries(BlockchainManager.HeaderSegmentSummary first,
            BlockchainManager.HeaderSegmentSummary second, BlockchainManager.HeaderSegmentSummary merged) {
        List<DbOperation> operations = List.of(
                new DbOperation(CHECKPOINT, summaryKey(first), null, DELETE),
                new DbOperation(CHECKPOINT, summaryKey(second), null, DELETE),
                summaryInsert(merged));
        if (!database.dataTransaction(operations)) {
            throw new IllegalStateException("Could not merge header segment summaries");
        }
    }

    private static DbOperation summaryInsert(BlockchainManager.HeaderSegmentSummary summary) {
        ByteBuffer value = ByteBuffer.allocate(12 + 32 + 32 + 32 + 1)
                .putInt(summary.startHeight())
                .putInt(summary.endHeight())
                .putInt(summary.blockCount())
                .put(hex(summary.startHash()))
                .put(hex(summary.endHash()))
                .put(summary.merkleRoot())
                .put((byte) (summary.aggregate() ? 1 : 0));
        return new DbOperation(CHECKPOINT, summaryKey(summary), value.array(), INSERT);
    }

    private static byte[] summaryKey(BlockchainManager.HeaderSegmentSummary summary) {
        return ByteBuffer.allocate(12)
                .putInt(0x48445253) // HDRS
                .putInt(summary.startHeight())
                .putInt(summary.endHeight())
                .array();
    }

    private static byte[] hex(String hash) {
        return SerializeUtils.hexToBytes(hash);
    }

    void commitHeader(byte[] hash, BlockHeader header, int height, UInt256 work) {
        commit(List.of(new DbOperation(TBLOCK_HEADER, hash, header.serialize(), INSERT),
                new DbOperation(HASH_TO_HEIGHT, hash, integer(height), INSERT),
                new DbOperation(HASH_TO_CHAIN_WORK, hash, work.toBytes(), INSERT)));
    }

    /** 在同一批写入中建立创世块、UTXO、主链索引和共识版本。 */
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

    /**
     * 校验候选区块并预先计算分叉两侧的 UTXO 变化；只有累计工作量更高时才把重组、索引和链顶
     * 合并成一次数据库提交，低工作量分支仅保存区块及其撤销记录。
     */
    public Accepted accept(Block block, Block tip, ParamsConfig.Params params) {
        if (manager == null) {
            manager = BlockchainManager.open(this, params);
        }
        if (!manager.getMainChainTip().equals(SerializeUtils.bytesToHex(tip.getHash()))) {
            throw new IllegalStateException("Supplied tip differs from the selected chain tip");
        }
        return manager.accept(block);
    }

    BlockchainManager.ForkChoice validateAndCommit(Block block, BlockchainManager index, ParamsConfig.Params params) {
        Block parent = blocks.apply(block.getBlockHeader().getPreviousHash());
        if (parent == null) {
            throw new IllegalArgumentException("Missing parent");
        }
        block.setHeight(Math.addExact(parent.getHeight(), 1));
        validateHeader(block, parent, params, index);
        if (block.getTransactions() == null || block.getTransactions().isEmpty()
                || block.getTransactions().size() > ConsensusRules.MAX_BLOCK_TRANSACTIONS
                || block.getTxCount() != block.getTransactions().size()
                || block.serialize().length > ConsensusRules.MAX_BLOCK_BYTES) {
            throw new IllegalArgumentException("Invalid block size");
        }
        if (!Arrays.equals(block.getBlockHeader().getMerkleRoot(), calculateMerkleRoot(block.getTransactions()))) {
            throw new IllegalArgumentException("Invalid Merkle root");
        }
        // 校验 coinbase 输入的前四字节是否以小端序写入本块高度。
        Transaction coinbase = block.getTransactions().getFirst();
        if (!coinbase.isCoinbase() || coinbase.getInputs().getFirst().getScriptSig() == null
                || coinbase.getInputs().getFirst().getScriptSig().length < 4
                || SerializeUtils.littleEndianToInt(
                        Arrays.copyOf(coinbase.getInputs().getFirst().getScriptSig(), 4)) != block.getHeight()) {
            throw new IllegalArgumentException("Coinbase must commit to candidate height");
        }
        var choice = index.evaluateCandidate(parent, block);
        block.setChainWork(UInt256.fromBigInteger(choice.candidate().getChainWork()));
        List<Block> disconnected = choice.disconnected();
        List<Block> connected = choice.connectedParents();
        // disconnected 已从旧顶端向祖先排列，依此撤销；connected 已反转为祖先向新顶端，依此重放。
        // 候选区块验证读取叠加视图，不必在判定工作量前改写持久化 UTXO。
        Map<String, UTXO> overlay = new HashMap<>();
        long overlayBytes = 0;
        for (Block old : disconnected) {
            UtxoTransition d = delta(old);
            overlayBytes += transitionWeight(d);
            requireOverlayBudget(overlayBytes);
            d.overlay(overlay, false);
        }
        for (Block next : connected) {
            UtxoTransition d = delta(next);
            overlayBytes += transitionWeight(d);
            requireOverlayBudget(overlayBytes);
            d.overlay(overlay, true);
        }
        Map<Integer, Block> branch = new HashMap<>();
        for (Block next : connected) {
            branch.put(next.getHeight(), next);
        }
        int commonHeight = choice.commonHeight();
        Map<Integer, Long> sourceTimes = new HashMap<>();
        UtxoTransition candidate = UtxoTransition.validate(block, key -> {
            String hex = SerializeUtils.bytesToHex(key);
            if (overlay.containsKey(hex)) {
                return overlay.get(hex);
            }
            byte[] bytes = database.get(TUTXO, key);
            return bytes == null ? null : UTXO.deserialize(bytes);
        }, params, ConsensusRules.medianTime(parent, index::getHeaderContext),
                sourceHeight -> sourceTimes.computeIfAbsent(sourceHeight, height -> {
                    int targetHeight = Math.min(parent.getHeight(), Math.max(0, height - 1));
                    Block ancestor = targetHeight > commonHeight ? branch.get(targetHeight)
                            : index.getHeaderContext(database.get(HEIGHT_TO_HASH, targetHeight));
                    if (ancestor == null) {
                        throw new IllegalStateException("Missing relative-lock ancestry");
                    }
                    return ConsensusRules.medianTime(ancestor, index::getHeaderContext);
                }));

        List<DbOperation> operations = new OperationBuffer();
        appendBlock(operations, block, candidate);
        // 侧链区块也需持久化，但只有工作量胜出才生成会改变当前主链状态的操作。
        if (choice.replacesTip()) {
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
        return choice;
    }

    private void validateHeader(Block block, Block parent, ParamsConfig.Params params, BlockchainManager index) {
        BlockHeader header = block.getBlockHeader();
        BigInteger target = ConsensusRules.target(header.getBits());
        if (target.compareTo(ConsensusRules.target((int) params.getPowLimitBits())) > 0
                || header.getBits() != ConsensusRules.expectedBits(parent, header.getTime(), params, index::getHeaderContext)
                || !Arrays.equals(block.getHash(), header.calculateHash())
                || !header.verifyProofOfWork(false)
                || header.getTime() <= ConsensusRules.medianTime(parent, index::getHeaderContext)
                || header.getTime() > clock.instant().getEpochSecond() + 7200
                || header.getTime() > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("Invalid contextual block header");
        }
    }


    /** 按前后快照的条目和脚本字节估算重组所需内存。 */
    private static long transitionWeight(UtxoTransition delta) {
        long bytes = 0;
        for (UTXO value : delta.before().values()) {
            bytes += 256L + (value == null ? 0 : value.getScript().length);
        }
        for (UTXO value : delta.after().values()) {
            bytes += 256L + (value == null ? 0 : value.getScript().length);
        }
        return bytes;
    }

    private static void requireOverlayBudget(long bytes) {
        if (bytes > 64L * 1024 * 1024) {
            throw new ChainStateLimitException("Candidate branch exceeds state overlay memory budget");
        }
    }

    private static final class OperationBuffer extends ArrayList<DbOperation> {

        private long bytes;

        @Override
        public boolean add(DbOperation operation) {
            bytes += 192L + operation.key.length + (operation.value == null ? 0 : operation.value.length);
            if (bytes > 256L * 1024 * 1024 || size() >= 1_000_000) {
                throw new ChainStateLimitException("Candidate exceeds atomic write preparation budget");
            }
            return super.add(operation);
        }
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

    /** 随区块接入或断开，增删主链高度、交易位置和地址交易索引。 */
    private static void appendIndexes(List<DbOperation> operations, Block block, UtxoTransition delta,
            boolean forward) {
        DbOperation.OpType type = forward ? INSERT : DELETE;
        operations.add(
                new DbOperation(HEIGHT_TO_HASH, integer(block.getHeight()), forward ? block.getHash() : null, type));
        Map<String, UTXO> sources = new HashMap<>(delta.before());
        for (UTXO output : block.getUTXOs()) {
            sources.put(SerializeUtils.bytesToHex(output.getKey()), output);
        }
        for (int i = 0; i < block.getTransactions().size(); i++) {
            Transaction tx = block.getTransactions().get(i);
            operations.add(new DbOperation(TX_TO_BLOCK, tx.getTxId(),
                    forward ? tx.getBlockIndex(block.getHash(), i) : null, type));
            Set<String> scripts = new HashSet<>();
            for (TxOutput output : tx.getOutputs()) {
                scripts.add(SerializeUtils.bytesToHex(output.getScriptPubKey()));
            }
            if (!tx.isCoinbase()) {
                for (TxInput input : tx.getInputs()) {
                    UTXO source = sources
                            .get(SerializeUtils.bytesToHex(UTXO.getKey(input.getTxId(), input.getIndex())));
                    if (source == null) {
                        throw new IllegalStateException("Undo record is missing an input");
                    }
                    scripts.add(SerializeUtils.bytesToHex(source.getScript()));
                }
            }
            for (String script : scripts) {
                byte[] prefix = SerializeUtils.hexToBytes(script);
                // 公钥地址查询只为紧凑格式的锁定脚本建立索引，防止任意脚本字节
                // 在他人选定的 20 字节地址前缀下生成索引键。
                if (prefix.length != 20) {
                    continue;
                }
                byte[] key = Arrays.copyOf(prefix, prefix.length + 32);
                System.arraycopy(tx.getTxId(), 0, key, prefix.length, 32);
                operations.add(new DbOperation(ADDR_TO_TX, key, forward ? new byte[0] : null, type));
            }
        }
    }

    private void commit(List<DbOperation> operations) {
        if (!database.dataTransaction(operations)) {
            throw new IllegalStateException("Durable chain-state commit failed");
        }
    }

    private static byte[] integer(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }
}
