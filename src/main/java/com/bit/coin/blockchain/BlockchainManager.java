package com.bit.coin.blockchain;

import com.bit.coin.config.ParamsConfig;
import com.bit.coin.structure.block.Block;
import com.bit.coin.structure.block.BlockHeader;
import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.structure.tx.TransactionValidator;
import com.bit.coin.utils.UInt256;
import lombok.Data;
import lombok.Getter;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static com.bit.coin.utils.SerializeUtils.hexToBytes;

/**
 * 维护节点的区块头索引和已生效的主链。启动时从所有完整分支中选链，收块时增量更新同一索引。
 * {@link ChainStateStore} 提交 UTXO、索引和链尖之后，本管理器才向读者发布新的链尖。
 * <p>{@link #setBlockHeaders(Map)} 也支持从区块头集合重建主链；该区块头视图将创世区块记为高度 1。
 */

public class BlockchainManager {

    // 全部区块头，键为区块哈希（十六进制字符串）
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

    // 有参构造：初始化创世区块哈希
    public BlockchainManager(String genesisBlockHash) {
        this(genesisBlockHash, null, null);
    }

    BlockchainManager(String genesisBlockHash, ChainStateStore store, ParamsConfig.Params params) {
        this.genesisBlockHash = genesisBlockHash;
        this.store = store;
        this.params = params;
    }

    static BlockchainManager open(ChainStateStore store, ParamsConfig.Params params) {
        Block applied = store.loadAppliedTip(params);
        BlockchainManager manager = store.readBlockIndex(params, applied.getHash());
        manager.activateBestChain(applied);
        return manager;
    }

    public String getMainChainTip() {
        var tip = mainTip;
        return tip == null ? null : tip.getHash();
    }

    public BigInteger getMainChainWork() {
        var tip = mainTip;
        return tip == null ? BigInteger.ZERO : tip.getChainWork();
    }

    public int getMainChainHeight() {
        var tip = mainTip;
        return tip == null ? -1 : tip.getHeight();
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
        BlockHeaderWrapper indexed = blockHeaderWrappers.get(key);
        if (indexed == null || indexed.getHeight() < 1) {
            return null;
        }
        Block context = new Block();
        context.setHash(hash.clone());
        context.setBlockHeader(BlockHeader.deserialize(blockHeaders.get(key).serialize()));
        context.setHeight(indexed.getHeight() - 1);
        context.setChainWork(UInt256.fromBigInteger(indexed.getChainWork()));
        return context;
    }

    boolean hasFullBlock(byte[] hash) {
        if (hash == null || hash.length != 32) {
            return false;
        }
        BlockHeaderWrapper node = blockHeaderWrappers.get(HexFormat.of().formatHex(hash));
        return node != null && node.isFullChain();
    }

    boolean acceptHeader(BlockHeader header, int height, UInt256 claimedWork) {
        byte[] hash = header.calculateHash();
        BlockHeaderWrapper node = prepareHeader(hash, header);
        if (node.getHeight() - 1 != height || !node.getChainWork().equals(claimedWork.toBigInteger())) {
            return false;
        }
        if (!blockHeaderWrappers.containsKey(node.getHash())) {
            store.commitHeader(hash, header, height, claimedWork);
            recordHeader(node, header);
        }
        return true;
    }

    ChainStateStore.Accepted accept(Block block) {
        ForkChoice choice = store.validateAndCommit(block, this, params);
        recordAccepted(block, choice);
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
        BlockHeaderWrapper indexedParent = blockHeaderWrappers.get(candidate.getParentHash());
        if (indexedParent == null || !indexedParent.isFullChain() || candidate.getHeight() - 1 != block.getHeight()) {
            throw new IllegalStateException("Candidate parent is not on a validated branch");
        }
        List<Block> disconnected = new ArrayList<>();
        List<Block> connected = new ArrayList<>();
        long retainedBlockBytes = 0;
        Block oldCursor = mainTip.getHash().equals(candidate.getParentHash()) ? parent : getMainLatestBlock();
        Block newCursor = parent;
        while (!Arrays.equals(oldCursor.getHash(), newCursor.getHash())) {
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
        private Set<String> children; // 子区块哈希集合
        private volatile boolean fullChain; // 从创世到本块均有已验证的区块体

        public BlockHeaderWrapper(String hash, BlockHeader header) {
            this.hash = hash;
            this.parentHash = HexFormat.of().formatHex(header.getPreviousHash());
            this.children = ConcurrentHashMap.newKeySet(); // 线程安全的Set
            this.height = -1; // 初始化为无效高度
            this.chainWork = BigInteger.ZERO;
        }
    }

    public void initBlockchainStructure() {
        setBlockHeaders(blockHeaders);
    }

    /** 启动阶段的索引在主链及 UTXO 恢复完成后才交给服务的读者。 */
    void loadStoredHeaders(Map<String, BlockHeader> headers, Set<String> fullBlocks, String preferredTip) {
        mainTip = indexHeaders(headers, fullBlocks::contains, preferredTip);
    }

    private void activateBestChain(Block appliedTip) {
        BlockHeaderWrapper best = mainTip;
        String appliedHash = bytesToHex(appliedTip.getHash());
        BlockHeaderWrapper applied = blockHeaderWrappers.get(appliedHash);
        if (applied == null || !applied.isFullChain() || applied.getHeight() - 1 != appliedTip.getHeight()
                || !applied.getChainWork().equals(appliedTip.getChainWork().toBigInteger())) {
            throw new IllegalStateException("Applied chain tip disagrees with the stored block headers");
        }
        if (applied != best) {
            mainTip = applied;
            Block winner = store.storedBlock(hexToBytes(best.getHash()));
            if (!accept(winner).mainChanged()) {
                throw new IllegalStateException("Could not activate the highest-work complete branch");
            }
        }
    }

    private BlockHeaderWrapper indexHeaders(Map<String, BlockHeader> headers, Predicate<String> hasBody,
            String preferredTip) {
        if (headers.isEmpty()) {
            throw new IllegalStateException("区块头集合为空，请先设置blockHeaders");
        }
        if (genesisBlockHash == null || !headers.containsKey(genesisBlockHash)) {
            throw new IllegalArgumentException("创世区块哈希无效或不存在");
        }

        Map<String, BlockHeaderWrapper> wrappers = new ConcurrentHashMap<>();
        Map<String, Set<String>> orphans = new ConcurrentHashMap<>();
        headers.forEach((hash, header) -> wrappers.put(hash, new BlockHeaderWrapper(hash, header)));
        for (BlockHeaderWrapper block : wrappers.values()) {
            if (genesisBlockHash.equals(block.getHash())) {
                continue;
            }
            BlockHeaderWrapper parent = wrappers.get(block.getParentHash());
            if (parent == null) {
                orphans.computeIfAbsent(block.getParentHash(), key -> ConcurrentHashMap.newKeySet()).add(block.getHash());
            } else {
                parent.getChildren().add(block.getHash());
            }
        }

        BlockHeaderWrapper genesis = wrappers.get(genesisBlockHash);
        genesis.setHeight(1);
        genesis.setChainWork(headers.get(genesisBlockHash).getWork());
        genesis.setFullChain(hasBody.test(genesisBlockHash));
        connectDescendants(genesis, wrappers, headers, hasBody);

        // 同工作量时保留仍在候选中的当前链尖；首次加载时用高度和哈希稳定裁决平局。
        Comparator<BlockHeaderWrapper> byWork = Comparator.comparing(BlockHeaderWrapper::getChainWork)
                .thenComparing(block -> block.getHash().equals(preferredTip))
                .thenComparingInt(BlockHeaderWrapper::getHeight)
                .thenComparing(BlockHeaderWrapper::getHash);
        // 完整分支的末端也可能已有只含区块头的子块。
        BlockHeaderWrapper best = wrappers.values().stream().filter(BlockHeaderWrapper::isFullChain)
                .max(byWork).orElseThrow(() -> new IllegalStateException("No complete chain from genesis"));

        blockHeaders = headers;
        blockHeaderWrappers = wrappers;
        orphanBlocks = orphans;
        return best;
    }

    private void connectDescendants(BlockHeaderWrapper root, Map<String, BlockHeaderWrapper> wrappers,
            Map<String, BlockHeader> headers, Predicate<String> hasBody) {
        Deque<BlockHeaderWrapper> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            BlockHeaderWrapper parent = pending.pop();
            for (String hash : parent.getChildren()) {
                BlockHeaderWrapper child = wrappers.get(hash);
                child.setHeight(parent.getHeight() + 1);
                child.setChainWork(accumulatedWork(parent.getChainWork(), headers.get(hash)));
                child.setFullChain(parent.isFullChain() && hasBody.test(hash));
                pending.push(child);
            }
        }
    }

    private BlockHeaderWrapper prepareHeader(byte[] hash, BlockHeader header) {
        String key = HexFormat.of().formatHex(hash);
        BlockHeaderWrapper known = blockHeaderWrappers.get(key);
        if (known != null) {
            return known;
        }
        BlockHeaderWrapper node = new BlockHeaderWrapper(key, header);
        BlockHeaderWrapper parent = blockHeaderWrappers.get(node.getParentHash());
        if (parent == null || parent.getHeight() < 1) {
            throw new IllegalStateException("Header parent missing from block index");
        }
        node.setHeight(Math.addExact(parent.getHeight(), 1));
        node.setChainWork(accumulatedWork(parent.getChainWork(), header));
        return node;
    }

    private void recordHeader(BlockHeaderWrapper node, BlockHeader header) {
        if (!blockHeaderWrappers.containsKey(node.getHash())) {
            blockHeaders.put(node.getHash(), BlockHeader.deserialize(header.serialize()));
            blockHeaderWrappers.get(node.getParentHash()).getChildren().add(node.getHash());
            blockHeaderWrappers.put(node.getHash(), node);
            Set<String> waiting = orphanBlocks.remove(node.getHash());
            if (waiting != null) {
                node.getChildren().addAll(waiting);
                connectDescendants(node, blockHeaderWrappers, blockHeaders, hash -> false);
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
            BlockHeaderWrapper wrapper = blockHeaderWrappers.get(currentHash);
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

    // ===================== 覆写setter：设置blockHeaders后自动初始化 =====================
    public void setBlockHeaders(Map<String, BlockHeader> blockHeaders) {
        if (store != null) {
            throw new IllegalStateException("A live chain index must be restored from committed blocks");
        }
        if (genesisBlockHash == null) {
            this.blockHeaders = blockHeaders;
        } else {
            mainTip = indexHeaders(blockHeaders, hash -> true, getMainChainTip());
        }
    }

    // ===================== 新增方法1：判断区块是否是分叉区块 =====================
    public boolean isForkBlock(String blockHash) {
        // 1. 校验区块是否存在
        BlockHeaderWrapper wrapper = blockHeaderWrappers.get(blockHash);
        if (wrapper == null) {
            throw new IllegalArgumentException("区块不存在：" + blockHash);
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
        BlockHeaderWrapper wrapper = blockHeaderWrappers.get(blockHash);
        if (wrapper == null) {
            throw new IllegalArgumentException("区块不存在：" + blockHash);
        }

        return wrapper.getHeight() < 0;
    }

    // ===================== 新增方法3：判断是否需要重组并执行重组 =====================
    public ChainReorganizationResult checkAndPerformReorganization(String newBlockHash) {
        // 1. 基础校验
        BlockHeaderWrapper newBlockWrapper = blockHeaderWrappers.get(newBlockHash);
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
            BlockHeaderWrapper wrapper = blockHeaderWrappers.get(current);
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
            BlockHeaderWrapper wrapper = blockHeaderWrappers.get(current);
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
            BlockHeaderWrapper wrapper = blockHeaderWrappers.get(current);
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
