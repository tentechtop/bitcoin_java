package com.bit.coin.blockchain;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.bit.coin.config.ParamsConfig;
import com.bit.coin.structure.block.Block;
import com.bit.coin.structure.block.BlockHeader;
import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.structure.tx.TransactionValidator;
import com.bit.coin.utils.Sha;
import com.bit.coin.utils.UInt256;
import lombok.Data;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static com.bit.coin.utils.SerializeUtils.hexToBytes;

/**
 * 维护节点的区块头索引和已生效的主链。持久化模式启动时流式校验头表并按需加载索引，收块时增量更新同一索引。
 * {@link ChainStateStore} 提交 UTXO、索引和链尖之后，本管理器才向读者发布新的链尖。
 * <p>持久化模式从 {@link ChainStateStore} 恢复区块头，历史头按需读取并受有界缓存管理。
 */

public class BlockchainManager {

    private static final Logger LOG = LoggerFactory.getLogger(BlockchainManager.class);

    /** 默认的区块头索引内存预算。持久化区块头仍保存在 RocksDB。 */
    public static final long DEFAULT_HEADER_MEMORY_LIMIT_BYTES = 100L * 1024 * 1024;
    /** 达到预算的 75% 后开始压缩已裁决的主链区段。 */
    public static final int DEFAULT_COMPACTION_THRESHOLD_PERCENT = 75;
    /** 一个摘要默认覆盖的连续区块数。 */
    public static final int DEFAULT_SUMMARY_BLOCK_COUNT = 100;
    /** 保留最近的热区，避免活动分叉和重组读取频繁触发磁盘访问。 */
    public static final int DEFAULT_FINALITY_DEPTH = 1_000;
    /** 摘要自身也设置上限，超出后按摘要树继续合并。 */
    private static final int MAX_SUMMARIES_IN_MEMORY = 1_024;
    private static final long ESTIMATED_HEADER_INDEX_BYTES = 700L;
    private static final long DEFAULT_HEADER_CACHE_MEMORY_BYTES = 32L * 1024 * 1024;
    private static final int HEADER_CACHE_ENTRY_WEIGHT = 256;
    private static final int WRAPPER_CACHE_ENTRY_WEIGHT = 384;

    private volatile long headerMemoryLimitBytes = DEFAULT_HEADER_MEMORY_LIMIT_BYTES;
    private volatile int compactionThresholdPercent = DEFAULT_COMPACTION_THRESHOLD_PERCENT;
    private volatile int summaryBlockCount = DEFAULT_SUMMARY_BLOCK_COUNT;
    private volatile int finalityDepth = DEFAULT_FINALITY_DEPTH;
    private volatile long headerCacheMemoryBytes = DEFAULT_HEADER_CACHE_MEMORY_BYTES;
    private volatile Cache<String, BlockHeader> headerCache;
    private volatile Cache<String, BlockHeaderWrapper> wrapperCache;
    private final NavigableMap<Integer, HeaderSegmentSummary> headerSummaries = new TreeMap<>();
    private volatile int lastCompactedHeight = -1;

    // 内存热区区块头，键为区块哈希（十六进制字符串）。完整历史保存在持久化层。
    @Getter
    private Map<String, BlockHeader> blockHeaders = new ConcurrentHashMap<>();

    // 区块头包装器映射（核心节点结构），键为区块哈希
    @Getter
    private Map<String, BlockHeaderWrapper> blockHeaderWrappers = new ConcurrentHashMap<>();

    // 孤块缓存：key=父区块哈希，value=该父区块的孤块哈希集合
    @Getter
    private Map<String, Set<String>> orphanBlocks = new ConcurrentHashMap<>();

    // 创世区块哈希（缓存）
    @Getter
    private final String genesisBlockHash;
    private final ChainStateStore store;
    private final ParamsConfig.Params params;
    private volatile BlockHeaderWrapper mainTip;

    /**
     * 已裁决区块的摘要。区块原文仍在持久化层，摘要只用于完整性校验和范围定位。
     * 普通区段 root 是区块哈希的 Merkle root，合并摘要 root 是子摘要 root 的 Merkle root。
     */
    public record HeaderSegmentSummary(int startHeight, int endHeight, int blockCount,
            String startHash, String endHash, byte[] merkleRoot, boolean aggregate) {

        public HeaderSegmentSummary {
            if (startHeight < 0 || endHeight < startHeight || blockCount <= 0
                    || startHash == null || endHash == null || merkleRoot == null || merkleRoot.length != 32) {
                throw new IllegalArgumentException("Invalid header segment summary");
            }
            merkleRoot = merkleRoot.clone();
        }

        public HeaderSegmentSummary(int startHeight, int endHeight, int blockCount,
                String startHash, String endHash, byte[] merkleRoot) {
            this(startHeight, endHeight, blockCount, startHash, endHash, merkleRoot, false);
        }

        @Override
        public byte[] merkleRoot() {
            return merkleRoot.clone();
        }
    }

    // 有参构造：初始化创世区块哈希
    public BlockchainManager(String genesisBlockHash) {
        this(genesisBlockHash, null, null);
    }

    BlockchainManager(String genesisBlockHash, ChainStateStore store, ParamsConfig.Params params) {
        this.genesisBlockHash = genesisBlockHash;
        this.store = store;
        this.params = params;
        rebuildHeaderCaches();
    }

    private void rebuildHeaderCaches() {
        long effectiveCacheBytes = Math.max(1L, getEffectiveHeaderCacheMemoryBytes());
        long headerWeight = Math.max(1L, effectiveCacheBytes / 2L);
        long wrapperWeight = Math.max(1L, effectiveCacheBytes - headerWeight);
        headerCache = Caffeine.newBuilder()
                .maximumWeight(headerWeight)
                .weigher((String key, BlockHeader value) -> HEADER_CACHE_ENTRY_WEIGHT)
                .build();
        wrapperCache = Caffeine.newBuilder()
                .maximumWeight(wrapperWeight)
                .weigher((String key, BlockHeaderWrapper value) -> WRAPPER_CACHE_ENTRY_WEIGHT)
                .build();
    }

    public long getHeaderMemoryLimitBytes() {
        return headerMemoryLimitBytes;
    }

    public synchronized void setHeaderMemoryLimitBytes(long bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("Header memory limit must be positive");
        }
        headerMemoryLimitBytes = bytes;
        rebuildHeaderCaches();
        compactIfNeededSafely();
    }

    public long getHeaderCacheMemoryBytes() {
        return headerCacheMemoryBytes;
    }

    /** 返回受总区块头预算约束后的实际缓存预算。 */
    public long getEffectiveHeaderCacheMemoryBytes() {
        return Math.min(headerCacheMemoryBytes, headerMemoryLimitBytes);
    }

    /** 设置老区块头和包装器的总缓存预算。缓存采用 Caffeine 的有界淘汰策略。 */
    public synchronized void setHeaderCacheMemoryBytes(long bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("Header cache memory must be positive");
        }
        headerCacheMemoryBytes = bytes;
        rebuildHeaderCaches();
    }

    /** Returns the estimated weighted bytes currently retained by the two caches. */
    public long getHeaderCacheEstimatedSize() {
        return getHeaderCacheEstimatedBytes();
    }

    public long getHeaderCacheEstimatedBytes() {
        // Caffeine performs weighted eviction asynchronously.  Force pending
        // maintenance before exposing the budget metric so operators and
        // readiness checks never observe a transient value above the configured
        // bound after a burst of header inserts.
        Cache<String, BlockHeader> headers = headerCache;
        Cache<String, BlockHeaderWrapper> wrappers = wrapperCache;
        headers.cleanUp();
        wrappers.cleanUp();
        return headers.estimatedSize() * HEADER_CACHE_ENTRY_WEIGHT
                + wrappers.estimatedSize() * WRAPPER_CACHE_ENTRY_WEIGHT;
    }

    public long getHeaderCacheEntryCount() {
        Cache<String, BlockHeader> headers = headerCache;
        Cache<String, BlockHeaderWrapper> wrappers = wrapperCache;
        headers.cleanUp();
        wrappers.cleanUp();
        return headers.estimatedSize() + wrappers.estimatedSize();
    }

    public int getCompactionThresholdPercent() {
        return compactionThresholdPercent;
    }

    public void setCompactionThresholdPercent(int percent) {
        if (percent < 1 || percent > 100) {
            throw new IllegalArgumentException("Compaction threshold must be between 1 and 100");
        }
        compactionThresholdPercent = percent;
        compactIfNeededSafely();
    }

    public int getSummaryBlockCount() {
        return summaryBlockCount;
    }

    public void setSummaryBlockCount(int count) {
        if (count < 2) {
            throw new IllegalArgumentException("Summary block count must be at least 2");
        }
        synchronized (headerSummaries) {
            if (!headerSummaries.isEmpty() && summaryBlockCount != count) {
                throw new IllegalStateException("Cannot change summary block count after summaries exist");
            }
        }
        summaryBlockCount = count;
    }

    public int getFinalityDepth() {
        return finalityDepth;
    }

    public void setFinalityDepth(int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("Finality depth must not be negative");
        }
        finalityDepth = depth;
        compactIfNeededSafely();
    }

    public long getEstimatedHeaderMemoryBytes() {
        return estimateHeaderMemoryBytes();
    }

    public List<HeaderSegmentSummary> getHeaderSummaries() {
        synchronized (headerSummaries) {
            return List.copyOf(headerSummaries.values());
        }
    }

    /** 从持久化高度索引重算一个摘要，验证摘要与历史主链是否一致。 */
    public boolean verifyHeaderSummary(HeaderSegmentSummary summary) {
        if (summary == null || store == null
                || summary.blockCount() != summary.endHeight() - summary.startHeight() + 1) {
            return false;
        }
        List<String> hashes = new ArrayList<>(summary.aggregate() ? summaryBlockCount : summary.blockCount());
        byte[] calculatedRoot = null;
        String firstHash = null;
        String lastHash = null;
        for (int height = summary.startHeight(); height <= summary.endHeight(); height++) {
            byte[] hash = store.storedHashAtHeight(height);
            if (hash == null) {
                return false;
            }
            String hex = bytesToHex(hash);
            if (firstHash == null) {
                firstHash = hex;
            }
            lastHash = hex;
            hashes.add(hex);
            if (summary.aggregate() && hashes.size() == summaryBlockCount) {
                byte[] segmentRoot = merkleRoot(hashes);
                calculatedRoot = calculatedRoot == null ? segmentRoot : merklePair(calculatedRoot, segmentRoot);
                hashes.clear();
            }
        }
        if (summary.aggregate() && !hashes.isEmpty()) {
            byte[] segmentRoot = merkleRoot(hashes);
            calculatedRoot = calculatedRoot == null ? segmentRoot : merklePair(calculatedRoot, segmentRoot);
        } else if (!summary.aggregate()) {
            calculatedRoot = merkleRoot(hashes);
        }
        return summary.startHash().equals(firstHash) && summary.endHash().equals(lastHash)
                && Arrays.equals(summary.merkleRoot(), calculatedRoot);
    }

    /**
     * 为已达到 finalityDepth 的主链区段生成摘要，并在索引估算达到 75% 预算时驱逐冷索引。
     * 侧链和热区仍保留在内存中，防止自动重组失去所需的索引。摘要数量过多时会合并相邻摘要。
     */
    public void compactIfNeeded() {
        if (store == null) {
            return;
        }

        BlockHeaderWrapper tip = mainTip;
        if (tip == null) {
            return;
        }
        int finalHeight = tip.getHeight() - 1 - finalityDepth;
        int nextStart = lastCompactedHeight + 1;
        int compactedEnd = lastCompactedHeight;
        List<HeaderSegmentSummary> created = new ArrayList<>();
        while (nextStart + summaryBlockCount - 1 <= finalHeight) {
            int endHeight = nextStart + summaryBlockCount - 1;
            List<String> hashes = new ArrayList<>(summaryBlockCount);
            boolean completeSegment = true;
            for (int height = nextStart; height <= endHeight; height++) {
                byte[] hash = store.storedHashAtHeight(height);
                if (hash == null) {
                    completeSegment = false;
                    break;
                }
                hashes.add(bytesToHex(hash));
            }
            if (!completeSegment) {
                break;
            }

            HeaderSegmentSummary summary = new HeaderSegmentSummary(nextStart, endHeight, hashes.size(),
                    hashes.getFirst(), hashes.getLast(), merkleRoot(hashes));
            created.add(summary);
            compactedEnd = endHeight;
            nextStart = endHeight + 1;
        }
        if (!created.isEmpty()) {
            store.commitHeaderSummaries(created);
            synchronized (headerSummaries) {
                for (HeaderSegmentSummary summary : created) {
                    headerSummaries.put(summary.startHeight(), summary);
                }
            }
            for (HeaderSegmentSummary summary : created) {
                for (int height = summary.startHeight(); height <= summary.endHeight(); height++) {
                    byte[] hash = store.storedHashAtHeight(height);
                    if (hash != null) {
                        String key = bytesToHex(hash);
                        blockHeaders.remove(key);
                        blockHeaderWrappers.remove(key);
                    }
                }
            }
            lastCompactedHeight = compactedEnd;
            synchronized (headerSummaries) {
                mergeOldestSummariesIfNeeded();
            }
        }
        if (estimateHeaderMemoryBytes() > compactionLimitBytes()) {
            evictColdIndexEntries();
        }
    }

    /**
     * Compaction is an index optimization, not part of the durable block commit.
     * A transient database/disk failure must therefore leave the committed chain
     * successful; the next block or maintenance call retries compaction.
     */
    private void compactIfNeededSafely() {
        try {
            compactIfNeeded();
        } catch (RuntimeException failure) {
            LOG.warn("Header compaction deferred after a non-consensus failure: {}", failure.toString());
        }
    }

    private long compactionLimitBytes() {
        return Math.max(1L, headerMemoryLimitBytes * compactionThresholdPercent / 100L);
    }

    private long estimateHeaderMemoryBytes() {
        long headers = (long) blockHeaders.size() * ESTIMATED_HEADER_INDEX_BYTES;
        long wrappers = (long) blockHeaderWrappers.size() * ESTIMATED_HEADER_INDEX_BYTES;
        long cachedHeaders = headerCache.estimatedSize() * HEADER_CACHE_ENTRY_WEIGHT;
        long cachedWrappers = wrapperCache.estimatedSize() * WRAPPER_CACHE_ENTRY_WEIGHT;
        long orphans = orphanBlocks.values().stream().mapToLong(Set::size).sum() * 96L;
        long summaries;
        synchronized (headerSummaries) {
            summaries = (long) headerSummaries.size() * 256L;
        }
        return headers + wrappers + cachedHeaders + cachedWrappers + orphans + summaries;
    }

    private void mergeOldestSummariesIfNeeded() {
        while (headerSummaries.size() > MAX_SUMMARIES_IN_MEMORY) {
            Map.Entry<Integer, HeaderSegmentSummary> first = headerSummaries.firstEntry();
            if (first == null) {
                return;
            }
            Map.Entry<Integer, HeaderSegmentSummary> second = headerSummaries.higherEntry(first.getKey());
            if (second == null
                    || second.getValue().startHeight() != first.getValue().endHeight() + 1) {
                return;
            }
            HeaderSegmentSummary merged = new HeaderSegmentSummary(first.getValue().startHeight(),
                    second.getValue().endHeight(), first.getValue().blockCount() + second.getValue().blockCount(),
                    first.getValue().startHash(), second.getValue().endHash(),
                    mergedMerkleRoot(first.getValue(), second.getValue()), true);
            if (store != null) {
                store.replaceHeaderSummaries(first.getValue(), second.getValue(), merged);
            }
            headerSummaries.remove(first.getKey());
            headerSummaries.remove(second.getKey());
            headerSummaries.put(merged.startHeight(), merged);
        }
    }

    private byte[] mergedMerkleRoot(HeaderSegmentSummary first, HeaderSegmentSummary second) {
        return merklePair(first.merkleRoot(), second.merkleRoot());
    }

    /** 清理已持久化的冷索引。它们仍可由 wrapperFor/headerFor 从 RocksDB 恢复。 */
    private void evictColdIndexEntries() {
        BlockHeaderWrapper tip = mainTip;
        if (tip == null) {
            return;
        }
        int hotFloor = Math.max(0, tip.getHeight() - 1 - finalityDepth);
        Set<String> hotHashes = new HashSet<>();
        BlockHeaderWrapper cursor = tip;
        while (cursor != null && cursor.getHeight() - 1 >= hotFloor) {
            hotHashes.add(cursor.getHash());
            if (genesisBlockHash.equals(cursor.getHash())) {
                break;
            }
            cursor = wrapperFor(cursor.getParentHash());
        }
        blockHeaderWrappers.keySet().removeIf(hash -> !hotHashes.contains(hash));
        blockHeaders.keySet().removeIf(hash -> !hotHashes.contains(hash));
        headerCache.asMap().keySet().removeIf(hash -> !hotHashes.contains(hash));
        wrapperCache.asMap().keySet().removeIf(hash -> !hotHashes.contains(hash));
    }

    private static byte[] merkleRoot(List<String> hashes) {
        if (hashes.isEmpty()) {
            return new byte[32];
        }
        List<byte[]> level = new ArrayList<>(hashes.size());
        for (String hash : hashes) {
            level.add(hexToBytes(hash));
        }
        while (level.size() > 1) {
            List<byte[]> next = new ArrayList<>((level.size() + 1) / 2);
            for (int i = 0; i < level.size(); i += 2) {
                byte[] left = level.get(i);
                byte[] right = i + 1 < level.size() ? level.get(i + 1) : left;
                next.add(merklePair(left, right));
            }
            level = next;
        }
        return level.getFirst();
    }

    private static byte[] merklePair(byte[] left, byte[] right) {
        byte[] pair = new byte[left.length + right.length];
        System.arraycopy(left, 0, pair, 0, left.length);
        System.arraycopy(right, 0, pair, left.length, right.length);
        return Sha.doubleSha256(pair);
    }

    static BlockchainManager open(ChainStateStore store, ParamsConfig.Params params) {
        Block applied = store.loadAppliedTip(params);
        BlockchainManager manager = store.readBlockIndex(params, applied.getHash());
        manager.activateBestChain(applied);
        manager.compactIfNeededSafely();
        return manager;
    }

    public String getMainChainTip() {
        var tip = mainTip;
        return tip == null ? null : tip.getHash();
    }

    private BlockHeader headerFor(String hash) {
        BlockHeader header = blockHeaders.get(hash);
        if (header != null || store == null) {
            return header;
        }
        header = headerCache.getIfPresent(hash);
        if (header != null) {
            return header;
        }
        header = store.storedHeader(hexToBytes(hash));
        if (header != null) {
            headerCache.put(hash, header);
        }
        return header;
    }

    /** 从热索引或持久化索引按需取得包装器。被摘要淘汰的老区块只在访问时短暂恢复。 */
    private BlockHeaderWrapper wrapperFor(String hash) {
        if (hash == null) {
            return null;
        }
        BlockHeaderWrapper wrapper = blockHeaderWrappers.get(hash);
        if (wrapper != null || store == null) {
            return wrapper;
        }
        wrapper = wrapperCache.getIfPresent(hash);
        if (wrapper != null) {
            return wrapper;
        }
        BlockHeader header = headerFor(hash);
        if (header == null) {
            return null;
        }
        BlockHeaderWrapper loaded = new BlockHeaderWrapper(hash, header);
        loaded.setHeight(store.storedHeight(hexToBytes(hash)) + 1);
        loaded.setChainWork(store.storedChainWork(hexToBytes(hash)));
        loaded.setFullChain(store.storedBodyExists(hexToBytes(hash)));
        wrapperCache.put(hash, loaded);
        return loaded;
    }

    public BigInteger getMainChainWork() {
        var tip = mainTip;
        return tip == null ? BigInteger.ZERO : tip.getChainWork();
    }

    public int getMainChainHeight() {
        var tip = mainTip;
        return tip == null ? -1 : tip.getHeight();
    }

    /** 查询已生效主链指定高度的区块。高度对外从创世块记为 0。 */
    public Block getMainChainBlockByHeight(int height) {
        if (height < 0 || store == null) {
            return null;
        }
        byte[] hash = store.storedHashAtHeight(height);
        if (hash == null) {
            return null;
        }
        return store.storedBlock(hash);
    }

    public BlockHeader getMainChainHeaderByHeight(int height) {
        Block block = getMainChainBlockByHeight(height);
        return block == null ? null : block.getBlockHeader();
    }

    public byte[] getMainChainHashByHeight(int height) {
        Block block = getMainChainBlockByHeight(height);
        return block == null ? null : block.getHash();
    }

    public int getHeightByHash(byte[] hash) {
        if (hash == null || hash.length != 32) {
            return -1;
        }
        BlockHeaderWrapper wrapper = wrapperFor(HexFormat.of().formatHex(hash));
        return wrapper == null || wrapper.getHeight() < 1 ? -1 : wrapper.getHeight() - 1;
    }

    public UInt256 getChainWorkByHash(byte[] hash) {
        if (hash == null || hash.length != 32) {
            return UInt256.ZERO;
        }
        BlockHeaderWrapper wrapper = wrapperFor(HexFormat.of().formatHex(hash));
        return wrapper == null ? UInt256.ZERO : UInt256.fromBigInteger(wrapper.getChainWork());
    }

    public ChainTipSnapshot getMainTipSnapshot() {
        var tip = mainTip;
        return tip == null ? null : new ChainTipSnapshot(tip.getHeight() - 1, hexToBytes(tip.getHash()),
                UInt256.fromBigInteger(tip.getChainWork()));
    }

    Block getMainLatestBlock() {
        var tip = mainTip;
        if (store == null || tip == null || tip.getHash() == null) {
            return null;
        }
        return store.storedBlock(hexToBytes(tip.getHash()));
    }

    Block getHeaderContext(byte[] hash) {
        if (hash == null || hash.length != 32) {
            return null;
        }
        String key = HexFormat.of().formatHex(hash);
        BlockHeaderWrapper indexed = wrapperFor(key);
        if (indexed == null || indexed.getHeight() < 1) {
            return null;
        }
        Block context = new Block();
        context.setHash(hash.clone());
        BlockHeader header = headerFor(key);
        if (header == null) {
            return null;
        }
        context.setBlockHeader(BlockHeader.deserialize(header.serialize()));
        context.setHeight(indexed.getHeight() - 1);
        context.setChainWork(UInt256.fromBigInteger(indexed.getChainWork()));
        return context;
    }

    boolean hasFullBlock(byte[] hash) {
        if (hash == null || hash.length != 32) {
            return false;
        }
        BlockHeaderWrapper node = wrapperFor(HexFormat.of().formatHex(hash));
        return node != null && node.isFullChain();
    }

    boolean acceptHeader(BlockHeader header, int height, UInt256 claimedWork) {
        byte[] hash = header.calculateHash();
        BlockHeaderWrapper node = prepareHeader(hash, header);
        if (node.getHeight() - 1 != height || !node.getChainWork().equals(claimedWork.toBigInteger())) {
            return false;
        }
        if (!blockHeaderWrappers.containsKey(node.getHash()) && headerFor(node.getHash()) == null) {
            store.commitHeader(hash, header, height, claimedWork);
            recordHeader(node, header);
            compactIfNeededSafely();
        }
        return true;
    }

    ChainStateStore.Accepted accept(Block block) {
        ForkChoice choice = store.validateAndCommit(block, this, params);
        recordAccepted(block, choice);
        compactIfNeededSafely();
        List<Block> connected = new ArrayList<>(choice.connectedParents());
        connected.add(block);
        return new ChainStateStore.Accepted(choice.replacesTip(), choice.disconnected(), List.copyOf(connected));
    }

    /** disconnected 从当前链尖向下；connectedParents 从共同祖先之后排到候选块的父块。 */
    record ForkChoice(BlockHeaderWrapper candidate, boolean replacesTip, int commonHeight,
            List<Block> disconnected, List<Block> connectedParents) {}

    /** 沿两条分支回溯至共同祖先，计算撤销和接入顺序；工作量相同保留原链顶。 */
    ForkChoice evaluateCandidate(Block parent, Block block) {
        BlockHeaderWrapper candidate = prepareHeader(block.getHash(), block.getBlockHeader());
        BlockHeaderWrapper indexedParent = wrapperFor(candidate.getParentHash());
        if (indexedParent == null || !indexedParent.isFullChain() || candidate.getHeight() - 1 != block.getHeight()) {
            throw new IllegalStateException("Candidate parent is not on a validated branch");
        }
        List<Block> disconnected = new ArrayList<>();
        List<Block> connected = new ArrayList<>();
        long retainedBlockBytes = 0;
        Block oldCursor = mainTip.getHash().equals(candidate.getParentHash()) ? parent : getMainLatestBlock();
        Block newCursor = parent;
        while (!Arrays.equals(oldCursor.getHash(), newCursor.getHash())) {
            if (lastCompactedHeight >= 0
                    && Math.min(oldCursor.getHeight(), newCursor.getHeight()) - 1 <= lastCompactedHeight) {
                throw new ChainStateLimitException("Candidate reorganization reaches a summarized chain segment");
            }
            Block retained = oldCursor.getHeight() >= newCursor.getHeight() ? oldCursor : newCursor;
            retainedBlockBytes += blockWeight(retained);
            if (disconnected.size() + connected.size() >= 512 || retainedBlockBytes > 64L * 1024 * 1024) {
                throw new ChainStateLimitException("Candidate branch exceeds automatic reorganization budget");
            }
            if (oldCursor.getHeight() >= newCursor.getHeight()) {
                disconnected.add(oldCursor);
                oldCursor = parentOf(oldCursor);
            } else {
                connected.add(newCursor);
                newCursor = parentOf(newCursor);
            }
        }
        Collections.reverse(connected);
        return new ForkChoice(candidate, hasMoreWork(candidate.getChainWork(), mainTip.getChainWork()),
                oldCursor.getHeight(), List.copyOf(disconnected), List.copyOf(connected));
    }

    private static boolean hasMoreWork(BigInteger candidate, BigInteger incumbent) {
        return candidate.compareTo(incumbent) > 0;
    }

    private static BigInteger accumulatedWork(BigInteger parentWork, BlockHeader header) {
        return parentWork.add(header.getWork());
    }

    private static long blockWeight(Block block) {
        long weight = 512;
        for (Transaction tx : block.getTransactions()) {
            weight += TransactionValidator.memoryWeight(tx);
        }
        return weight;
    }

    private Block parentOf(Block block) {
        if (block.getHeight() == 0) {
            throw new IllegalArgumentException("Branches do not share genesis");
        }
        Block parent = store.storedBlock(block.getBlockHeader().getPreviousHash());
        if (parent.getHeight() != block.getHeight() - 1) {
            throw new IllegalStateException("Corrupt ancestor index");
        }
        return parent;
    }

    // ===================== 新增：重组结果返回类 =====================
    @Data
    public static class ChainReorganizationResult {
        private String commonAncestorHash; // 共同祖先区块哈希
        private List<String> newChainPath; // 共同祖先到新顶端的路径（按高度递增）
        private List<String> oldChainPath; // 共同祖先到旧顶端的路径（按高度递增）
    }

    // ===================== 核心内部类：区块头包装器 =====================
    @Data
    public class BlockHeaderWrapper {
        private String hash;          // 区块哈希
        private int height;           // 此管理器中的区块高度（创世区块为1）
        private BigInteger chainWork; // 从创世区块到当前区块的累计工作量
        private String parentHash;    // 父区块哈希
        private volatile boolean fullChain; // 从创世到本块均有已验证的区块体

        public BlockHeaderWrapper(String hash, BlockHeader header) {
            this.hash = hash;
            this.parentHash = HexFormat.of().formatHex(header.getPreviousHash());
            this.height = -1; // 初始化为无效高度
            this.chainWork = BigInteger.ZERO;
        }
    }

    /**
     * 持久化索引的惰性启动路径。RocksDB 已经保存头、身、height 和 chainWork，内存只建立链尖包装器。
     */
    void loadStoredIndex(String bestHash) {
        blockHeaders = new ConcurrentHashMap<>();
        blockHeaderWrappers = new ConcurrentHashMap<>();
        orphanBlocks = new ConcurrentHashMap<>();
        headerCache.invalidateAll();
        wrapperCache.invalidateAll();
        lastCompactedHeight = -1;
        List<HeaderSegmentSummary> storedSummaries = store.loadHeaderSummaries();
        synchronized (headerSummaries) {
            headerSummaries.clear();
            for (HeaderSegmentSummary summary : storedSummaries) {
                headerSummaries.put(summary.startHeight(), summary);
                lastCompactedHeight = Math.max(lastCompactedHeight, summary.endHeight());
            }
        }
        mainTip = wrapperFor(bestHash);
        if (mainTip == null || mainTip.getHeight() < 1 || !mainTip.isFullChain()) {
            throw new IllegalStateException("No complete chain from genesis");
        }
    }

    private void activateBestChain(Block appliedTip) {
        BlockHeaderWrapper best = mainTip;
        String appliedHash = bytesToHex(appliedTip.getHash());
        BlockHeaderWrapper applied = wrapperFor(appliedHash);
        if (applied == null || !applied.isFullChain() || applied.getHeight() - 1 != appliedTip.getHeight()
                || !applied.getChainWork().equals(appliedTip.getChainWork().toBigInteger())) {
            throw new IllegalStateException("Applied chain tip disagrees with the stored block headers");
        }
        if (!applied.getHash().equals(best.getHash())) {
            mainTip = applied;
            Block winner = store.storedBlock(hexToBytes(best.getHash()));
            if (!accept(winner).mainChanged()) {
                throw new IllegalStateException("Could not activate the highest-work complete branch");
            }
        }
    }

    private BlockHeaderWrapper prepareHeader(byte[] hash, BlockHeader header) {
        String key = HexFormat.of().formatHex(hash);
        BlockHeaderWrapper known = wrapperFor(key);
        if (known != null) {
            return known;
        }
        BlockHeaderWrapper node = new BlockHeaderWrapper(key, header);
        BlockHeaderWrapper parent = wrapperFor(node.getParentHash());
        if (parent == null || parent.getHeight() < 1) {
            throw new IllegalStateException("Header parent missing from block index");
        }
        node.setHeight(Math.addExact(parent.getHeight(), 1));
        node.setChainWork(accumulatedWork(parent.getChainWork(), header));
        return node;
    }

    private void recordHeader(BlockHeaderWrapper node, BlockHeader header) {
        if (!blockHeaderWrappers.containsKey(node.getHash())) {
            BlockHeader storedHeader = BlockHeader.deserialize(header.serialize());
            if (store == null) {
                blockHeaders.put(node.getHash(), storedHeader);
                blockHeaderWrappers.put(node.getHash(), node);
            } else {
                headerCache.put(node.getHash(), storedHeader);
                wrapperCache.put(node.getHash(), node);
            }
            Set<String> waiting = orphanBlocks.remove(node.getHash());
            if (waiting != null) {
                connectWaitingDescendants(node, waiting);
            }
        }
    }

    private void connectWaitingDescendants(BlockHeaderWrapper parent, Set<String> waiting) {
        record Pending(BlockHeaderWrapper parent, String hash) {}
        Deque<Pending> pending = new ArrayDeque<>();
        for (String hash : waiting) {
            pending.addLast(new Pending(parent, hash));
        }
        while (!pending.isEmpty()) {
            Pending next = pending.removeFirst();
            BlockHeaderWrapper currentParent = next.parent();
            String hash = next.hash();
            BlockHeader childHeader = headerFor(hash);
            if (childHeader == null) {
                continue;
            }
            BlockHeaderWrapper child = wrapperFor(hash);
            if (child == null) {
                child = new BlockHeaderWrapper(hash, childHeader);
            }
            child.setHeight(currentParent.getHeight() + 1);
            child.setChainWork(accumulatedWork(currentParent.getChainWork(), childHeader));
            child.setFullChain(currentParent.isFullChain() && store != null
                    && store.storedBodyExists(hexToBytes(hash)));
            if (store == null) {
                blockHeaderWrappers.put(hash, child);
            } else {
                wrapperCache.put(hash, child);
            }
            Set<String> descendants = orphanBlocks.remove(hash);
            if (descendants != null) {
                for (String descendant : descendants) {
                    pending.addLast(new Pending(child, descendant));
                }
            }
        }
    }

    private void recordAccepted(Block block, ForkChoice choice) {
        BlockHeaderWrapper node = choice.candidate();
        recordHeader(node, block.getBlockHeader());
        node.setFullChain(true);
        if (choice.replacesTip()) {
            mainTip = node;
        }
    }

    // ===================== 扩展方法：获取主链完整哈希列表 =====================
    public List<String> getMainChainHashes() {
        List<String> mainChain = new ArrayList<>();
        String currentHash = getMainChainTip();

        // 回溯直到创世区块
        while (currentHash != null) {
            BlockHeaderWrapper wrapper = wrapperFor(currentHash);
            if (wrapper == null) {
                break;
            }
            mainChain.add(currentHash);
            // 创世区块终止回溯
            if (genesisBlockHash.equals(currentHash)) {
                break;
            }
            currentHash = wrapper.getParentHash();
        }

        // 反转列表（从创世区块到主链末端）
        Collections.reverse(mainChain);
        return mainChain;
    }

    // ===================== 新增方法1：判断区块是否是分叉区块 =====================
    public boolean isForkBlock(String blockHash) {
        // 1. 校验区块是否存在
        BlockHeaderWrapper wrapper = wrapperFor(blockHash);
        if (wrapper == null) {
            throw new IllegalArgumentException("区块不存在：" + blockHash);
        }

        if (store != null && wrapper.getHeight() > 0) {
            int height = wrapper.getHeight() - 1;
            byte[] mainHash = store.storedHashAtHeight(height);
            if (mainHash != null && blockHash.equals(bytesToHex(mainHash))) {
                return false;
            }
            String parentHash = wrapper.getParentHash();
            byte[] mainParent = height <= 0 ? null : store.storedHashAtHeight(height - 1);
            return mainParent != null && parentHash.equals(bytesToHex(mainParent));
        }

        // 2. 获取当前主链哈希列表
        List<String> mainChainHashes = getMainChainHashes();

        // 3. 如果区块在主链中 → 不是分叉
        if (mainChainHashes.contains(blockHash)) {
            return false;
        }

        // 4. 检查父区块是否在主链中 → 是则为分叉，否则不是
        String parentHash = wrapper.getParentHash();
        return mainChainHashes.contains(parentHash);
    }

    // ===================== 新增方法2：判断区块是否是孤儿区块 =====================
    public boolean isOrphanBlock(String blockHash) {
        // 1. 校验区块是否存在
        BlockHeaderWrapper wrapper = wrapperFor(blockHash);
        if (wrapper == null) {
            throw new IllegalArgumentException("区块不存在：" + blockHash);
        }

        return wrapper.getHeight() < 0;
    }

    // ===================== 新增方法3：判断是否需要重组并执行重组 =====================
    public ChainReorganizationResult checkAndPerformReorganization(String newBlockHash) {
        // 1. 基础校验
        BlockHeaderWrapper newBlockWrapper = wrapperFor(newBlockHash);
        if (newBlockWrapper == null) {
            throw new IllegalArgumentException("区块不存在：" + newBlockHash);
        }
        if (isOrphanBlock(newBlockHash)) {
            throw new IllegalArgumentException("孤儿区块无法触发重组：" + newBlockHash);
        }

        // 2. 比较新分支和当前主链的工作量
        BigInteger newChainWork = newBlockWrapper.getChainWork();
        if (!newBlockWrapper.isFullChain() || !hasMoreWork(newChainWork, mainTip.getChainWork())) {
            return null; // 工作量不足，不需要重组
        }

        // 3. 找到新分支与旧主链的共同祖先
        String commonAncestor = findCommonAncestor(newBlockHash, getMainChainTip());

        // 4. 构建新链路径（共同祖先 → 新区块）
        List<String> newChainPath = buildChainPath(commonAncestor, newBlockHash);

        // 5. 构建旧链路径（共同祖先 → 旧主链末端）
        List<String> oldChainPath = buildChainPath(commonAncestor, getMainChainTip());

        // 6. 执行主链重组：更新主链核心字段
        if (store == null) {
            mainTip = newBlockWrapper;
        } else {
            accept(store.storedBlock(hexToBytes(newBlockHash)));
        }

        // 7. 封装并返回重组结果
        ChainReorganizationResult result = new ChainReorganizationResult();
        result.setCommonAncestorHash(commonAncestor);
        result.setNewChainPath(newChainPath);
        result.setOldChainPath(oldChainPath);
        return result;
    }

    // ===================== 重组辅助方法：查找共同祖先 =====================
    private String findCommonAncestor(String blockHash1, String blockHash2) {
        // 构建区块1的祖先链（从自身到创世区块）
        Set<String> ancestors1 = new HashSet<>();
        String current = blockHash1;
        while (current != null) {
            ancestors1.add(current);
            if (genesisBlockHash.equals(current)) {
                break;
            }
            BlockHeaderWrapper wrapper = wrapperFor(current);
            if (wrapper == null) {
                break;
            }
            current = wrapper.getParentHash();
        }

        // 遍历区块2的祖先链，找到第一个在区块1祖先链中的区块
        current = blockHash2;
        while (current != null) {
            if (ancestors1.contains(current)) {
                return current;
            }
            if (genesisBlockHash.equals(current)) {
                break;
            }
            BlockHeaderWrapper wrapper = wrapperFor(current);
            if (wrapper == null) {
                break;
            }
            current = wrapper.getParentHash();
        }

        // 兜底返回创世区块
        return genesisBlockHash;
    }

    // ===================== 重组辅助方法：构建链路径 =====================
    private List<String> buildChainPath(String startHash, String targetHash) {
        List<String> path = new ArrayList<>();
        String current = targetHash;

        // 从目标区块回溯到起始区块
        while (current != null && !current.equals(startHash)) {
            path.add(current);
            BlockHeaderWrapper wrapper = wrapperFor(current);
            if (wrapper == null) {
                break;
            }
            current = wrapper.getParentHash();
        }

        // 添加起始区块并反转（得到从start到target的顺序）
        path.add(startHash);
        Collections.reverse(path);
        return path;
    }
}
