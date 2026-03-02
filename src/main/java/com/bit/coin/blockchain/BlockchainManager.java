package com.bit.coin.blockchain;

import com.bit.coin.structure.block.BlockHeader;
import lombok.Data;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;

/**
 *         blockchainManager = new BlockchainManager(bytesToHex(hash));
 *         Map<String, BlockHeader> blockHeaders = new ConcurrentHashMap<>();
 *         //查找全部的区块头给到链管理器
 *         dataBase.iterate(TBLOCK_HEADER, (key, value) -> {
 *             blockHeaders.put(bytesToHex(key),BlockHeader.deserialize(value));
 *             return true;
 *         });
 *         blockchainManager.setBlockHeaders(blockHeaders);
 *         //构建区块链
 *         // 4. 获取主链信息
 *         System.out.println("主链末端哈希：" + blockchainManager.getMainChainTip());
 *         System.out.println("主链高度：" + blockchainManager.getMainChainHeight());
 *         System.out.println("主链累计工作量：" + blockchainManager.getMainChainWork());
 *         System.out.println("主链完整哈希列表：" + blockchainManager.getMainChainHashes());
 */

@Data
public class BlockchainManager {

    // 全部区块头，键为区块哈希（十六进制字符串）
    private Map<String, BlockHeader> blockHeaders = new ConcurrentHashMap<>();

    // 区块头包装器映射（核心节点结构），键为区块哈希
    private Map<String, BlockHeaderWrapper> blockHeaderWrappers = new ConcurrentHashMap<>();

    // 孤块缓存：key=父区块哈希，value=该父区块的孤块哈希集合
    private Map<String, Set<String>> orphanBlocks = new ConcurrentHashMap<>();

    // 创世区块哈希（缓存）
    private String genesisBlockHash;

    // 当前主链末端区块哈希
    private String mainChainTip;

    // 主链累计工作量
    private BigInteger mainChainWork = BigInteger.ZERO;

    // 主链高度
    private int mainChainHeight = -1;

    // 私有化无参构造
    private BlockchainManager() {}

    // 有参构造：初始化创世区块哈希
    public BlockchainManager(String genesisBlockHash) {
        this.genesisBlockHash = genesisBlockHash;
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
        private int height;           // 区块在链中的高度（创世区块为0）
        private BigInteger chainWork; // 从创世区块到当前区块的累计工作量
        private String parentHash;    // 父区块哈希
        private Set<String> children; // 子区块哈希集合

        public BlockHeaderWrapper(String hash, BlockHeader header) {
            this.hash = hash;
            this.parentHash = bytesToHex(header.getPreviousHash()); // 假设BlockHeader有getPreviousHash()方法
            this.children = ConcurrentHashMap.newKeySet(); // 线程安全的Set
            this.height = -1; // 初始化为无效高度
            this.chainWork = BigInteger.ZERO;
        }
    }

    // ===================== 核心方法：初始化区块链结构 =====================
    public void initBlockchainStructure() {
        if (blockHeaders.isEmpty()) {
            throw new IllegalStateException("区块头集合为空，请先设置blockHeaders");
        }
        if (genesisBlockHash == null || !blockHeaders.containsKey(genesisBlockHash)) {
            throw new IllegalArgumentException("创世区块哈希无效或不存在");
        }

        // 步骤1：构建所有BlockHeaderWrapper
        buildBlockHeaderWrappers();

        // 步骤2：建立父-子区块关系，筛选孤块
        buildParentChildRelations();

        // 步骤3：处理孤块（尝试将孤块挂载到已存在的父区块）
        processOrphanBlocks();

        // 步骤4：计算所有区块的高度和累计工作量（从创世区块开始）
        calculateChainHeightAndWork(genesisBlockHash, 0, BigInteger.ZERO);

        // 步骤5：选择累计工作量最大的主链（修复后的逻辑）
        selectMainChain();
    }

    // ===================== 辅助方法：构建BlockHeaderWrapper =====================
    private void buildBlockHeaderWrappers() {
        blockHeaders.forEach((hash, header) -> {
            blockHeaderWrappers.put(hash, new BlockHeaderWrapper(hash, header));
        });
    }

    // ===================== 辅助方法：建立父-子关系 & 筛选孤块 =====================
    private void buildParentChildRelations() {
        blockHeaderWrappers.forEach((blockHash, wrapper) -> {
            String parentHash = wrapper.getParentHash();

            // 创世区块无父区块，跳过
            if (genesisBlockHash.equals(blockHash)) {
                return;
            }

            // 父区块存在：建立父子关系
            if (blockHeaderWrappers.containsKey(parentHash)) {
                BlockHeaderWrapper parentWrapper = blockHeaderWrappers.get(parentHash);
                parentWrapper.getChildren().add(blockHash);
            } else {
                // 父区块不存在：加入孤块缓存
                orphanBlocks.computeIfAbsent(parentHash, k -> ConcurrentHashMap.newKeySet()).add(blockHash);
            }
        });
    }

    // ===================== 辅助方法：处理孤块 =====================
    private void processOrphanBlocks() {
        Iterator<Map.Entry<String, Set<String>>> iterator = orphanBlocks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Set<String>> entry = iterator.next();
            String parentHash = entry.getKey();
            Set<String> orphanBlockHashes = entry.getValue();

            // 父区块已存在：挂载孤块，移除孤块缓存
            if (blockHeaderWrappers.containsKey(parentHash)) {
                BlockHeaderWrapper parentWrapper = blockHeaderWrappers.get(parentHash);
                orphanBlockHashes.forEach(orphanHash -> {
                    parentWrapper.getChildren().add(orphanHash);
                    // 递归处理该孤块的子孤块（如果有）
                    if (orphanBlocks.containsKey(orphanHash)) {
                        processOrphanBlocks(orphanHash);
                    }
                });
                iterator.remove(); // 移除已处理的孤块缓存
            }
        }
    }

    private void processOrphanBlocks(String parentHash) {
        Set<String> orphanBlockHashes = orphanBlocks.get(parentHash);
        if (orphanBlockHashes == null || orphanBlockHashes.isEmpty()) {
            return;
        }
        BlockHeaderWrapper parentWrapper = blockHeaderWrappers.get(parentHash);
        orphanBlockHashes.forEach(orphanHash -> {
            parentWrapper.getChildren().add(orphanHash);
            processOrphanBlocks(orphanHash);
        });
        orphanBlocks.remove(parentHash);
    }

    // ===================== 辅助方法：计算区块高度 & 累计工作量 =====================
    private void calculateChainHeightAndWork(String blockHash, int parentHeight, BigInteger parentChainWork) {
        BlockHeaderWrapper wrapper = blockHeaderWrappers.get(blockHash);
        if (wrapper == null) {
            return;
        }

        // 避免重复计算（已计算过高度的区块跳过）
        if (wrapper.getHeight() != -1) {
            return;
        }

        // 1. 设置当前区块高度（父高度+1）
        wrapper.setHeight(parentHeight + 1);

        // 2. 计算当前区块的工作量（基于难度目标，比特币逻辑：工作量 = 2^256 / (难度目标+1)）
        BlockHeader blockHeader = blockHeaders.get(blockHash);
        BigInteger blockWork = blockHeader.getWork();

        // 3. 设置当前区块累计工作量（父累计工作量 + 当前区块工作量）
        wrapper.setChainWork(parentChainWork.add(blockWork));

        // 4. 递归计算所有子区块
        for (String childHash : wrapper.getChildren()) {
            calculateChainHeightAndWork(childHash, wrapper.getHeight(), wrapper.getChainWork());
        }
    }

    // ===================== 新增辅助方法：查找所有顶端区块（无孩子的区块） =====================
    /**
     * 找出所有“顶端区块”：没有子区块的区块（各分支的末端）
     * @return 顶端区块哈希集合
     */
    private Set<String> findAllTipBlocks() {
        Set<String> tipBlocks = new HashSet<>();
        blockHeaderWrappers.forEach((hash, wrapper) -> {
            // 无子区块 → 是顶端区块
            if (wrapper.getChildren() == null || wrapper.getChildren().isEmpty()) {
                tipBlocks.add(hash);
            }
        });
        return tipBlocks;
    }

    // ===================== 核心方法：选择主链（修复后） =====================
    /**
     * 修复后的主链选择逻辑：
     * 1. 先找到所有无孩子的顶端区块（各分支末端）
     * 2. 只比较这些顶端区块的累计工作量（代表各分支总工作量）
     * 3. 选择工作量最大的顶端区块作为主链末端
     */
    private void selectMainChain() {
        // 步骤1：获取所有顶端区块（无孩子的区块）
        Set<String> tipBlocks = findAllTipBlocks();
        if (tipBlocks.isEmpty()) {
            // 极端情况：只有创世区块（也会被判定为顶端区块），这里兜底
            tipBlocks.add(genesisBlockHash);
        }

        BigInteger maxWork = BigInteger.ZERO;
        String maxWorkBlockHash = genesisBlockHash;
        int maxHeight = 0;

        // 步骤2：只遍历顶端区块，比较工作量（核心优化）
        for (String tipHash : tipBlocks) {
            BlockHeaderWrapper wrapper = blockHeaderWrappers.get(tipHash);
            if (wrapper == null) {
                continue;
            }
            BigInteger currentWork = wrapper.getChainWork();
            int currentHeight = wrapper.getHeight();

            // 优先按工作量排序，工作量相同则按高度排序
            if (currentWork.compareTo(maxWork) > 0 ||
                    (currentWork.equals(maxWork) && currentHeight > maxHeight)) {
                maxWork = currentWork;
                maxWorkBlockHash = wrapper.getHash();
                maxHeight = currentHeight;
            }
        }

        // 更新主链核心字段
        this.mainChainWork = maxWork;
        this.mainChainTip = maxWorkBlockHash;
        this.mainChainHeight = maxHeight;
    }

    // ===================== 扩展方法：获取主链完整哈希列表 =====================
    public List<String> getMainChainHashes() {
        List<String> mainChain = new ArrayList<>();
        String currentHash = mainChainTip;

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
        this.blockHeaders = blockHeaders;
        // 自动初始化区块链结构
        if (genesisBlockHash != null) {
            initBlockchainStructure();
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

        // 2. 创世区块不是孤儿
        if (genesisBlockHash.equals(blockHash)) {
            return false;
        }

        // 3. 获取父区块哈希并检查是否存在
        String parentHash = wrapper.getParentHash();
        boolean parentNotExists = !blockHeaderWrappers.containsKey(parentHash);

        // 4. 双重验证：孤块缓存中是否包含该区块
        boolean inOrphanCache = orphanBlocks.values().stream()
                .anyMatch(orphanHashes -> orphanHashes.contains(blockHash));

        // 父区块不存在 或 存在于孤块缓存 → 判定为孤儿
        return parentNotExists || inOrphanCache;
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
        if (newChainWork.compareTo(mainChainWork) <= 0) {
            return null; // 工作量不足，不需要重组
        }

        // 3. 找到新分支与旧主链的共同祖先
        String commonAncestor = findCommonAncestor(newBlockHash, mainChainTip);

        // 4. 构建新链路径（共同祖先 → 新区块）
        List<String> newChainPath = buildChainPath(commonAncestor, newBlockHash);

        // 5. 构建旧链路径（共同祖先 → 旧主链末端）
        List<String> oldChainPath = buildChainPath(commonAncestor, mainChainTip);

        // 6. 执行主链重组：更新主链核心字段
        this.mainChainTip = newBlockHash;
        this.mainChainWork = newChainWork;
        this.mainChainHeight = newBlockWrapper.getHeight();

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