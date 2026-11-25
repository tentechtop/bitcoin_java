package com.bit.coin.blockchain;

import com.bit.coin.cache.UTXOCache;
import com.bit.coin.config.ParamsConfig;
import com.bit.coin.config.SystemConfig;
import com.bit.coin.database.DataBase;
import com.bit.coin.database.rocksDb.PageResult;
import com.bit.coin.database.rocksDb.RocksDb;
import com.bit.coin.database.rocksDb.TableEnum;
import com.bit.coin.estimate.FeeEstimate;

import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.structure.block.Block;
import com.bit.coin.structure.block.BlockBody;
import com.bit.coin.structure.block.BlockHeader;
import com.bit.coin.structure.script.Address;
import com.bit.coin.structure.tx.*;
import com.bit.coin.txpool.TxPool;
import com.bit.coin.utils.Sha;
import com.bit.coin.utils.TimeGenerator;
import com.bit.coin.utils.UInt256;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.primitives.Bytes;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.base.Base58;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.bit.coin.database.rocksDb.TableEnum.*;
import static com.bit.coin.structure.block.BlockHeader.convertTargetToBits;
import static com.bit.coin.structure.tx.UTXOStatusResolver.CONFIRMED_UNSPENT;
import static com.bit.coin.structure.tx.UTXOStatusResolver.setLifecycleStatus;
import static com.bit.coin.utils.SerializeUtils.*;

/**
 * 协调区块头和区块体的接收、链状态存储、内存缓存及交易池。
 * {@link BlockchainManager} 负责选链和发布链尖；本服务在提交成功后协调缓存、交易池和广播。
 */
@Slf4j
@Service
public class BlockChainServiceImpl implements BlockChainService {

    @Autowired
    private SystemConfig config;

    @Autowired
    private DataBase dataBase;

    @Autowired
    private TxPool txPool;

    @Autowired
    private RoutingTable routingTable;

    private volatile BlockchainManager blockchainManager;

    //主链顶端高度 key
    public static final byte[] MAIN_CHAIN_TIP_HEIGHT_KEY = "MAIN_CHAIN_TIP_HEIGHT_KEY".getBytes(StandardCharsets.UTF_8);
    //主链顶端Hash
    public static final byte[] MAIN_CHAIN_TIP_HASH_KEY = "MAIN_CHAIN_TIP_HASH_KEY".getBytes(StandardCharsets.UTF_8);

    //咖啡因交易缓存 缓存20000笔交易 查询时先查询缓存 缓存中没有再查询交易
    private final Cache<String, Transaction> TransactionCache = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .maximumWeight(32L * 1024 * 1024)
            .weigher((String key, Transaction value) -> TransactionValidator.memoryWeight(value))
            .recordStats()
            .build();

    private volatile boolean stateHealthy;
    private final AtomicLong acceptedBlocks = new AtomicLong(), rejectedBlocks = new AtomicLong(),
            reorganizations = new AtomicLong();

    public long acceptedBlocks() {
        return acceptedBlocks.get();
    }

    public long rejectedBlocks() {
        return rejectedBlocks.get();
    }

    public long reorganizations() {
        return reorganizations.get();
    }

    public long medianBeforeHeight(int sourceHeight) {
        BlockchainManager manager = blockchainManager;
        Block ancestor = manager == null ? null
                : manager.getMainChainBlockByHeight(Math.max(0, sourceHeight - 1));
        if (ancestor == null) {
            throw new IllegalStateException("Missing relative-lock ancestry");
        }
        return ConsensusRules.medianTime(ancestor, this::getHeaderContext);
    }

    @Autowired
    private UTXOCache utxoCache;

    public String getNetworkName() {
        return config.getParams().getName();
    }

    public boolean isStateHealthy() {
        return stateHealthy;
    }

    public ChainTipSnapshot getMainTipSnapshot() {
        BlockchainManager manager = blockchainManager;
        return manager == null ? null : manager.getMainTipSnapshot();
    }

    @PostConstruct
    public synchronized void init() {
        stateHealthy = false;
        blockchainManager = BlockchainManager.open(new ChainStateStore(dataBase, this::getBlockByHash), config.getParams());
        stateHealthy = true;
    }

    // 按孤块自身哈希暂存，待父块到达后重新验证。
    private final Cache<String, Block> orphanBlocks = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .initialCapacity(100)
            .maximumWeight(32L * 1024 * 1024)
            .weigher((String key, Block value) -> (int) Math.min(Integer.MAX_VALUE,
                    4096L + value.getTransactions().stream().mapToLong(TransactionValidator::memoryWeight).sum()))
            .recordStats()
            .build();

    public boolean hasBlockBody(byte[] hash) {
        return hasFullBlock(hash);
    }

    public UInt256 getChainWorkByHash(byte[] hash) {
        BlockchainManager manager = blockchainManager;
        return manager == null ? UInt256.ZERO : manager.getChainWorkByHash(hash);
    }

    public BlockHeader getBlockHeaderByHeight(int height) {
        BlockchainManager manager = blockchainManager;
        return manager == null ? null : manager.getMainChainHeaderByHeight(height);
    }

    /** 追溯仅有区块头的祖先区块时，使用本地记录的高度和累计工作量。 */
    public Block getHeaderContext(byte[] hash) {
        BlockchainManager manager = blockchainManager;
        return manager == null ? null : manager.getHeaderContext(hash);
    }

    /** 校验父头、难度和累计工作量后保存区块头，供同步时先行验证头链。 */
    public synchronized boolean addBlockHeaderToStore(BlockHeader header, int height, UInt256 claimedWork) {
        if (!stateHealthy || header == null || height < 1 || claimedWork == null) {
            return false;
        }
        try {
            header = BlockHeader.deserialize(header.serialize());
            Block parent = getHeaderContext(header.getPreviousHash());
            if (parent == null || height != Math.addExact(parent.getHeight(), 1)
                    || header.getBits() != ConsensusRules.expectedBits(parent, header.getTime(), config.getParams(),
                            this::getHeaderContext)
                    || header.getTime() <= ConsensusRules.medianTime(parent, this::getHeaderContext)
                    || header.getTime() > System.currentTimeMillis() / 1000 + 7200
                    || !header.verifyProofOfWork(false)) {
                return false;
            }
            return blockchainManager.acceptHeader(header, height, claimedWork);
        } catch (IllegalStateException storageFailure) {
            stateHealthy = false;
            throw storageFailure;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /**
     * 接收并验证完整区块，再根据累计工作量决定是否切换主链。
     * 固化输入快照后委托链状态存储准备并原子提交数据库变更；提交成功后再协调缓存、交易池和广播。
     * 提交后的内存协调失败会将节点标记为不健康，避免把已落库的区块误认为回滚。
     */
    public synchronized boolean verifyBlock(Block block) {
        if (!stateHealthy || block == null || block.getBlockHeader() == null) {
            return false;
        }
        ChainStateStore.Accepted accepted;
        try {
            if (block.getTransactions() == null || block.getTransactions().isEmpty()
                    || block.getTransactions().size() > ConsensusRules.MAX_BLOCK_TRANSACTIONS
                    || block.serialize().length > ConsensusRules.MAX_BLOCK_BYTES) {
                return false;
            }
            // 提交前对调用方传入的可变对象取快照，并拒绝不符合规范的传输编码。
            block = Block.deserialize(block.serialize());
            if (!block.getBlockHeader().verifyProofOfWork(false)) {
                return false;
            }
            if (hasFullBlock(block.getHash())) {
                return true;
            }
            if (isMissingParentBlock(block)) {
                if (ConsensusRules.target(block.getBlockHeader().getBits())
                        .compareTo(ConsensusRules.target((int) config.getParams().getPowLimitBits())) > 0) {
                    return false;
                }
                addOrphanBlock(block);
                return false;
            }
            accepted = blockchainManager.accept(block);
        } catch (ChainStateLimitException resourceLimit) {
            rejectedBlocks.incrementAndGet();
            log.debug("Rejected block exceeding local processing limits: {}", resourceLimit.getMessage());
            return false;
        } catch (IllegalArgumentException | NullPointerException malformed) {
            rejectedBlocks.incrementAndGet();
            log.debug("Rejected block: {}", malformed.getMessage());
            return false;
        } catch (IllegalStateException storageFailure) {
            stateHealthy = false;
            log.error("Chain state unavailable; refusing further writes until recovery", storageFailure);
            return false;
        }
        acceptedBlocks.incrementAndGet();
        if (accepted.mainChanged()) {
            if (!accepted.disconnected().isEmpty()) {
                reorganizations.incrementAndGet();
            }
            TransactionCache.invalidateAll();
            utxoCache.invalidateAll();
            // 数据库已提交，后续缓存或交易池更新失败时，不能误报为提交已回滚。
            try {
                if (!accepted.disconnected().isEmpty()) {
                    int forkHeight = accepted.disconnected().stream().mapToInt(Block::getHeight).min().orElse(1) - 1;
                    FeeEstimate.reorg(forkHeight, block.getHeight());
                } else {
                    FeeEstimate.advanceHeight(block.getHeight());
                }
                for (Block confirmed : accepted.connected()) {
                    removeConfirmedTxsFromPool(confirmed);
                }
                List<Block> disconnected = new ArrayList<>(accepted.disconnected());
                Collections.reverse(disconnected);
                for (Block old : disconnected) {
                    for (Transaction tx : old.getTransactions()) {
                        if (!tx.isCoinbase()) {
                            // 先按旧链的祖先到后代顺序恢复交易，再统一重验证，
                            // 避免交易池中的子交易在父交易恢复前被误删。
                            txPool.addTx(tx);
                        }
                    }
                }
                txPool.revalidate();
            } catch (Exception memoryFailure) {
                stateHealthy = false;
                log.error("Block committed but memory reconciliation failed; restart to recover", memoryFailure);
            }
        }
        try {
            routingTable.BroadcastResource(0, block.getHash(), block.getHeight(), block.getChainWork());
        } catch (Exception broadcastFailure) {
            log.warn("Block committed; broadcast failed: {}", broadcastFailure.getMessage());
        }
        processOrphanBlocks();
        return true;
    }

    public Transaction[] getRecentTxByAddress(String address) {
        List<Transaction> recent = new ArrayList<>(Arrays.asList(txPool.getTxsByAddress(address)));
        recent.addAll(getTransactionsByPage(address, 20, null).getData());
        return recent.stream().limit(100).toArray(Transaction[]::new);
    }

    public List<Block> getOrphanBlocks() {
        return orphanBlocks.asMap().values().stream()
                .toList();
    }

    // 接收和同步区块时使用的区块查询方法。
    public boolean hasFullBlock(byte[] hash) {
        BlockchainManager manager = blockchainManager;
        return manager != null && manager.hasFullBlock(hash);
    }

    public boolean isMissingParentBlock(Block block) {
        if (block == null || block.getBlockHeader() == null || block.getHeight() == 0) {
            return false;
        }

        byte[] previousHash = block.getBlockHeader().getPreviousHash();
        if (previousHash == null || previousHash.length != 32 || isZeroHash(previousHash)) {
            return false;
        }

        return !hasFullBlock(previousHash);
    }

    private boolean isZeroHash(byte[] hash) {
        if (hash == null) {
            return true;
        }
        for (byte b : hash) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static class Pair<F, S> {

        private final F first;
        private final S second;

        public Pair(F first, S second) {
            this.first = first;
            this.second = second;
        }

        public F getFirst() {
            return first;
        }

        public S getSecond() {
            return second;
        }
    }

    /**
     * 二分法查找两条链的共同祖先区块
     * 原理：通过比较两条链在不同高度的区块哈希，定位最大的共同高度，进而找到共同祖先
     * @param newChainTip 新链的末端区块（分叉链的最新区块）
     * @return 共同祖先区块
     */
    private Block findCommonAncestor(Block newChainTip) {
        // 1. 确定查找范围：两条链的最大可能共同高度（取两者中的较小值）
        int mainChainHeight = getMainTipSnapshot().height();
        int newChainHeight = newChainTip.getHeight();
        int maxPossibleHeight = Math.min(mainChainHeight, newChainHeight);

        int low = 0;
        int high = maxPossibleHeight;
        int commonHeight = -1; // 记录找到的最大共同高度

        // 2. 二分查找最大共同高度
        while (low <= high) {
            int mid = (low + high) >>> 1; // 避免溢出的中间值计算

            // 2.1 获取主链在mid高度的区块哈希
            byte[] mainChainHash = getBlockHashByHeight(mid);
            if (mainChainHash == null) {
                // 主链在该高度无区块，缩小上界
                high = mid - 1;
                continue;
            }

            // 2.2 获取新链在mid高度的区块哈希（从新链末端向上追溯）
            byte[] newChainHash = getHashByHeightFromChain(newChainTip, mid);
            if (newChainHash == null) {
                // 新链在该高度无区块，缩小上界
                high = mid - 1;
                continue;
            }

            // 2.3 比较哈希，确定共同高度范围
            if (Arrays.equals(mainChainHash, newChainHash)) {
                // 哈希相同，记录当前高度并尝试查找更高的共同高度
                commonHeight = mid;
                low = mid + 1;
            } else {
                // 哈希不同，缩小到更低范围查找
                high = mid - 1;
            }
        }

        // 3. 处理查找结果
        if (commonHeight == -1) {
            // 未找到共同高度（理论上创世区块必为共同祖先）
            log.warn("未找到共同祖先，返回创世区块");
            return getGenesisBlock();
        }

        // 4. 根据共同高度获取祖先区块（主链和新链在该高度哈希相同，取主链即可）
        byte[] ancestorHash = getBlockHashByHeight(commonHeight);
        Block ancestorBlock = getBlockByHash(ancestorHash);
        if (ancestorBlock == null) {
            log.error("共同高度{}对应的区块不存在，返回创世区块", commonHeight);
            return getGenesisBlock();
        }
        log.info("找到共同祖先，高度: {}, 哈希: {}",
                commonHeight, bytesToHex(ancestorBlock.getHash()));
        return ancestorBlock;
    }

    private Block getGenesisBlock() {
        return getBlockByHeight(0);
    }

    private byte[] getBlockHashByHeight(int height) {
        BlockchainManager manager = blockchainManager;
        return manager == null ? null : manager.getMainChainHashByHeight(height);
    }

    private byte[] getHashByHeightFromChain(Block chainTip, long targetHeight) {
        if (chainTip == null) {
            return null;
        }
        BlockHeader blockHeader = chainTip.getBlockHeader();
        // 目标高度超过链末端高度，直接返回null
        if (targetHeight > chainTip.getHeight()) {
            return null;
        }
        BlockHeader current = blockHeader;
        // 向上追溯直到找到目标高度或到达链的起点
        while (current != null) {
            if (getBlockHeightByHash(current.getHash()) == targetHeight) {
                return current.getHash();
            }
            if (getBlockHeightByHash(current.getHash()) < targetHeight) {
                // 已越过目标高度仍未找到（通常是链不连续导致）
                return null;
            }
            // 继续向上追溯父区块
            current = getBlockHeaderByHash(current.getPreviousHash());
        }
        return null;
    }

    private int getBlockHeightByHash(byte[] hash) {
        BlockchainManager manager = blockchainManager;
        return manager == null ? -1 : manager.getHeightByHash(hash);
    }

    private boolean processingOrphans;

    private void addOrphanBlock(Block block) {
        orphanBlocks.put(bytesToHex(block.getHash()), block);
    }

    private void processOrphanBlocks() {
        // 父块到达后反复扫描：处理一个孤块可能又解锁其子块，直到本轮不再有进展。
        if (processingOrphans) {
            return;
        }
        processingOrphans = true;
        try {
            boolean progressed;
            do {
                progressed = false;
                for (Block orphan : List.copyOf(orphanBlocks.asMap().values())) {
                    if (!hasFullBlock(orphan.getBlockHeader().getPreviousHash())) {
                        continue;
                    }
                    orphanBlocks.invalidate(bytesToHex(orphan.getHash()));
                    if (verifyBlock(orphan)) {
                        progressed = true;
                    }
                }
            } while (progressed);
        } finally {
            processingOrphans = false;
        }
    }

    /**
     * 从交易池删除已上链的交易
     */
    private void removeConfirmedTxsFromPool(Block block) {
        List<Transaction> transactions = block.getTransactions();
        if (transactions == null || transactions.isEmpty()) {
            FeeEstimate.advanceHeight(block.getHeight());
            return;
        }

        // 收集所有已确认的交易（跳过coinbase交易）
        List<Transaction> confirmedTxs = new ArrayList<>();
        for (Transaction tx : transactions) {
            if (!tx.isCoinbase()) {
                confirmedTxs.add(tx);
            }
        }

        // 从交易池删除已确认的交易
        txPool.onBlockConfirmed(confirmedTxs.toArray(new Transaction[0]), block.getHeight());
    }

    public Block getMainLatestBlock() {
        BlockchainManager manager = blockchainManager;
        return manager == null ? null : manager.getMainLatestBlock();
    }

    public static Block createGenesisBlock() {
        return createGenesisBlock(ParamsConfig.MainNetParams);
    }

    public static Block createGenesisBlock(ParamsConfig.Params params) {
        Transaction coinbase = createGenesisCoinbaseTransaction();
        coinbase.getInputs().getFirst()
                .setScriptSig(("bitcoin_java/consensus-3/" + params.getName()).getBytes(StandardCharsets.UTF_8));
        coinbase.getOutputs().getFirst().setValue(0); // 不向开发钱包分配预挖奖励。
        coinbase.getOutputs().getFirst().setScriptPubKey(new byte[20]);
        coinbase.setTxId(coinbase.calculateTxId());
        Block genesis = new Block();
        genesis.setHeight(0);
        genesis.setTransactions(List.of(coinbase));
        genesis.setTxCount(1);
        long genesisTime = params.getGenesisTime();
        long genesisNonce = params.getGenesisNonce();
        // 保留旧构造器创建的测试参数兼容性。它们复用对应内置网络的创世向量，
        // 只覆盖难度调整或成熟度等单项规则。
        if (genesisNonce < 0) {
            ParamsConfig.Params base = switch (params.getName()) {
                case "mainnet" -> ParamsConfig.MainNetParams;
                case "testnet3" -> ParamsConfig.TestNet3Params;
                case "regtest" -> ParamsConfig.RegressionNetParams;
                default -> throw new IllegalArgumentException("Genesis nonce is not configured for " + params.getName());
            };
            genesisTime = base.getGenesisTime();
            genesisNonce = base.getGenesisNonce();
        }
        BlockHeader header = new BlockHeader(1, new byte[32], calculateMerkleRoot(genesis.getTransactions()),
                genesisTime, (int) params.getPowLimitBits(), 0);
        header.setNonce((int) genesisNonce);
        if (!header.verifyProofOfWork(false) || !Arrays.equals(header.calculateHash(), params.getGenesisHash())) {
            throw new IllegalStateException("Genesis vector mismatch");
        }
        genesis.setBlockHeader(header);
        genesis.setHash(header.calculateHash());
        genesis.setChainWork(UInt256.fromBigInteger(header.getWork()));
        return genesis;
    }

    public static Transaction createCoinBaseTransaction(String minerAddress, long blockHeight, long fee) {
        return createCoinBaseTransaction(minerAddress, blockHeight, fee, ParamsConfig.MainNetParams);
    }

    public static Transaction createCoinBaseTransaction(String minerAddress, long blockHeight, long fee,
            ParamsConfig.Params params) {
        // 获取当前时间毫秒数
        long currentTimeMillis = System.nanoTime();
        byte[] timeBytes = longToLittleEndian(currentTimeMillis);
        // 对时间字节数组进行第一次SHA256哈希
        byte[] firstHash = Sha.applySHA256(timeBytes);
        // 生成随机数（使用安全随机数生成器）
        SecureRandom secureRandom = new SecureRandom();
        long randomNumber = secureRandom.nextLong();
        byte[] randomBytes = longToLittleEndian(randomNumber);
        // 拼接第一次哈希结果和随机数字节数组
        byte[] combined = new byte[firstHash.length + randomBytes.length];
        System.arraycopy(firstHash, 0, combined, 0, firstHash.length);
        System.arraycopy(randomBytes, 0, combined, firstHash.length, randomBytes.length);
        // 对拼接后的数组进行第二次SHA256哈希
        byte[] secondHash = Sha.applyRIPEMD160(combined);
        byte[] extraNonce = Arrays.copyOfRange(secondHash, 0, 16);
        System.arraycopy(intToLittleEndian(Math.toIntExact(blockHeight)), 0, extraNonce, 0, 4);
        Transaction coinbaseTx = new Transaction();
        TxInput input = new TxInput();
        input.setTxId(new byte[32]); // 全零交易ID
        input.setIndex(0); // 特殊值表示CoinBase交易
        input.setScriptSig(extraNonce);
        List<TxInput> inputs = new ArrayList<>();
        inputs.add(input);
        coinbaseTx.setVersion(0);
        coinbaseTx.setInputs(inputs);
        // 创建输出（初始奖励50 BTC = 50*1e8聪）
        TxOutput output = new TxOutput();
        //根据高度计算奖励
        //TODO
        if (!TransactionValidator.moneyRange(fee)) {
            throw new IllegalArgumentException("Invalid fee");
        }
        output.setValue(Math.addExact(ConsensusRules.subsidy(Math.toIntExact(blockHeight), params), fee)); // 初始补贴为 50 BTC，以聪计。
        output.setScriptPubKey(Sha.applyRIPEMD160(Sha.applySHA256(Base58.decode(minerAddress))));
        List<TxOutput> outputs = new ArrayList<>();
        outputs.add(output);
        coinbaseTx.setOutputs(outputs);
        // 计算交易ID
        byte[] txId = coinbaseTx.calculateTxId();
        coinbaseTx.setTxId(txId);
        return coinbaseTx;
    }

    public static byte[] calculateMerkleRoot(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return new byte[32]; // 空交易列表返回零哈希
        }
        // 1. 提取所有交易的哈希（txId）作为默克尔树的叶子节点
        List<byte[]> leafHashes = transactions.stream()
                .map(Transaction::getTxId) // 每个交易的txId作为叶子哈希
                .collect(Collectors.toList());
        // 2. 递归构建默克尔树并返回根哈希
        return buildMerkleTree(leafHashes);
    }

    /**
     * 递归构建默克尔树并返回根哈希
     * 核心逻辑：逐层合并哈希对，每对哈希拼接后做双SHA-256，最终得到根哈希
     * @param hashes 当前层级的哈希列表（初始为叶子节点哈希）
     * @return 默克尔树根哈希（32字节数组）
     */
    private static byte[] buildMerkleTree(List<byte[]> hashes) {
        // 过滤无效哈希（null值）
        List<byte[]> validHashes = hashes.stream()
                .filter(hash -> hash != null)
                .collect(Collectors.toList());

        // 边界情况：空列表返回零哈希
        if (validHashes.isEmpty()) {
            return new byte[32];
        }

        // 边界情况：单哈希直接作为根（如创世块仅含1笔交易）
        if (validHashes.size() == 1) {
            return validHashes.get(0);
        }

        // 构建下一层哈希列表
        List<byte[]> nextLevel = new ArrayList<>();
        for (int i = 0; i < validHashes.size(); i += 2) {
            byte[] left = validHashes.get(i);
            // 若为奇数个哈希，最后一个哈希与自身合并
            byte[] right = (i + 1 < validHashes.size()) ? validHashes.get(i + 1) : left;
            // 拼接哈希对并计算双SHA-256
            byte[] merged = concat(left, right);
            byte[] parentHash = Sha.applySHA256(Sha.applySHA256(merged));
            nextLevel.add(parentHash);
        }
        // 递归处理下一层
        return buildMerkleTree(nextLevel);
    }

    private static Transaction createGenesisCoinbaseTransaction() {
        Transaction coinbaseTx = new Transaction();
        TxInput input = new TxInput();
        input.setTxId(new byte[32]); // 全零交易ID
        input.setIndex(0); // 特殊值表示CoinBase交易
        byte[] bytes = "The Times 03/Jan/2009 Chancellor on brink of second bailout for banks"
                .getBytes(StandardCharsets.UTF_8);//69字节
        input.setScriptSig(bytes);
        List<TxInput> inputs = new ArrayList<>();
        inputs.add(input);
        coinbaseTx.setVersion(0);
        coinbaseTx.setInputs(inputs);
        // 创建输出（初始奖励50 BTC = 50*1e8聪）
        TxOutput output = new TxOutput();
        output.setValue(50L * 100000000); // 将 50 BTC 换算为聪。
        String address = "J5JGY238f6TGBuscTBs5AQjDu6Jpau1mUaFYq89ty3GR";
        output.setScriptPubKey(Sha.applyRIPEMD160(Sha.applySHA256(Base58.decode(address))));
        List<TxOutput> outputs = new ArrayList<>();
        outputs.add(output);
        coinbaseTx.setOutputs(outputs);
        // 计算交易ID
        byte[] txId = coinbaseTx.calculateTxId();
        coinbaseTx.setTxId(txId);
        return coinbaseTx;
    }

    //获取交易手续费
    public long calculateTransactionFee(Transaction transaction) {
        return transaction.getFee();
    }

    public long calculateTransactionFee(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return 0L; // 处理空列表或null值的情况
        }
        long totalFee = 0L;
        for (Transaction transaction : transactions) {
            // 假设Transaction类中有一个getFee()方法返回手续费
            totalFee = Math.addExact(totalFee, transaction.getFee());
        }
        return totalFee;
    }

    public Block getBlockByHash(byte[] hash) {
        //获取区块头 区块体
        if (hash == null || hash.length != 32) {
            return null;
        }

        byte[] header_bytes = dataBase.get(TBLOCK_HEADER, hash);
        byte[] body_bytes = dataBase.get(TBLOCK_BODY, hash);
        if (header_bytes != null && body_bytes != null) {
            // 运行期间的累计工作量由 BlockchainManager 的头索引提供；启动恢复时管理器尚未发布，
            // 仅由持久化回调读取已提交值，供管理器完成初始化。
            BlockchainManager manager = blockchainManager;
            UInt256 uInt256 = manager == null
                    ? UInt256.fromBytes(dataBase.get(HASH_TO_CHAIN_WORK, hash))
                    : manager.getChainWorkByHash(hash);
            BlockHeader blockHeader = BlockHeader.deserialize(header_bytes);
            BlockBody blockBody = BlockBody.deserialize(body_bytes);
            Block block = Block.buildBlock(blockHeader, blockBody);
            block.setChainWork(uInt256);
            return block;
        } else {
            return null;
        }
    }

    /**
     * 根据交易哈希获取交易对象。
     * 此方法现在可以处理包含多笔交易的区块。
     *
     * @param txId 交易哈希 (32字节)
     * @return 找到的 Transaction 对象，如果未找到或发生错误则返回 null
     */
    public Transaction getTxByHash(byte[] txId) {
        if (txId == null || txId.length != Transaction.TX_ID_LENGTH) {
            return null;
        }
        //先查询是否在交易池中
        Transaction tx = txPool.getTx(txId);
        if (tx != null) {
            return tx;
        }
        Transaction ifPresent = TransactionCache.getIfPresent(bytesToHex(txId));
        if (ifPresent != null) {
            log.info("存在缓存中");
            return ifPresent;
        }
        // 1. 从 "tx_to_block" 表中获取区块索引信息
        byte[] blockIndexBytes = dataBase.get(TableEnum.TX_TO_BLOCK, txId);
        if (blockIndexBytes == null) {
            return null; // 未找到该交易的记录
        }

        // 2. 解析区块哈希和交易在区块内的索引
        Map<String, Object> blockIndexMap = Transaction.parseBlockIndex(blockIndexBytes);
        byte[] blockHash = (byte[]) blockIndexMap.get("hash");
        int txIndexInBlock = (int) blockIndexMap.get("index");

        // 3. 从 "block" 表中获取完整的区块数据
        byte[] blockBodyData = dataBase.get(TBLOCK_BODY, blockHash);
        if (blockBodyData == null) {
            return null; // 区块数据不存在
        }
        BlockBody deserialize = BlockBody.deserialize(blockBodyData);
        if (deserialize == null || deserialize.getTransactions() == null
                || txIndexInBlock < 0 || txIndexInBlock >= deserialize.getTransactions().size()) {
            return null;
        }
        Transaction transaction = deserialize.getTransactions().get(txIndexInBlock);
        if (transaction == null) {
            return null;
        }
        long status = 0;
        //交易所在的区块是否主连区块
        boolean isMainBlock = isMainBlock(blockHash);
        log.info("当前交易是否主链交易{}", isMainBlock);
        if (isMainBlock) {
            status = TransactionStatusResolver.setLifecycleStatus(status, TransactionStatusResolver.CONFIRMED);
        } else {
            status = TransactionStatusResolver.setLifecycleStatus(status, TransactionStatusResolver.FORK);
        }
        transaction.setStatus(status);
        TransactionCache.put(bytesToHex(txId), transaction);
        return transaction;
    }

    public Transaction getTxAndQueryInputByHash(byte[] txId) {
        // API callers can provide malformed hashes.  Avoid passing null or a
        // short key into the database and avoid exposing an internal parsing
        // exception as a 500 response.
        if (txId == null || txId.length != Transaction.TX_ID_LENGTH) {
            return null;
        }
        //先查询是否在交易池中
        Transaction tx = txPool.getTx(txId);
        if (tx != null) {
            return tx;
        }
        // 1. 从 "tx_to_block" 表中获取区块索引信息
        byte[] blockIndexBytes = dataBase.get(TableEnum.TX_TO_BLOCK, txId);
        if (blockIndexBytes == null) {
            return null; // 未找到该交易的记录
        }

        // 2. 解析区块哈希和交易在区块内的索引
        Map<String, Object> blockIndexMap = Transaction.parseBlockIndex(blockIndexBytes);
        byte[] blockHash = (byte[]) blockIndexMap.get("hash");
        int txIndexInBlock = (int) blockIndexMap.get("index");

        byte[] blockHeaderData = dataBase.get(TBLOCK_HEADER, blockHash);
        if (blockHeaderData == null) {
            return null; // 区块数据不存在
        }
        BlockHeader blockHeader = BlockHeader.deserialize(blockHeaderData);

        // 3. 从 "block" 表中获取完整的区块数据
        byte[] blockBodyData = dataBase.get(TBLOCK_BODY, blockHash);
        if (blockBodyData == null) {
            return null; // 区块数据不存在
        }
        BlockBody deserialize = BlockBody.deserialize(blockBodyData);
        if (deserialize == null || deserialize.getTransactions() == null
                || txIndexInBlock < 0 || txIndexInBlock >= deserialize.getTransactions().size()) {
            return null;
        }
        Transaction transaction = deserialize.getTransactions().get(txIndexInBlock);
        if (transaction == null) {
            return null;
        }
        //带出输入对应的输出
        List<TxInput> inputs = transaction.getInputs();
        for (TxInput txInput : inputs) {
            if (!txInput.isCoinbase() && deserialize.getHeight() != 0) {
                Transaction txByHash = getTxByHash(txInput.getTxId());
                if (txByHash != null && txByHash.getOutputs() != null
                        && txInput.getIndex() >= 0 && txInput.getIndex() < txByHash.getOutputs().size()) {
                    txInput.setOutput(txByHash.getOutputs().get(txInput.getIndex()));
                }
            }
        }
        transaction.setInputs(inputs);
        long status = 0;
        //交易所在的区块是否主连区块
        boolean isMainBlock = isMainBlock(blockHash);
        log.info("当前交易是否主链交易{}", isMainBlock);
        if (isMainBlock) {
            status = TransactionStatusResolver.setLifecycleStatus(status, TransactionStatusResolver.CONFIRMED);
        } else {
            status = TransactionStatusResolver.setLifecycleStatus(status, TransactionStatusResolver.FORK);
        }
        transaction.setStatus(status);
        transaction.setTime(blockHeader.getTime());
        transaction.setHeight(deserialize.getHeight());
        transaction.setHash(blockHeader.calculateHash());
        return transaction;
    }

    //获取一笔交易所有参与交易待花费的UTXO
    public Map<String, UTXO> getUTXOsByTx(Transaction transaction) {
        List<TxInput> inputs = transaction.getInputs();
        Map<String, UTXO> utxos = new HashMap<>();
        for (TxInput txInput : inputs) {
            byte[] txId = txInput.getTxId();
            int index = txInput.getIndex();
            //交易ID是全零且index=0 则是一个铸币输入
            if (Arrays.equals(txId, new byte[32]) && index == 0) {
                log.info("这是一个铸币输入");
            } else {
                byte[] key = UTXO.getKey(txId, index);
                byte[] bytes = dataBase.get(TableEnum.TUTXO, key);
                assert bytes != null;
                UTXO deserialize = UTXO.deserialize(bytes);
                utxos.put(bytesToHex(key), deserialize);
            }
        }
        return utxos;
    }

    public BlockHeader getBlockHeaderByHash(byte[] hash) {
        byte[] bytes = dataBase.get(TBLOCK_HEADER, hash);
        if (bytes != null) {
            //获取前80字节
            byte[] headerBytes = Arrays.copyOfRange(bytes, 0, 80);
            //序列化
            return BlockHeader.deserialize(headerBytes);
        } else {
            return null;
        }
    }

    public Map<String, Object> getUTXOs(String address) {
        List<UTXO> utxos = utxoCache.getAddressAllUTXO(address);
        return Map.of("total", utxos.stream().mapToLong(UTXO::getValue).reduce(0L, Math::addExact), "utxos", utxos);
    }

    public Block getBlockByHeight(int height) {
        BlockchainManager manager = blockchainManager;
        return manager == null ? null : manager.getMainChainBlockByHeight(height);
    }

    /**
     * 已经上链的交易
     * @param address
     * @return
     */
    public List<Transaction> getTxListByAddres(String address) {
        return getTransactionsByPage(address, 20, null).getData();
    }

    //分页获取这个地址下的交易
    public List<Transaction> getTxListByAddres(String address, int pageNum, int pageSize) {
        if (pageNum != 1) {
            throw new IllegalArgumentException("Use cursor pagination for address history");
        }
        return getTransactionsByPage(address, pageSize, null).getData();
    }

    public int calculateNextBlockDifficulty() {
        Block tip = getMainLatestBlock();
        long time = Math.max(System.currentTimeMillis() / 1000,
                ConsensusRules.medianTime(tip, this::getHeaderContext) + 1);
        return ConsensusRules.expectedBits(tip, time, config.getParams(), this::getHeaderContext);
    }

    private int getCurrentChainHeight() {
        return getMainTipSnapshot().height();
    }

    public static void main(String[] args) {
        byte[] bytes = "The Times 03/Jan/2009 Chancellor on brink of second bailout for banks"
                .getBytes(StandardCharsets.UTF_8);
        log.info("长度{}", bytes.length);

        Transaction coinbaseTx = createGenesisCoinbaseTransaction();
        List<Transaction> transactions = new ArrayList<>();
        transactions.add(coinbaseTx);
        byte[] merkleRoot = calculateMerkleRoot(transactions);

        // 创世区块的已知信息
        int version = 1;
        byte[] prevBlockHash = new byte[32]; // 前一个区块哈希全0
        int timestamp = 1754287472; // 2017年2月3日
        int bits = convertTargetToBits(new BigInteger(BlockHeader.INIT_DIFFICULTY_TARGET_HEX, 16));

        // 挖矿参数
        BigInteger target = new BigInteger(BlockHeader.INIT_DIFFICULTY_TARGET_HEX, 16);
        int maxNonce = Integer.MAX_VALUE; // 最大nonce值
        int nonce = 0;
        boolean found = false;

        System.out.println("开始挖矿...");
        System.out.println("目标值: " + target.toString(16));
        System.out.println("目标bits: " + bits);

        long startTime = System.currentTimeMillis();

        // 挖矿循环
        for (nonce = 0; nonce < maxNonce; nonce++) {
            // 创建区块头
            BlockHeader blockHeader = new BlockHeader(version, prevBlockHash, merkleRoot, timestamp, bits, nonce);

            // 计算区块哈希
            byte[] blockHashBytes = blockHeader.calculateHash();
            BigInteger hashValue = new BigInteger(1, blockHashBytes);

            // 检查是否小于等于目标值
            if (hashValue.compareTo(target) <= 0) {
                found = true;
                break;
            }

            // 每100万次打印一次进度
            if (nonce % 1000000 == 0 && nonce > 0) {
                long elapsedTime = System.currentTimeMillis() - startTime;
                double hashRate = nonce / (elapsedTime / 1000.0);
                System.out.println(String.format("尝试次数: %d, 哈希率: %.2f H/s",
                        nonce, hashRate));
            }
        }

        if (found) {
            long elapsedTime = System.currentTimeMillis() - startTime;
            double elapsedSeconds = elapsedTime / 1000.0;
            double hashRate = nonce / elapsedSeconds;

            System.out.println("\n挖矿成功！");
            System.out.println("找到的nonce: " + nonce);
            System.out.println("尝试次数: " + nonce);
            System.out.println("耗时: " + elapsedSeconds + " 秒");
            System.out.println("平均哈希率: " + String.format("%.2f", hashRate) + " H/s");

            // 创建最终的创世区块
            Block genesisBlock = new Block();
            genesisBlock.setHeight(0);
            BlockHeader finalHeader = new BlockHeader(version, prevBlockHash, merkleRoot, timestamp, bits, nonce);
            genesisBlock.setBlockHeader(finalHeader);
            genesisBlock.setTransactions(transactions);

            // 输出区块信息
            byte[] finalHash = finalHeader.calculateHash();
            System.out.println("\n创世区块哈希: " + bytesToHex(finalHash));
            System.out.println("区块头哈希值(十六进制): " + new BigInteger(1, finalHash).toString(16));

        } else {
            System.out.println("挖矿失败，未找到有效的nonce");
        }

    }

    public UTXO getUtxo(String key) {
        if (key == null || !key.matches("(?i)[0-9a-f]{72}")) {
            return null;
        }
        byte[] bytes = dataBase.get(TUTXO, hexToBytes(key));
        return bytes == null ? null : UTXO.deserialize(bytes);
    }

    /**
     * 分页查询区块
     * 查询指定高度范围内的区块（从较高高度到较低高度）
     * 每次最多返回100个区块
     *
     * @param startHeight 起始高度（较高高度）
     * @param endHeight 结束高度（较低高度）
     * @return 区块列表
     */
    public List<Block> getBlocksByRange(int startHeight, int endHeight) {
        if (startHeight < 0) {
            startHeight = 0;
        }

        if (endHeight < 0) {
            endHeight = 0;
        }

        // 确保 startHeight >= endHeight
        if (startHeight < endHeight) {
            int temp = startHeight;
            startHeight = endHeight;
            endHeight = temp;
        }

        if (startHeight > getCurrentChainHeight()) {
            startHeight = getCurrentChainHeight();
        }
        int count = (int) Math.min(100L, (long) startHeight - endHeight + 1);
        if (count <= 0) {
            return List.of();
        }
        endHeight = Math.max(endHeight, startHeight - count + 1);

        // 限制最多100个
        if (count > 100) {
            endHeight = startHeight - 99;  // 调整结束高度
            count = 100;
        }

        List<Block> blocks = new ArrayList<>(count);
        long responseBytes = 0;
        for (int height = startHeight; height >= endHeight; height--) {
            Block block = getBlockByHeight(height);
            if (block != null) {
                responseBytes += block.getTransactions().stream().mapToLong(TransactionValidator::memoryWeight).sum();
                if (responseBytes > 16L * 1024 * 1024) {
                    throw new IllegalArgumentException("Response budget exceeded; reduce block range");
                }
                blocks.add(block);
            }
        }

        return blocks;
    }

    /**
     * 分页查询交易
     * @param pageNum 页码（从1开始）
     * @param pageSize 每页大小
     * @return 分页结果
     */
    public List<Transaction> getTransactionsByPage(int pageNum, int pageSize) {
        if (pageNum < 1 || pageNum > 100 || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("Page bounds exceeded");
        }
        int skip = (pageNum - 1) * pageSize;
        List<Transaction> result = new ArrayList<>(pageSize);
        long bytes = 0;
        for (int height = getCurrentChainHeight(); height >= 0 && result.size() < pageSize; height--) {
            Block block = getBlockByHeight(height);
            if (block == null) {
                throw new IllegalStateException("Missing main-chain block");
            }
            for (Transaction tx : block.getTransactions()) {
                if (skip-- > 0) {
                    continue;
                }
                tx.setHeight(height);
                tx.setHash(block.getHash());
                tx.setTime(block.getBlockHeader().getTime());
                tx.setStatus(TransactionStatusResolver.CONFIRMED);
                bytes += TransactionValidator.memoryWeight(tx);
                if (bytes > 16L * 1024 * 1024) {
                    throw new IllegalArgumentException("Response budget exceeded; reduce page size");
                }
                result.add(tx);
                if (result.size() >= pageSize) {
                    break;
                }
            }
        }
        return result;
    }

    @Override
    public PageResult<Transaction> getTransactionsByPage(String address, int pageSize, String lastKey) {
        if (pageSize < 1 || pageSize > 20) {
            throw new IllegalArgumentException("Page size must be 1..20");
        }
        byte[] prefix = addressPrefix(address);
        byte[] cursor = lastKey == null || lastKey.isBlank() ? null : hexToBytes(lastKey);
        if (cursor != null && (cursor.length != 52 || !Arrays.equals(prefix, Arrays.copyOf(cursor, 20)))) {
            throw new IllegalArgumentException("Invalid address cursor");
        }
        PageResult<Object> page = dataBase.pageKeyByPrefix(ADDR_TO_TX, prefix, pageSize, cursor);
        List<Transaction> transactions = new ArrayList<>();
        for (Object value : page.getData()) {
            byte[] key = (byte[]) value;
            if (key.length != 52) {
                throw new IllegalStateException("Corrupt address index");
            }
            Transaction tx = getTxAndQueryInputByHash(Arrays.copyOfRange(key, 20, 52));
            if (tx == null) {
                throw new IllegalStateException("Missing indexed transaction");
            }
            transactions.add(tx);
        }
        return new PageResult<>(transactions, page.getLastKey(), page.isLastPage());
    }

    public static byte[] addressPrefix(String address) {
        if (address == null || address.length() > 44) {
            throw new IllegalArgumentException("Invalid address");
        }
        byte[] key = Base58.decode(address);
        if (key.length != 32) {
            throw new IllegalArgumentException("Address must encode a 32-byte public key");
        }
        return Sha.applyRIPEMD160(Sha.applySHA256(key));
    }

    /** 按地址分页读取主链 UTXO，传入上一页的链顶哈希可检测翻页期间的链顶变化。 */
    public synchronized Map<String, Object> utxoPage(String address, int limit, String cursor, String expectedTip) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Page size must be 1..100");
        }
        ChainTipSnapshot tipSnapshot = getMainTipSnapshot();
        String tip = bytesToHex(tipSnapshot.hash());
        if (expectedTip != null && !expectedTip.equals(tip)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Chain changed; restart pagination");
        }
        byte[] prefix = addressPrefix(address);
        byte[] last = cursor == null || cursor.isBlank() ? null : hexToBytes(cursor);
        if (last != null && (last.length != 56 || !Arrays.equals(prefix, Arrays.copyOf(last, 20)))) {
            throw new IllegalArgumentException("Invalid UTXO cursor");
        }
        PageResult<Object> page = dataBase.pageKeyByPrefix(ADDR_TO_UTXO, prefix, limit, last);
        List<UTXO> result = new ArrayList<>();
        for (Object item : page.getData()) {
            byte[] key = (byte[]) item;
            if (key.length != 56) {
                throw new IllegalStateException("Corrupt UTXO index");
            }
            byte[] raw = dataBase.get(TUTXO, Arrays.copyOfRange(key, 20, 56));
            if (raw == null) {
                throw new IllegalStateException("Missing indexed UTXO");
            }
            result.add(UTXO.deserialize(raw));
        }
        return Map.of("tip", tip, "height", tipSnapshot.height(), "utxos", result,
                "nextCursor", page.getLastKey() == null ? "" : bytesToHex(page.getLastKey()), "lastPage",
                page.isLastPage());
    }

    //区块是否主链
    @Override
    public boolean isMainBlock(String blockHexHash) {
        return blockHexHash != null && isMainBlock(hexToBytes(blockHexHash));
    }

    @Override
    public boolean hasTx(String address) {
        // 空地址直接返回false
        if (address == null || address.trim().isEmpty()) {
            log.warn("地址为空，无法查询交易");
            return false;
        }
        try {
            // 第一步：先检查交易池（内存查询，速度最快）
            Transaction[] poolTxs = txPool.getTxsByAddress(address);
            if (poolTxs != null && poolTxs.length > 0) {
                log.info("地址[{}]在交易池中存在{}笔未确认交易", address, poolTxs.length);
                return true;
            }

            // 第二步：转换地址为数据库中ADDR_TO_TX表的前缀（与分页查询逻辑保持一致）
            byte[] pubkey = Base58.decode(address);
            byte[] addrPrefix = Sha.applyRIPEMD160(Sha.applySHA256(pubkey));

            // 第三步：检查数据库中是否存在该地址的交易记录（只需判断是否有任意一条，无需全量查询）
            boolean hasRecord = dataBase.existsByPrefix(ADDR_TO_TX, addrPrefix);
            if (hasRecord) {
                log.info("地址[{}]在链上存在已确认交易", address);
                return true;
            }

            // 第四步：兜底检查UTXO（防止ADDR_TO_TX索引异常时漏判）
            boolean hasUtxo = dataBase.existsByPrefix(ADDR_TO_UTXO, addrPrefix);
            if (hasUtxo) {
                log.info("地址[{}]存在UTXO（说明曾有交易）", address);
                return true;
            }

            // 所有检查都无结果
            log.info("地址[{}]暂无任何交易记录（包括未确认和已确认）", address);
            return false;

        } catch (Exception e) {
            log.error("判断地址[{}]是否有交易时发生异常", address, e);
            // 异常时返回false（避免因解码失败/数据库异常导致程序中断）
            return false;
        }
    }

    @Override
    public byte[] hasTx(List<String> addressList) {
        // 1. 参数校验：空列表/Null直接返回空字节数组
        if (addressList == null || addressList.isEmpty()) {
            log.warn("地址列表为空，无需处理");
            return new byte[0];
        }

        int addressCount = addressList.size();
        log.info("开始批量判断{}个地址的交易存在性", addressCount);

        // 2. 计算字节数组长度：向上取整（8个地址=1字节，9个地址=2字节，以此类推）
        int byteArrayLength = (addressCount + 7) / 8;
        byte[] result = new byte[byteArrayLength]; // 默认全0（所有位初始为无交易）

        // 3. 遍历每个地址，批量判断并设置对应位
        for (int i = 0; i < addressCount; i++) {
            String address = addressList.get(i);
            try {
                // 复用已有单地址判断逻辑，保证规则完全一致
                boolean hasTransaction = hasTx(address);

                if (hasTransaction) {
                    // 计算该地址对应的字节索引和位索引
                    int byteIndex = i / 8;    // 第几个字节（0开始）
                    int bitIndex = i % 8;     // 字节内的第几位（0开始，从低位到高位）

                    // 将对应位设为1（用|=操作，避免覆盖其他位）
                    result[byteIndex] |= (byte) (1 << bitIndex);

                    log.debug("地址[{}](索引{})有交易，设置字节[{}]的位[{}]为1",
                            address, i, byteIndex, bitIndex);
                } else {
                    log.debug("地址[{}](索引{})无交易，对应位保持0", address, i);
                }
            } catch (Exception e) {
                // 单个地址处理异常：该位保持0，不影响其他地址
                log.error("处理地址[{}](索引{})时发生异常，标记为无交易", address, i, e);
            }
        }

        log.info("批量判断完成：{}个地址 → 生成{}字节的结果数组", addressCount, byteArrayLength);
        return result;
    }

    public List<Boolean> parseHasTxResult(byte[] resultBytes, int addressCount) {
        List<Boolean> resultList = new ArrayList<>(addressCount);
        if (resultBytes == null || resultBytes.length == 0 || addressCount <= 0) {
            return resultList;
        }

        for (int i = 0; i < addressCount; i++) {
            int byteIndex = i / 8;
            int bitIndex = i % 8;

            // 字节数组长度不足时，默认无交易
            if (byteIndex >= resultBytes.length) {
                resultList.add(false);
                continue;
            }

            // 提取对应位的值（1=有交易，0=无交易）
            boolean hasTx = (resultBytes[byteIndex] & (1 << bitIndex)) != 0;
            resultList.add(hasTx);
        }
        return resultList;
    }

    public boolean isMainBlock(byte[] blockHash) {
        BlockchainManager manager = blockchainManager;
        if (manager == null || blockHash == null || blockHash.length != 32) {
            return false;
        }
        int height = manager.getHeightByHash(blockHash);
        return height >= 0 && Arrays.equals(blockHash, manager.getMainChainHashByHeight(height));
    }

    /**
     * 生成区块链同步路标（基于Landmark类，帮助对方节点快速定位共同祖先）
     * @param peerMaxHeight 对方节点的最大区块高度
     * @return 路标列表（每个Landmark对应36字节：32字节hash + 4字节height）
     */
    public List<Landmark> generateSyncLandmarks(int peerMaxHeight) {
        // 存储最终的路标列表
        List<Landmark> landmarks = new ArrayList<>();
        // 存储已处理的高度，避免重复生成路标
        Set<Integer> processedHeights = new HashSet<>();

        try {
            // 1. 获取本地主链最大高度，计算有效基准高度（取本地和对方高度的较小值）
            int localMaxHeight = getCurrentChainHeight();
            int baseHeight = Math.min(localMaxHeight, peerMaxHeight);
            log.info("开始生成同步路标 | 本地最大高度: {} | 对方最大高度: {} | 基准高度: {}",
                    localMaxHeight, peerMaxHeight, baseHeight);

            // 2. 强制添加核心检查点：创世区块（高度0）和基准高度区块
            addLandmark(0, processedHeights, landmarks);
            addLandmark(baseHeight, processedHeights, landmarks);

            // 3. 指数级步进生成路标（1,2,4,8,16...倍步进，直到超过基准高度）
            // 步进规则：从1开始，每次×2，覆盖不同层级的区块，兼顾效率和覆盖范围
            int step = 1;
            while (true) {
                int targetHeight = baseHeight - step;
                // 终止条件：高度小于0 或 步进超过基准高度
                if (targetHeight <= 0 || step > baseHeight) {
                    break;
                }
                addLandmark(targetHeight, processedHeights, landmarks);
                // 步进翻倍（指数级增长）
                step *= 2;
            }

            // 4. 对路标按高度降序排序（从新到旧，方便对方优先匹配最新共同祖先）
            landmarks.sort((a, b) -> Integer.compare(b.getHeight(), a.getHeight()));

            log.info("路标生成完成 | 总数: {} | 高度范围: {} ~ {}",
                    landmarks.size(),
                    landmarks.getLast().getHeight(),
                    landmarks.getFirst().getHeight());

        } catch (Exception e) {
            log.error("生成同步路标失败", e);
            // 异常时返回包含创世区块的保底路标
            landmarks.clear();
            addLandmark(0, new HashSet<>(), landmarks);
        }

        return landmarks;
    }

    /**
     * 辅助方法：添加单个Landmark到列表（自动处理去重和合法性校验）
     * @param height 区块高度
     * @param processedHeights 已处理高度集合（去重）
     * @param landmarks 路标列表
     */
    private void addLandmark(int height, Set<Integer> processedHeights, List<Landmark> landmarks) {
        // 去重：已处理的高度跳过
        if (processedHeights.contains(height)) {
            return;
        }

        try {
            // 1. 获取该高度的区块Hash（必须是32字节）
            byte[] blockHash = getBlockHashByHeight(height);
            if (blockHash == null || blockHash.length != 32) {
                log.warn("跳过无效高度的路标生成 | 高度: {} | Hash为空或长度异常", height);
                return;
            }

            // 2. 创建Landmark对象并添加到列表
            Landmark landmark = new Landmark();
            landmark.setHash(blockHash);
            landmark.setHeight(height);

            landmarks.add(landmark);
            processedHeights.add(height);
            log.debug("添加路标 | 高度: {} | Hash: {} | 序列化后36字节: {}",
                    height, bytesToHex(blockHash), bytesToHex(landmark.serialize()));

        } catch (Exception e) {
            log.error("添加高度{}的路标失败", height, e);
        }
    }

    /**
     * 通过本地和远程的路标列表，找到双方的最新共同祖先区块的Landmark
     * 核心逻辑：优先匹配高度最高的共同区块（降序遍历），无匹配则返回创世区块
     * @param remote 远程节点发送的路标列表
     * @return 共同祖先的Landmark（优先最高高度，无匹配则返回创世区块）
     */
    public Landmark findCommonAncestorByLandmark(List<Landmark> remote) {
        int maxHeight = remote.stream().mapToInt(Landmark::getHeight).max().getAsInt();
        log.info("该路标最大高度{}", maxHeight);
        List<Landmark> local = generateSyncLandmarks(maxHeight);

        //如果远程的maxHeight 大于本地直接返回创世 因为无法比较
        if (maxHeight > getMainTipSnapshot().height()) {
            return createGenesisLandmark();
        }

        // 1. 严格参数校验与无效路标过滤
        if (local == null || local.isEmpty()) {
            log.warn("本地路标列表为null/空，返回创世区块");
            return createGenesisLandmark();
        }
        if (remote.isEmpty()) {
            log.warn("远程路标列表为null/空，返回创世区块");
            return createGenesisLandmark();
        }

        // 2. 构建远程 hash -> Landmark 映射（保留每个 hash 的最高高度），忽略无法规范化和创世高度(0)
        Map<String, Landmark> remoteByHash = new HashMap<>();
        for (Landmark r : remote) {
            String rh = bytesToHex(r.getHash());
            if (r.getHeight() == 0) {
                continue;       // 忽略创世（作为兜底返回）
            }
            Landmark exist = remoteByHash.get(rh);
            if (exist == null || r.getHeight() > exist.getHeight()) {
                remoteByHash.put(rh, r);
            }
        }
        // 3. 收集本地有效路标（可规范化且非创世），按高度降序排序
        List<Landmark> localValid = new ArrayList<>();
        for (Landmark l : local) {
            String lh = bytesToHex(l.getHash());
            if (l.getHeight() == 0) {
                continue;
            }
            localValid.add(l);
        }
        if (localValid.isEmpty() || remoteByHash.isEmpty()) {
            log.warn("无有效非创世路标可比对，返回创世区块");
            return createGenesisLandmark();
        }
        localValid.sort(Comparator.comparingInt(Landmark::getHeight).reversed());

        // 4. 按本地高度从高到低查找第一个在远端存在的 hash（即最高共同祖先）
        for (Landmark l : localValid) {
            String lh = bytesToHex(l.getHash());
            Landmark r = remoteByHash.get(lh);
            log.debug("本地高度{} 本地Hash{}", l.getHeight(), lh);
            if (r != null) {
                // 找到匹配：返回高度更高的那个 Landmark（通常两边高度相同）
                Landmark chosen = (l.getHeight() >= r.getHeight()) ? l : r;
                log.debug("找到共同祖先 | 高度: {} | Hash: {}", chosen.getHeight(), bytesToHex(chosen.getHash()));
                return chosen;
            }
        }

        // 5. 无任何匹配项，兜底返回创世区块
        log.warn("全量对比所有有效路标后，未找到非创世区块的共同祖先，返回创世区块");
        return createGenesisLandmark();
    }

    /**
     * 辅助方法：创建创世区块（高度0）的默认Landmark
     * 保证无共同祖先时，同步逻辑能从创世区块开始
     */
    private Landmark createGenesisLandmark() {
        Landmark genesisLandmark = new Landmark();
        genesisLandmark.setHeight(0);
        // 创世区块的hash需与项目中createGenesisBlock()生成的hash一致
        // 这里复用原有创世区块创建逻辑的hash，保证准确性
        Block genesisBlock = BlockChainServiceImpl.createGenesisBlock(config.getParams());
        genesisLandmark.setHash(genesisBlock.getHash());
        log.debug("创建创世区块路标 | 高度: 0 | Hash: {}", bytesToHex(genesisLandmark.getHash()));
        return genesisLandmark;
    }

}
