package com.bit.coin.blockchain;

import com.bit.coin.cache.UTXOCache;
import com.bit.coin.config.ParamsConfig;
import com.bit.coin.config.SystemConfig;
import com.bit.coin.database.DataBase;
import com.bit.coin.database.rocksDb.PageResult;
import com.bit.coin.database.rocksDb.RocksDb;
import com.bit.coin.database.rocksDb.TableEnum;

import com.bit.coin.p2p.impl.PeerClient;
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
import org.bitcoinj.core.Base58;
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


import static com.bit.coin.database.rocksDb.RocksDb.bytesToInt;
import static com.bit.coin.database.rocksDb.TableEnum.*;
import static com.bit.coin.structure.block.BlockHeader.convertTargetToBits;
import static com.bit.coin.structure.tx.UTXOStatusResolver.CONFIRMED_UNSPENT;
import static com.bit.coin.structure.tx.UTXOStatusResolver.setLifecycleStatus;
import static com.bit.coin.utils.SerializeUtils.*;



@Slf4j
@Service
public class BlockChainServiceImpl implements BlockChainService{

    @Autowired
    private SystemConfig config;

    @Autowired
    private DataBase dataBase;

    @Autowired
    private TxPool txPool;

    @Autowired
    private PeerClient peerClient;

    @Autowired
    private RoutingTable routingTable;


    public static volatile Block mainTipBlock = null;

    //主链顶端高度 key
    public static final byte[] MAIN_CHAIN_TIP_HEIGHT_KEY = "MAIN_CHAIN_TIP_HEIGHT_KEY".getBytes(StandardCharsets.UTF_8);
    //主链顶端Hash
    public static final byte[] MAIN_CHAIN_TIP_HASH_KEY = "MAIN_CHAIN_TIP_HASH_KEY".getBytes(StandardCharsets.UTF_8);


    //咖啡因交易缓存 缓存20000笔交易 查询时先查询缓存 缓存中没有再查询交易
    private final Cache<String, Transaction> TransactionCache = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .maximumSize(20000)
            .recordStats()
            .build();



    @PostConstruct
    public void init() {
        //检查创世区块 int转字节
        byte[] hash = dataBase.get(TableEnum.HEIGHT_TO_HASH, 0);
        if (hash==null){
            //初始化
            Block genesisBlock = createGenesisBlock();
            addBlockToChain(genesisBlock,true,true);
            log.info("新创建创世区块高度{} 最新Hash{}",genesisBlock.getHeight(),bytesToHex(genesisBlock.getHash()));
        }else {
            byte[] tipHash = dataBase.get(CHAIN, MAIN_CHAIN_TIP_HASH_KEY);
            byte[] tipHeight = dataBase.get(CHAIN, MAIN_CHAIN_TIP_HEIGHT_KEY);
            Block deserialize = getBlockByHash(tipHash);
            deserialize.setHeight(bytesToInt(tipHeight));
            deserialize.setHash(tipHash);
            mainTipBlock=deserialize;
            log.info("最新区块高度{} 最新Hash{}",deserialize.getHeight(),bytesToHex(tipHash));
        }
    }



    //孤块缓存: previousHash -> 等待处理的区块列表
    private final Cache<String, List<Block>> orphanBlocks = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .initialCapacity(100)
            .maximumSize(100)
            .recordStats()
            .build();

    //将区块加入到主链
    synchronized public void addBlockToChain(Block block,boolean isMainChain,boolean isTip) {
        List<RocksDb.DbOperation> operations = new ArrayList<>();
        BlockHeader blockHeader = block.getBlockHeader();
        BlockBody blockBody = block.getBlockBody();
        UInt256 chainWork = block.getChainWork();
        byte[] intHeightToBytes = ByteBuffer.allocate(4).putInt(block.getHeight()).array();

        operations.add(new RocksDb.DbOperation(
                TBLOCK_HEADER,
                block.getHash(),
                blockHeader.serialize(),
                RocksDb.DbOperation.OpType.INSERT
        ));
        operations.add(new RocksDb.DbOperation(
                TBLOCK_BODY,
                block.getHash(),
                blockBody.serialize(),
                RocksDb.DbOperation.OpType.INSERT
        ));
        operations.add(new RocksDb.DbOperation(
                HASH_TO_CHAIN_WORK,
                block.getHash(),
                chainWork.toBytes(),
                RocksDb.DbOperation.OpType.INSERT
        ));
        operations.add(new RocksDb.DbOperation(
                HASH_TO_HEIGHT,
                block.getHash(),
                intHeightToBytes,
                RocksDb.DbOperation.OpType.INSERT
        ));
        if (isMainChain){
            operations.add(new RocksDb.DbOperation(
                    TableEnum.HEIGHT_TO_HASH,
                    intHeightToBytes,
                    block.getHash(),
                    RocksDb.DbOperation.OpType.INSERT
            ));

            if (isTip){
                operations.add(new RocksDb.DbOperation(
                        TableEnum.CHAIN,
                        MAIN_CHAIN_TIP_HEIGHT_KEY,
                        intHeightToBytes,
                        RocksDb.DbOperation.OpType.INSERT
                ));
                operations.add(new RocksDb.DbOperation(
                        TableEnum.CHAIN,
                        MAIN_CHAIN_TIP_HASH_KEY,
                        block.getHash(),
                        RocksDb.DbOperation.OpType.INSERT
                ));
            }

            //花费的
            List<UTXO> spentUTXOKeys = block.getSpentUTXOKeys();
            for (UTXO utxo : spentUTXOKeys){
                operations.add(new RocksDb.DbOperation(
                        TableEnum.TUTXO,
                        utxo.getKey(),
                        null,
                        RocksDb.DbOperation.OpType.DELETE
                ));
                operations.add(new RocksDb.DbOperation(
                        ADDR_TO_UTXO,
                        utxo.getAddressKey(),
                        null,
                        RocksDb.DbOperation.OpType.DELETE
                ));
            }
            //新增的UTXO
            List<UTXO> utxOs = block.getUTXOs();
            for (UTXO utxo : utxOs){
                operations.add(new RocksDb.DbOperation(
                        TableEnum.TUTXO,
                        utxo.getKey(),
                        utxo.serialize(),
                        RocksDb.DbOperation.OpType.INSERT
                ));
                //地址到UTXO value是金额 帮助快速找到地址的UTXO
                operations.add(new RocksDb.DbOperation(
                        ADDR_TO_UTXO,
                        utxo.getAddressKey(),
                        longToLittleEndian(utxo.getValue()),
                        RocksDb.DbOperation.OpType.INSERT
                ));
            }
        }

        //交易到区块的索引
        List<Transaction> transactions = block.getTransactions();
        for (int i = 0; i < transactions.size(); i++) {
            Transaction transaction = transactions.get(i);
            boolean coinbase = transaction.isCoinbase();
            byte[] txId = transaction.getTxId();
            byte[] blockIndex = transaction.getBlockIndex(block.getHash(),i);
            operations.add(new RocksDb.DbOperation(
                    TableEnum.TX_TO_BLOCK,
                    txId,
                    blockIndex,
                    RocksDb.DbOperation.OpType.INSERT
            ));
            // 使用LinkedHashSet进行去重，并保留顺序
            Set<byte[]> seenScripts = new LinkedHashSet<>();
            List<TxInput> inputs = transaction.getInputs();
            if (!coinbase){
                for (TxInput input : inputs) {
                    byte[] scriptSig = input.getScriptSig();
                    log.info("解锁脚本长度{} 解锁脚本{}",scriptSig.length,bytesToHex(scriptSig));
                    //获取最后32位
                    byte[] last32 = Arrays.copyOfRange(scriptSig, scriptSig.length - 32, scriptSig.length);
                    byte[] scriptPubKey = Sha.applyRIPEMD160(Sha.applySHA256(last32));//解锁脚本
                    byte[] scriptPubKeyWithTxId = new byte[scriptPubKey.length + txId.length];
                    System.arraycopy(scriptPubKey, 0, scriptPubKeyWithTxId, 0, scriptPubKey.length);
                    System.arraycopy(txId, 0, scriptPubKeyWithTxId, scriptPubKey.length, txId.length);

                    if (seenScripts.add(scriptPubKey)) {
                        operations.add(new RocksDb.DbOperation(
                                TableEnum.ADDR_TO_TX,
                                scriptPubKeyWithTxId,
                                new byte[]{},
                                RocksDb.DbOperation.OpType.INSERT
                        ));
                    }
                }
            }
            //地址到交易的索引 获取输出列表所有的锁定脚本
            List<TxOutput> outputs = transaction.getOutputs();
            for (TxOutput output : outputs) {
                byte[] scriptPubKey = output.getScriptPubKey();//[20字节][32字节]
                //地址到交易的key 将scriptPubKey 和 txId 拼接为 52字节key
                byte[] scriptPubKeyWithTxId = new byte[scriptPubKey.length + txId.length];
                System.arraycopy(scriptPubKey, 0, scriptPubKeyWithTxId, 0, scriptPubKey.length);
                System.arraycopy(txId, 0, scriptPubKeyWithTxId, scriptPubKey.length, txId.length);
                // 如果add方法返回true，说明是首次添加，不是重复项
                if (seenScripts.add(scriptPubKey)) {
                    operations.add(new RocksDb.DbOperation(
                            TableEnum.ADDR_TO_TX,
                            scriptPubKeyWithTxId,
                            new byte[]{},
                            RocksDb.DbOperation.OpType.INSERT
                    ));
                }
            }
        }
        boolean success = dataBase.dataTransaction(operations);
        if (success) {
            System.out.println("事务成功：将区块加入到主链。");
            if (isTip){
                mainTipBlock=block;
            }
        } else {
            System.out.println("事务失败：未将区块加入到主链。");
        }
    }

    /**
     * 执行链重组（Reorganization）
     * 核心优化：所有数据库操作合并到同一个原子事务中执行，保证链重组的原子性
     */
    synchronized private boolean performChainReorg(Block newBlock) {
        // 全局事务操作列表：收集所有需要执行的数据库操作
        List<RocksDb.DbOperation> globalTxOps = new ArrayList<>();
        // 临时缓存：需要放回交易池的交易（旧链回滚）
        List<Transaction> txsToAddToPool = new ArrayList<>();
        // 临时缓存：需要从交易池移除的交易（新链应用）
        List<Transaction> txsToRemoveFromPool = new ArrayList<>();

        try {
            log.info("开始执行链重组，新链顶端区块高度:{} Hash:{}",
                    newBlock.getHeight(), bytesToHex(newBlock.getHash()));

            // 1. 找到新旧链的共同祖先
            Block commonAncestor = findCommonAncestor(newBlock);
            if (commonAncestor == null) {
                log.error("链重组失败：未找到共同祖先");
                return false;
            }
            log.info("找到共同祖先区块，高度:{} Hash:{}",
                    commonAncestor.getHeight(), bytesToHex(commonAncestor.getHash()));

            // 2. 收集旧链需要回滚的区块（从旧主链顶端到共同祖先，倒序）
            List<Block> oldChainBlocksToRevert = new ArrayList<>();
            Block currentOldBlock = mainTipBlock;
            while (currentOldBlock != null && !Arrays.equals(currentOldBlock.getHash(), commonAncestor.getHash())) {
                oldChainBlocksToRevert.add(currentOldBlock);
                currentOldBlock = getBlockByHash(currentOldBlock.getBlockHeader().getPreviousHash());
            }
            if (oldChainBlocksToRevert.isEmpty()) {
                log.warn("链重组无需回滚旧链，直接应用新链");
            } else {
                log.info("需要回滚旧链区块数量:{}，区块高度范围:{}~{}",
                        oldChainBlocksToRevert.size(),
                        commonAncestor.getHeight() + 1,
                        mainTipBlock.getHeight());
            }

            // 3. 收集新链需要应用的区块（从共同祖先下一个区块到新链顶端，正序）
            List<Block> newChainBlocksToApply = new ArrayList<>();
            Block currentNewBlock = newBlock;
            while (currentNewBlock != null && !Arrays.equals(currentNewBlock.getHash(), commonAncestor.getHash())) {
                newChainBlocksToApply.add(currentNewBlock);
                currentNewBlock = getBlockByHash(currentNewBlock.getBlockHeader().getPreviousHash());
            }
            // 反转列表，保证从共同祖先的下一个区块开始应用
            Collections.reverse(newChainBlocksToApply);
            log.info("需要应用新链区块数量:{}，区块高度范围:{}~{}",
                    newChainBlocksToApply.size(),
                    commonAncestor.getHeight() + 1,
                    newBlock.getHeight());

            // 4. 收集旧链回滚的数据库操作（倒序回滚，先回滚最新的区块）
            for (Block blockToRevert : oldChainBlocksToRevert) {
                log.info("收集旧链回滚操作，高度:{} Hash:{}",
                        blockToRevert.getHeight(), bytesToHex(blockToRevert.getHash()));
                Pair<List<RocksDb.DbOperation>, List<Transaction>> revertResult = collectRevertBlockOps(blockToRevert);
                if (revertResult == null) {
                    log.error("收集回滚操作失败，链重组终止：高度{} Hash{}",
                            blockToRevert.getHeight(), bytesToHex(blockToRevert.getHash()));
                    return false;
                }
                // 合并回滚操作到全局事务
                globalTxOps.addAll(revertResult.getFirst());
                // 收集需要放回交易池的交易
                txsToAddToPool.addAll(revertResult.getSecond());
            }

            // 5. 收集新链应用的数据库操作（正序应用，从共同祖先下一个区块开始）
            for (Block blockToApply : newChainBlocksToApply) {
                log.info("收集新链应用操作，高度:{} Hash:{}",
                        blockToApply.getHeight(), bytesToHex(blockToApply.getHash()));
                Pair<List<RocksDb.DbOperation>, List<Transaction>> applyResult = collectApplyBlockOps(blockToApply);
                if (applyResult == null) {
                    log.error("收集应用操作失败，链重组终止：高度{} Hash{}",
                            blockToApply.getHeight(), bytesToHex(blockToApply.getHash()));
                    return false;
                }
                // 合并应用操作到全局事务
                globalTxOps.addAll(applyResult.getFirst());
                // 收集需要从交易池移除的交易
                txsToRemoveFromPool.addAll(applyResult.getSecond());
            }

            // 6. 收集主链状态更新操作（最后执行）
            byte[] newHeightBytes = ByteBuffer.allocate(4).putInt(newBlock.getHeight()).array();
            // 更新主链顶端高度
            globalTxOps.add(new RocksDb.DbOperation(
                    TableEnum.CHAIN,
                    MAIN_CHAIN_TIP_HEIGHT_KEY,
                    newHeightBytes,
                    RocksDb.DbOperation.OpType.INSERT
            ));
            // 更新主链顶端哈希
            globalTxOps.add(new RocksDb.DbOperation(
                    TableEnum.CHAIN,
                    MAIN_CHAIN_TIP_HASH_KEY,
                    newBlock.getHash(),
                    RocksDb.DbOperation.OpType.INSERT
            ));
            // 更新高度到哈希的映射（新链的区块）
            for (Block block : newChainBlocksToApply) {
                byte[] heightBytes = ByteBuffer.allocate(4).putInt(block.getHeight()).array();
                globalTxOps.add(new RocksDb.DbOperation(
                        TableEnum.HEIGHT_TO_HASH,
                        heightBytes,
                        block.getHash(),
                        RocksDb.DbOperation.OpType.INSERT
                ));
            }

            // 7. 执行全局原子事务（所有操作要么全部成功，要么全部失败）
            log.info("执行链重组全局事务，总操作数:{}", globalTxOps.size());
            boolean txSuccess = dataBase.dataTransaction(globalTxOps);
            if (!txSuccess) {
                log.error("链重组全局事务执行失败，所有操作已回滚");
                return false;
            }

            // 8. 事务成功后，执行内存状态更新（仅此时修改内存，保证数据一致性）
            // 8.1 更新主链顶端内存状态
            mainTipBlock = newBlock;
            // 8.2 处理交易池：放回旧链回滚的交易
            for (Transaction tx : txsToAddToPool) {
                txPool.addTx(tx);
                log.debug("将回滚交易放回交易池，TxId:{}", bytesToHex(tx.getTxId()));
            }
            // 8.3 处理交易池：移除新链确认的交易
            for (Transaction tx : txsToRemoveFromPool) {
                //主链才有UTXO
                txPool.onBlockConfirmed(new Transaction[]{tx}, newBlock.getHeight());
                log.debug("从交易池移除已确认交易，TxId:{}", bytesToHex(tx.getTxId()));
            }

            // 9. 处理孤块（重组后可能有新的父区块可用）
            processOrphanBlocks();

            log.info("链重组完成，新主链顶端高度:{} Hash:{}，全局事务执行成功",
                    newBlock.getHeight(), bytesToHex(newBlock.getHash()));
            return true;
        } catch (Exception e) {
            log.error("链重组异常，全局事务未提交，无数据变更", e);
            return false;
        }
    }

    /**
     * 收集回滚区块的数据库操作（不执行事务，仅返回操作列表）
     * @param block 要回滚的区块
     * @return Pair<回滚操作列表, 需要放回交易池的交易列表>，失败返回null
     */
    private Pair<List<RocksDb.DbOperation>, List<Transaction>> collectRevertBlockOps(Block block) {
        if (block == null) {
            log.error("收集回滚操作失败：区块为空");
            return null;
        }

        List<RocksDb.DbOperation> revertOps = new ArrayList<>();
        List<Transaction> txsToAddToPool = new ArrayList<>();
        List<Transaction> transactions = block.getTransactions();
        byte[] intHeightToBytes = ByteBuffer.allocate(4).putInt(block.getHeight()).array();

        try {
            //区块从主链剥离
            revertOps.add(new RocksDb.DbOperation(
                    TableEnum.HEIGHT_TO_HASH,
                    intHeightToBytes,
                    null,
                    RocksDb.DbOperation.OpType.DELETE
            ));
            //撤销区块交易
            for (Transaction tx : transactions) {
                if (tx.isCoinbase()) {
                    // 1. 收集Coinbase交易回滚操作：删除其创建的UTXO
                    List<TxOutput> outputs = tx.getOutputs();
                    for (int outputIndex = 0; outputIndex < outputs.size(); outputIndex++) {
                        TxOutput output = outputs.get(outputIndex);
                        byte[] utxoKey = UTXO.getKey(tx.getTxId(), outputIndex);
                        // 从TUTXO表删除该UTXO
                        revertOps.add(new RocksDb.DbOperation(
                                TableEnum.TUTXO,
                                utxoKey,
                                null,
                                RocksDb.DbOperation.OpType.DELETE
                        ));
                        // 修复：ADDR_TO_UTXO的key = 地址前缀 + UTXO key
                        byte[] addrKey = output.getScriptPubKey();
                        byte[] addrUtxoKey = Bytes.concat(addrKey, utxoKey);
                        revertOps.add(new RocksDb.DbOperation(
                                TableEnum.ADDR_TO_UTXO,
                                addrUtxoKey,
                                null,
                                RocksDb.DbOperation.OpType.DELETE
                        ));
                    }
                    log.debug("收集Coinbase交易回滚操作，TxId:{}", bytesToHex(tx.getTxId()));
                } else {
                    // 2. 收集普通交易回滚操作：恢复被花费的UTXO，删除创建的UTXO
                    // 2.1 恢复该交易花费的UTXO
                    List<TxInput> inputs = tx.getInputs();
                    for (TxInput input : inputs) {
                        byte[] txId = input.getTxId();
                        int index = input.getIndex();
                        Transaction txByHash = getTxByHash(txId);
                        TxOutput txOutput = txByHash.getOutputs().get(index);
                        UTXO utxo = new UTXO();
                        utxo.setTxId(txId);
                        utxo.setIndex(index);
                        utxo.setValue(txOutput.getValue());
                        utxo.setScript(txOutput.getScriptPubKey());
                        utxo.setHeight(block.getHeight());
                        utxo.setCoinbase(false);
                        revertOps.add(new RocksDb.DbOperation(
                                TableEnum.TUTXO,
                                utxo.getKey(),
                                utxo.serialize(),
                                RocksDb.DbOperation.OpType.INSERT
                        ));
                        // 修复：ADDR_TO_UTXO的key = 地址前缀 + UTXO key，value = 小端序的金额
                        byte[] addrKey = utxo.getAddressKey();
                        byte[] addrUtxoKey = Bytes.concat(addrKey, utxo.getKey());
                        revertOps.add(new RocksDb.DbOperation(
                                TableEnum.ADDR_TO_UTXO,
                                addrUtxoKey,
                                longToLittleEndian(utxo.getValue()),
                                RocksDb.DbOperation.OpType.INSERT
                        ));
                    }

                    // 2.2 删除该交易创建的UTXO
                    List<TxOutput> outputs = tx.getOutputs();
                    for (int outputIndex = 0; outputIndex < outputs.size(); outputIndex++) {
                        byte[] utxoKey = UTXO.getKey(tx.getTxId(), outputIndex);
                        revertOps.add(new RocksDb.DbOperation(
                                TableEnum.TUTXO,
                                utxoKey,
                                null,
                                RocksDb.DbOperation.OpType.DELETE
                        ));
                        // 删除ADDR_TO_UTXO映射
                        TxOutput output = outputs.get(outputIndex);
                        byte[] addrKey = output.getScriptPubKey();
                        byte[] addrUtxoKey = Bytes.concat(addrKey, utxoKey);
                        revertOps.add(new RocksDb.DbOperation(
                                TableEnum.ADDR_TO_UTXO,
                                addrUtxoKey,
                                null,
                                RocksDb.DbOperation.OpType.DELETE
                        ));
                    }

                    // 2.3 收集需要放回交易池的交易（事务成功后执行）
                    txsToAddToPool.add(tx);
                    log.debug("收集普通交易回滚操作，TxId:{}（需放回交易池）", bytesToHex(tx.getTxId()));
                }
            }

            return new Pair<>(revertOps, txsToAddToPool);
        } catch (Exception e) {
            log.error("收集回滚操作异常，区块高度:{} Hash:{}", block.getHeight(), bytesToHex(block.getHash()), e);
            return null;
        }
    }

    /**
     * 收集应用区块的数据库操作（不执行事务，仅返回操作列表）
     * @param block 要应用的区块
     * @return Pair<应用操作列表, 需要从交易池移除的交易列表>，失败返回null
     */
    private Pair<List<RocksDb.DbOperation>, List<Transaction>> collectApplyBlockOps(Block block) {
        if (block == null) {
            log.error("收集应用操作失败：区块为空");
            return null;
        }
        byte[] intHeightToBytes = ByteBuffer.allocate(4).putInt(block.getHeight()).array();

        List<RocksDb.DbOperation> applyOps = new ArrayList<>();
        List<Transaction> txsToRemoveFromPool = new ArrayList<>();
        List<Transaction> transactions = block.getTransactions();

        try {
            //添加到主链
            applyOps.add(new RocksDb.DbOperation(
                    TableEnum.HEIGHT_TO_HASH,
                    intHeightToBytes,
                    block.getHash(),
                    RocksDb.DbOperation.OpType.INSERT
            ));

            for (Transaction tx : transactions) {
                if (tx.isCoinbase()) {
                    // 1. 收集Coinbase交易应用操作：创建挖矿奖励的UTXO
                    List<TxOutput> outputs = tx.getOutputs();
                    for (int outputIndex = 0; outputIndex < outputs.size(); outputIndex++) {
                        TxOutput output = outputs.get(outputIndex);
                        // 创建UTXO对象
                        UTXO utxo = new UTXO();
                        utxo.setTxId(tx.getTxId());
                        utxo.setIndex(outputIndex);
                        utxo.setValue(output.getValue());
                        utxo.setScript(output.getScriptPubKey());
                        utxo.setCoinbase(true);

                        // 插入UTXO到数据库
                        byte[] utxoKey = UTXO.getKey(tx.getTxId(), outputIndex);
                        applyOps.add(new RocksDb.DbOperation(
                                TableEnum.TUTXO,
                                utxoKey,
                                utxo.serialize(),
                                RocksDb.DbOperation.OpType.INSERT
                        ));
                        // 更新ADDR_TO_UTXO映射
                        applyOps.add(new RocksDb.DbOperation(
                                TableEnum.ADDR_TO_UTXO,
                                utxo.getAddressKey(),
                                longToLittleEndian(utxo.getValue()),
                                RocksDb.DbOperation.OpType.INSERT
                        ));
                    }
                    log.debug("收集Coinbase交易应用操作，TxId:{}", bytesToHex(tx.getTxId()));
                } else {
                    // 2. 收集普通交易应用操作：花费旧UTXO，创建新UTXO
                    // 2.1 花费该交易的输入对应的UTXO
                    List<TxInput> inputs = tx.getInputs();
                    for (TxInput input : inputs) {
                        byte[] spentTxId = input.getTxId();
                        int spentIndex = input.getIndex();
                        byte[] spentUtxoKey = UTXO.getKey(spentTxId, spentIndex);
                        // 删除被花费的UTXO
                        applyOps.add(new RocksDb.DbOperation(
                                TableEnum.TUTXO,
                                spentUtxoKey,
                                null,
                                RocksDb.DbOperation.OpType.DELETE
                        ));
                        // 删除ADDR_TO_UTXO映射
                        UTXO spentUtxo = getUtxo(bytesToHex(spentUtxoKey));
                        if (spentUtxo != null) {
                            applyOps.add(new RocksDb.DbOperation(
                                    TableEnum.ADDR_TO_UTXO,
                                    spentUtxo.getAddressKey(),
                                    null,
                                    RocksDb.DbOperation.OpType.DELETE
                            ));
                        }
                    }

                    // 2.2 创建该交易输出对应的UTXO
                    List<TxOutput> outputs = tx.getOutputs();
                    for (int outputIndex = 0; outputIndex < outputs.size(); outputIndex++) {
                        TxOutput output = outputs.get(outputIndex);
                        // 创建UTXO对象
                        UTXO utxo = new UTXO();
                        utxo.setTxId(tx.getTxId());
                        utxo.setIndex(outputIndex);
                        utxo.setValue(output.getValue());
                        utxo.setScript(output.getScriptPubKey());
                        utxo.setCoinbase(false);
                        utxo.setHeight(block.getHeight());

                        // 插入UTXO到数据库
                        byte[] utxoKey = UTXO.getKey(tx.getTxId(), outputIndex);
                        applyOps.add(new RocksDb.DbOperation(
                                TableEnum.TUTXO,
                                utxoKey,
                                utxo.serialize(),
                                RocksDb.DbOperation.OpType.INSERT
                        ));
                        // 更新ADDR_TO_UTXO映射
                        applyOps.add(new RocksDb.DbOperation(
                                TableEnum.ADDR_TO_UTXO,
                                utxo.getAddressKey(),
                                longToLittleEndian(utxo.getValue()),
                                RocksDb.DbOperation.OpType.INSERT
                        ));
                    }

                    // 2.3 收集需要从交易池移除的交易（事务成功后执行）
                    txsToRemoveFromPool.add(tx);
                    log.debug("收集普通交易应用操作，TxId:{}（需从交易池移除）", bytesToHex(tx.getTxId()));
                }
            }

            return new Pair<>(applyOps, txsToRemoveFromPool);
        } catch (Exception e) {
            log.error("收集应用操作异常，区块高度:{} Hash:{}", block.getHeight(), bytesToHex(block.getHash()), e);
            return null;
        }
    }


    //验证区块
    synchronized public boolean verifyBlock(Block block) {
        if (block == null) {
            log.error("验证失败：区块为空");
            return false;
        }

        log.info("开始验证区块 高度:{} 区块哈希:{}",block.getHeight(),bytesToHex(block.getHash()));

        // 1. 区块基础格式验证
        if (!validateBlockFormat(block)) {
            log.error("区块基础格式验证失败");
            return false;
        }

        // 2. 区块 Hash 验证
        if (!validateBlockHash(block)) {
            log.error("区块Hash验证失败");
            return false;
        }

        // 3. 工作量证明验证
        if (!validateProofOfWork(block)) {
            log.error("工作量证明验证失败");
            return false;
        }

        //验证是否已经存在
        if (getBlockHeaderByHash(block.getHash()) != null) {
            log.error("区块已经存在");
            return true;
        }


        // 4. 区块交易验证
        if (!validateBlockTransactions(block)) {
            log.error("区块交易验证失败");
            return false;
        }

        // 5. 链接关系处理（主链延续、孤块、分叉）
        if (handleBlockConnection(block)){
            routingTable.BroadcastResource(0,block.getHash());
            return true;
        }else {
            //打包失败将交易重新添加回交易池
            txPool.onBlockFailed(block.getTransactions().toArray(new Transaction[0]));
            return false;
        }
    }

    /**
     * 区块基础格式验证
     */
    synchronized private boolean validateBlockFormat(Block block) {
        // 验证区块头不为空
        if (block.getBlockHeader() == null) {
            log.error("区块头为空");
            return false;
        }

        // 验证交易列表不为空
        if (block.getTransactions() == null || block.getTransactions().isEmpty()) {
            log.error("区块必须包含至少一笔交易");
            return false;
        }

        // 验证第一笔交易是 Coinbase 交易
        if (!block.getTransactions().getFirst().isCoinbase()) {
            log.error("区块的第一笔交易必须是Coinbase交易");
            return false;
        }

        // 验证其余交易不是 Coinbase 交易
        for (int i = 1; i < block.getTransactions().size(); i++) {
            if (block.getTransactions().get(i).isCoinbase()) {
                log.error("区块中只能有一笔Coinbase交易（必须是第一笔）");
                return false;
            }
        }

        // 验证交易数量与txCount字段一致
        if (block.getTxCount() != block.getTransactions().size()) {
            log.error("交易数量不匹配，txCount:{}, 实际:{}",
                block.getTxCount(), block.getTransactions().size());
            return false;
        }

        // 验证区块高度合理
        if (block.getHeight() < 0) {
            log.error("区块高度不能为负数: {}", block.getHeight());
            return false;
        }

        log.info("区块基础格式验证通过");
        return true;
    }

    /**
     * 区块 Hash 验证
     */
    synchronized private boolean validateBlockHash(Block block) {
        byte[] blockHash = block.getHash();

        // 验证 Hash 不为空且长度为32字节
        if (blockHash == null || blockHash.length != 32) {
            log.error("区块Hash长度必须为32字节");
            return false;
        }

        // 验证存储的Hash与计算的Hash一致
        byte[] calculatedHash = block.getBlockHeader().calculateHash();
        if (!Arrays.equals(blockHash, calculatedHash)) {
            log.error("区块Hash不一致，存储的:{} 计算的:{}",
                bytesToHex(blockHash), bytesToHex(calculatedHash));
            return false;
        }

        // 验证默克尔根
        byte[] calculatedMerkleRoot = calculateMerkleRoot(block.getTransactions());
        if (!Arrays.equals(block.getBlockHeader().getMerkleRoot(), calculatedMerkleRoot)) {
            log.error("默克尔根不匹配，存储的:{} 计算的:{}",
                bytesToHex(block.getBlockHeader().getMerkleRoot()),
                bytesToHex(calculatedMerkleRoot));
            return false;
        }

        log.info("区块Hash验证通过");
        return true;
    }

    /**
     * 工作量证明验证
     */
    private boolean validateProofOfWork(Block block) {
        BlockHeader header = block.getBlockHeader();

        // 验证工作量证明
        if (!header.verifyProofOfWork(false)) {
            log.error("工作量证明验证失败");
            return false;
        }

        // 验证时间戳合理性（不能太超前于当前时间）
        long currentTime = System.currentTimeMillis() / 1000;
        if (header.getTime() > currentTime + 7200) { // 允许2小时的未来时间
            log.error("区块时间戳过于超前: {} vs 当前时间: {}", header.getTime(), currentTime);
            return false;
        }

        // 验证时间戳不小于中位时间（如果前区块存在）
        if (mainTipBlock != null && mainTipBlock.getMedianTime() > 0) {
            if (header.getTime() < mainTipBlock.getMedianTime()) {
                log.error("区块时间戳小于前区块的中位时间: {} < {}",
                    header.getTime(), mainTipBlock.getMedianTime());
                return false;
            }
        }

        log.info("工作量证明验证通过");
        return true;
    }

    /**
     * 区块交易验证
     */
    private boolean validateBlockTransactions(Block block) {
        ParamsConfig.Params params = config.getParams();
        int coinbaseMaturity = params.getCoinbaseMaturity();
        List<Transaction> transactions = block.getTransactions();
        // 用于存储所有非Coinbase交易的总输入/输出（准确计算手续费）
        long totalNormalTxInput = 0;
        long totalNormalTxOutput = 0;
        int coinbaseCount = 0;

        // 用于检测交易重复
        Set<String> txIdSet = new HashSet<>();

        for (int i = 0; i < transactions.size(); i++) {
            Transaction tx = transactions.get(i);

            // 验证交易ID唯一性
            String txIdHex = bytesToHex(tx.getTxId());
            if (txIdSet.contains(txIdHex)) {
                log.error("区块中存在重复的交易ID: {}", txIdHex);
                return false;
            }
            txIdSet.add(txIdHex);

            if (tx.isCoinbase()) {
                coinbaseCount++;

                // Coinbase 交易验证
                // 1. 验证只有1个输入
                if (tx.getInputs().size() != 1) {
                    log.error("Coinbase交易必须有且仅有一个输入");
                    return false;
                }

                // 2. 验证输入的txId为全0且index为0
                TxInput coinbaseInput = tx.getInputs().getFirst();
                if (!Arrays.equals(coinbaseInput.getTxId(), new byte[32]) || coinbaseInput.getIndex() != 0) {
                    log.error("Coinbase交易的输入格式不正确");
                    return false;
                }

            } else {
                // 普通交易验证

                // 1. 为交易准备UTXO视图
                Map<String, UTXO> utxoMap = getUTXOsByTx(tx);
                tx.setUtxoMap(utxoMap);

                // 2. 验证交易签名和逻辑
                if (!tx.validate()) {
                    log.error("区块中第{}笔交易验证失败", i);
                    return false;
                }

                // 3. 检查是否有双花（交易输入是否已被本区块其他交易花费）
                List<String> spendingKeys = tx.getSpendingUTXOKeyList();
                if (spendingKeys != null) {
                    for (String spendingKey : spendingKeys) {
                        // 检查本区块前面的交易是否已经花费了这个UTXO
                        for (int j = 0; j < i; j++) {
                            Transaction prevTx = transactions.get(j);
                            if (!prevTx.isCoinbase()) {
                                List<String> prevSpendingKeys = prevTx.getSpendingUTXOKeyList();
                                if (prevSpendingKeys != null && prevSpendingKeys.contains(spendingKey)) {
                                    log.error("检测到双花：UTXO {} 被多个交易花费", spendingKey);
                                    return false;
                                }
                            }
                        }
                    }
                }

                // 4. 累加普通交易的输入/输出金额（用于计算总手续费）
                long singleTxInput = 0;
                long singleTxOutput = 0;
                for (TxInput input : tx.getInputs()) {
                    UTXO utxo = utxoMap.get(bytesToHex(UTXO.getKey(input.getTxId(), input.getIndex())));
                    if (utxo != null) {
                        // 验证Coinbase UTXO是否成熟
                        if (utxo.isCoinbase()) {
                            int height = utxo.getHeight();
                            int mainHeight = mainTipBlock.getHeight();
                            // 计算确认数：当前主链高度 - UTXO所在区块高度 + 1
                            int confirmations = mainHeight - height + 1;
                            boolean isMature = confirmations > coinbaseMaturity;
                            if (!isMature) {
                                log.error("使用了未成熟的Coinbase UTXO，UTXO高度:{}，当前高度:{}，确认数:{}，要求成熟度:{}",
                                        height, mainHeight, confirmations, coinbaseMaturity);
                                return false;
                            }
                        }
                        singleTxInput += utxo.getValue();
                    } else {
                        log.error("输入引用的UTXO不存在，txId:{}，index:{}",
                                bytesToHex(input.getTxId()), input.getIndex());
                        return false;
                    }
                }

                for (TxOutput output : tx.getOutputs()) {
                    singleTxOutput += output.getValue();
                }

                // 验证单笔交易金额平衡（输入 >= 输出，手续费不能为负）
                if (singleTxInput < singleTxOutput) {
                    log.error("普通交易金额不平衡，输入:{}，输出:{}，txId:{}",
                            singleTxInput, singleTxOutput, txIdHex);
                    return false;
                }

                // 累加到全局普通交易输入/输出
                totalNormalTxInput += singleTxInput;
                totalNormalTxOutput += singleTxOutput;
            }
        }

        // 验证Coinbase交易数量
        if (coinbaseCount != 1) {
            log.error("区块中必须有且仅有一笔Coinbase交易，实际数量: {}", coinbaseCount);
            return false;
        }

        // 验证Coinbase交易位置（必须是第一个交易）
        Transaction coinbaseTx = transactions.getFirst();
        if (!coinbaseTx.isCoinbase()) {
            log.error("Coinbase交易必须是区块中的第一笔交易");
            return false;
        }

        // 1. 重新计算总手续费：所有普通交易的（输入总和 - 输出总和）（核心修复点）
        long totalFee = totalNormalTxInput - totalNormalTxOutput;
        if (totalFee < 0) {
            log.error("总手续费为负数，普通交易输入总和:{}，输出总和:{}", totalNormalTxInput, totalNormalTxOutput);
            return false;
        }

        // 2. 计算基础挖矿奖励
        long coinbaseReward = calculateCoinbaseReward(block.getHeight());
        // 3. 计算允许的Coinbase最大输出金额（基础奖励 + 总手续费）
        long expectedCoinbaseOutput = coinbaseReward + totalFee;

        // 4. 计算Coinbase交易的实际输出金额
        long coinbaseOutputValue = coinbaseTx.getOutputs().stream()
                .mapToLong(TxOutput::getValue)
                .sum();

        // 5. 验证Coinbase输出金额（核心修复：使用准确的totalFee计算允许值）
        if (coinbaseOutputValue > expectedCoinbaseOutput) {
            log.error("Coinbase输出金额过大，允许值(基础奖励{} + 总手续费{})={}, 实际值:{}",
                    coinbaseReward, totalFee, expectedCoinbaseOutput, coinbaseOutputValue);
            return false;
        }

        if (coinbaseOutputValue < expectedCoinbaseOutput) {
            log.warn("Coinbase输出金额小于允许值，允许值:{}，实际值:{} (矿工未完全收取手续费)",
                    expectedCoinbaseOutput, coinbaseOutputValue);
        }

        log.info("区块交易验证通过，基础挖矿奖励: {}，总手续费: {}，Coinbase实际输出: {}",
                coinbaseReward, totalFee, coinbaseOutputValue);
        return true;
    }

    /**
     * 计算Coinbase奖励（区块奖励）
     */
    private long calculateCoinbaseReward(int blockHeight) {
        // 初始奖励: 50 BTC = 50 * 100,000,000 satoshi
        long initialReward = 50L * 100000000;
        // 每减半周期的区块数
        int halvingInterval = config.getParams().getSubsidyReductionInterval();
        // 计算已经发生的减半次数
        int halvings = blockHeight / halvingInterval;
        // 最大减半次数（防止整数溢出）
        if (halvings > 63) {
            return 0;
        }
        // 计算当前奖励
        return initialReward >> halvings;
    }

    /**
     * 处理区块链接关系（主链延续、孤块、分叉） 不处理高度为零的区块
     */
    synchronized private boolean handleBlockConnection(Block block) {
        if (block.getHeight()==0){
            return false;
        }else {
            byte[] previousHash = block.getBlockHeader().getPreviousHash();

            // 检查是否为孤块（父区块不存在）
            byte[] parentBlockBytes = dataBase.get(TBLOCK_HEADER, previousHash);
            if (parentBlockBytes == null) {
                log.warn("孤块：父区块不存在，等待父区块到达。父区块Hash: {}",
                        bytesToHex(previousHash));
                addOrphanBlock(block);
                return false;
            }

            // 获取父区块工作总量
            Block parentBlock = getBlockByHash(previousHash);
            int height = parentBlock.getHeight();
            UInt256 previousChainWork = parentBlock.getChainWork();
            UInt256 newBlockChainWork = previousChainWork.add(UInt256.fromBigInteger(block.getBlockHeader().getWork()));
            block.setChainWork(newBlockChainWork);
            block.setHeight(height+1);

            // 检查是否为主链的延续
            if (Arrays.equals(previousHash, mainTipBlock.getHash())) {
                log.info("是主链的延续，高度:{} -> {}", mainTipBlock.getHeight(), block.getHeight());
                // 将区块加入到主链
                addBlockToChain(block, true,true);
                // 上链成功后，从交易池删除已确认的交易
                removeConfirmedTxsFromPool(block);
                // 尝试处理孤块
                processOrphanBlocks();
                return true;
            }
            // 计算当前主链的链工作量
            UInt256 mainChainWork = mainTipBlock.getChainWork();
            log.info("分叉区块 - 新链工作量:{} 主链工作量:{}", newBlockChainWork, mainChainWork);
            // 比较工作量，决定是否进行重组
            int i = newBlockChainWork.compareTo(mainChainWork);
            addBlockToChain(block, false,false);
            if (i > 0) {
                log.info("新链工作量更大，执行链重组");
                // 将区块加入到侧链（不更新UTXO，但存储区块数据）
                return performChainReorg(block);
            } else {
                log.info("新链工作量较小，暂时作为侧链区块");
                // 将区块加入到侧链（不更新UTXO，但存储区块数据）
                return true;
            }
        }
    }




    public Transaction[] getRecentTxByAddress(String address) {
        Transaction[] txsByAddress = txPool.getTxsByAddress(address);
        //反向查询地址的交易




        return null;
    }

    public List<Block> getOrphanBlocks() {
        return orphanBlocks.asMap().values().stream()
                .flatMap(List::stream)
                .toList();
    }


    // 辅助类：用于返回多值（可替换为Apache Commons Pair或自定义类）
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
        int mainChainHeight = mainTipBlock.getHeight();
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
        return dataBase.get(HEIGHT_TO_HASH,height);
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
        if (hash == null || hash.length != 32) {
            log.error("区块哈希无效：hash为null或长度不是32字节");
            return -1;
        }
        byte[] heightBytes = dataBase.get(HASH_TO_HEIGHT, hash);
        // 新增null校验
        if (heightBytes == null) {
            log.error("区块哈希在HASH_TO_HEIGHT表中无对应高度，hash={}", bytesToHex(hash));
            return -1;
        }
        return RocksDb.bytesToInt(dataBase.get(HASH_TO_HEIGHT, hash));
    }


    private void addOrphanBlock(Block block) {
        if (block == null || block.getBlockHeader() == null) {
            log.error("添加孤块失败：区块或区块头为空");
            return;
        }

        // 获取父区块Hash的十六进制字符串作为缓存key
        String parentHashHex = bytesToHex(block.getBlockHeader().getPreviousHash());
        if (parentHashHex.isEmpty()) {
            log.error("添加孤块失败：父区块Hash为空");
            return;
        }

        // 兼容低版本 Caffeine：手动实现 computeIfAbsent 逻辑（线程安全）
        List<Block> orphanList = orphanBlocks.getIfPresent(parentHashHex);
        if (orphanList == null) {
            // 双重检查锁，确保原子性创建空列表
            synchronized (orphanBlocks) {
                orphanList = orphanBlocks.getIfPresent(parentHashHex);
                if (orphanList == null) {
                    orphanList = new ArrayList<>();
                    orphanBlocks.put(parentHashHex, orphanList);
                }
            }
        }

        // 向列表中添加孤块（列表本身需保证线程安全，这里用同步块）
        synchronized (orphanList) {
            orphanList.add(block);
        }
    }



    /**
     * 处理孤块（当有新区块加入主链后，检查是否有孤块可以被处理）
     */
    private void processOrphanBlocks() {
        if (mainTipBlock == null) {
            log.warn("处理孤块失败：主链最新区块为空");
            return;
        }

        // 获取主链最新区块Hash的十六进制字符串（作为孤块的父区块key）
        String tipHashHex = bytesToHex(mainTipBlock.getHash());
        // 从缓存中获取该key对应的所有孤块
        List<Block> orphansToProcess = orphanBlocks.getIfPresent(tipHashHex);

        if (orphansToProcess == null || orphansToProcess.isEmpty()) {
            log.debug("无待处理的孤块（父区块Hash: {}）", tipHashHex);
            return;
        }

        log.info("找到{}个待处理的孤块（父区块已上链：{}）", orphansToProcess.size(), tipHashHex);

        // 拷贝列表避免遍历中修改，同时加同步锁
        List<Block> processList;
        synchronized (orphansToProcess) {
            processList = new ArrayList<>(orphansToProcess);
            orphansToProcess.clear(); // 清空原列表，避免重复处理
        }

        // 遍历处理每个孤块
        for (Block orphanBlock : processList) {
            try {
                log.info("开始处理孤块，高度: {}, Hash: {}",
                        orphanBlock.getHeight(), bytesToHex(orphanBlock.getHash()));

                // 验证孤块（验证通过则自动加入主链/侧链）
                boolean verifyResult = verifyBlock(orphanBlock);
                if (verifyResult) {
                    log.info("孤块处理成功，高度: {}, Hash: {}",
                            orphanBlock.getHeight(), bytesToHex(orphanBlock.getHash()));
                } else {
                    log.error("孤块处理失败，高度: {}, Hash: {}",
                            orphanBlock.getHeight(), bytesToHex(orphanBlock.getHash()));
                }
            } catch (Exception e) {
                log.error("处理孤块异常，Hash: {}", bytesToHex(orphanBlock.getHash()), e);
            }
        }

        // 如果处理后列表为空，清理缓存key（减少冗余）
        synchronized (orphansToProcess) {
            if (orphansToProcess.isEmpty()) {
                orphanBlocks.invalidate(tipHashHex);
                log.debug("清理空的孤块缓存key: {}", tipHashHex);
            }
        }
    }


    /**
     * 从交易池删除已上链的交易
     */
    private void removeConfirmedTxsFromPool(Block block) {
        List<Transaction> transactions = block.getTransactions();
        if (transactions == null || transactions.isEmpty()) {
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
        if (!confirmedTxs.isEmpty()) {
            txPool.onBlockConfirmed(confirmedTxs.toArray(new Transaction[0]),block.getHeight());
        }
    }





    public Block getMainLatestBlock() {
        return mainTipBlock;
    }





    public static Block createGenesisBlock() {
        Transaction coinbaseTx = createGenesisCoinbaseTransaction();
        List<Transaction> transactions = new ArrayList<>();
        transactions.add(coinbaseTx);
        byte[] merkleRoot = calculateMerkleRoot(transactions);
        Block genesisBlock = new Block();
        genesisBlock.setHeight(0); // 创世区块高度为0
        BlockHeader blockHeader = new BlockHeader(1, new byte[32], merkleRoot, 1754287472, convertTargetToBits(new BigInteger(BlockHeader.INIT_DIFFICULTY_TARGET_HEX, 16)), 29196);
        genesisBlock.setBlockHeader(blockHeader);
        genesisBlock.setTxCount(1);
        genesisBlock.setTransactions(transactions);
        genesisBlock.setHash(blockHeader.calculateHash());
        genesisBlock.setChainWork(UInt256.fromBigInteger(blockHeader.getWork()));
        return genesisBlock;
    }

    public static Transaction createCoinBaseTransaction(String minerAddress, long blockHeight, long fee) {
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
        output.setValue((50L * 100000000)+fee); // 50 BTC in satoshi
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
        byte[] bytes = "The Times 03/Jan/2009 Chancellor on brink of second bailout for banks".getBytes(StandardCharsets.UTF_8);//69字节
        input.setScriptSig(bytes);
        List<TxInput> inputs = new ArrayList<>();
        inputs.add(input);
        coinbaseTx.setVersion(0);
        coinbaseTx.setInputs(inputs);
        // 创建输出（初始奖励50 BTC = 50*1e8聪）
        TxOutput output = new TxOutput();
        output.setValue(50L * 100000000); // 50 BTC in satoshi
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
            totalFee += transaction.getFee();
        }
        return totalFee;
    }






    public Block getBlockByHash(byte[] hash) {
        //获取区块头 区块体
        byte[] header_bytes = dataBase.get(TBLOCK_HEADER,hash);
        byte[] body_bytes = dataBase.get(TBLOCK_BODY,hash);
        byte[] chainwork_bytes = dataBase.get(HASH_TO_CHAIN_WORK, hash);
        UInt256 uInt256 = UInt256.fromBytes(chainwork_bytes);
        if (header_bytes!=null && body_bytes!=null){
            BlockHeader blockHeader = BlockHeader.deserialize(header_bytes);
            BlockBody blockBody = BlockBody.deserialize(body_bytes);
            Block block = Block.buildBlock(blockHeader, blockBody);
            block.setChainWork(uInt256);
            return block;
        }else {
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
        //先查询是否在交易池中
        Transaction tx = txPool.getTx(txId);
        if (tx!=null){
            return tx;
        }
        Transaction ifPresent = TransactionCache.getIfPresent(bytesToHex(txId));
        if (ifPresent!=null){
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
        assert deserialize != null;
        Transaction transaction = deserialize.getTransactions().get(txIndexInBlock);
        long status = 0;
        //交易所在的区块是否主连区块
        boolean isMainBlock = isMainBlock(blockHash);
        log.info("当前交易是否主链交易{}",isMainBlock);
        if (isMainBlock){
            status = TransactionStatusResolver.setLifecycleStatus(status,TransactionStatusResolver.CONFIRMED);
        }else {
            status = TransactionStatusResolver.setLifecycleStatus(status,TransactionStatusResolver.FORK);
        }
        transaction.setStatus(status);
        TransactionCache.put(bytesToHex(txId),transaction);
        return transaction;
    }


    public Transaction getTxAndQueryInputByHash(byte[] txId) {
        //先查询是否在交易池中
        Transaction tx = txPool.getTx(txId);
        if (tx!=null){
            //带出输入引用的输出
            Map<String, UTXO> utxoMap = tx.getUtxoMap();
            List<TxInput> inputs = tx.getInputs();
            for (TxInput input : inputs){
                int index = input.getIndex();
                byte[] txId1 = input.getTxId();
                UTXO utxo = utxoMap.get(UTXO.getKey(txId1, index));
                if (utxo!=null){
                    TxOutput txOutput = new TxOutput();
                    txOutput.setValue(utxo.getValue());
                    txOutput.setScriptPubKey(utxo.getScript());
                    input.setOutput(txOutput);
                }
            }
            tx.setInputs(inputs);
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
        assert deserialize != null;
        Transaction transaction = deserialize.getTransactions().get(txIndexInBlock);
        //带出输入对应的输出
        List<TxInput> inputs = transaction.getInputs();
        for (TxInput txInput:inputs){
            if (!txInput.isCoinbase() && deserialize.getHeight()!=0){
                Transaction txByHash = getTxByHash(txInput.getTxId());
                TxOutput txOutput = txByHash.getOutputs().get(txInput.getIndex());
                txInput.setOutput(txOutput);
            }
        }
        transaction.setInputs(inputs);
        long status = 0;
        //交易所在的区块是否主连区块
        boolean isMainBlock = isMainBlock(blockHash);
        log.info("当前交易是否主链交易{}",isMainBlock);
        if (isMainBlock){
            status = TransactionStatusResolver.setLifecycleStatus(status,TransactionStatusResolver.CONFIRMED);
        }else {
            status = TransactionStatusResolver.setLifecycleStatus(status,TransactionStatusResolver.FORK);
        }
        transaction.setStatus(status);
        transaction.setTime(blockHeader.getTime());
        transaction.setHeight(deserialize.getHeight());
        transaction.setHash(blockHeader.calculateHash());
        return transaction;
    }



    //获取一笔交易所有参与交易待花费的UTXO
    public Map<String,UTXO> getUTXOsByTx(Transaction transaction) {
        List<TxInput> inputs = transaction.getInputs();
        Map<String,UTXO> utxos = new HashMap<>();
        for (TxInput txInput:inputs){
            byte[] txId = txInput.getTxId();
            int index = txInput.getIndex();
            //交易ID是全零且index=0 则是一个铸币输入
            if (Arrays.equals(txId, new byte[32]) && index == 0) {
                log.info("这是一个铸币输入");
            }else {
                byte[] key = UTXO.getKey(txId, index);
                byte[] bytes = dataBase.get(TableEnum.TUTXO, key);
                assert bytes != null;
                UTXO deserialize = UTXO.deserialize(bytes);
                utxos.put(bytesToHex(key),deserialize);
            }
        }
        return utxos;
    }


    public BlockHeader getBlockHeaderByHash(byte[] hash) {
        byte[] bytes = dataBase.get(TBLOCK_HEADER,hash);
        if (bytes!=null){
            //获取前80字节
            byte[] headerBytes = Arrays.copyOfRange(bytes, 0, 80);
            //序列化
            return BlockHeader.deserialize(headerBytes);
        }else {
            return null;
        }
    }

    public Map<String, Object> getUTXOs(String address) {
        ParamsConfig.Params params = config.getParams();
        int coinbaseMaturity = params.getCoinbaseMaturity();
        AtomicLong total = new AtomicLong();
        ArrayList<UTXO> utxos = new ArrayList<>();

        try {
            byte[] pubkey = Base58.decode(address);
            byte[] addr = Sha.applyRIPEMD160(Sha.applySHA256(pubkey));
            log.info("前缀{}", bytesToHex(addr));

            dataBase.iterateByPrefix(ADDR_TO_UTXO, addr, (key, value) -> {
                try {
                    // 1. 校验key的基本合法性
                    byte[] keyBytes = (byte[]) key;
                    if (keyBytes.length < 20) { // 至少包含20字节地址前缀
                        log.warn("无效的ADDR_TO_UTXO key，长度不足：{}", keyBytes.length);
                        return true; // 跳过该记录，继续遍历
                    }

                    // 2. 提取UTXO key并校验长度（32字节txId + 4字节index = 36字节）
                    byte[] utxoKey = Arrays.copyOfRange(keyBytes, 20, keyBytes.length);
                    if (utxoKey.length != 36) {
                        log.warn("无效的UTXO key长度：{}，预期36字节", utxoKey.length);
                        return true;
                    }

                    // 3. 查询TUTXO并校验是否为空
                    byte[] utxoBytes = dataBase.get(TUTXO, utxoKey);
                    if (utxoBytes == null) {
                        log.warn("UTXO key {} 在TUTXO表中无数据，跳过", bytesToHex(utxoKey));
                        return true;
                    }

                    // 4. 反序列化UTXO并捕获异常
                    UTXO deserialize = UTXO.deserialize(utxoBytes);
                    if (deserialize == null) {
                        log.warn("UTXO反序列化为null，key：{}", bytesToHex(utxoKey));
                        return true;
                    }

                    // 5. 设置UTXO状态（原有逻辑）
                    long status = 0L;
                    status = setLifecycleStatus(status, CONFIRMED_UNSPENT);
                    if (deserialize.isCoinbase()) {
                        int height = deserialize.getHeight();
                        int mainHeight = mainTipBlock != null ? mainTipBlock.getHeight() : 0;
                        int confirmations = mainHeight - height + 1;
                        boolean isMature = confirmations > coinbaseMaturity;
                        if (!isMature) {
                            status = UTXOStatusResolver.setCoinbaseMaturing(status);
                        } else {
                            status = UTXOStatusResolver.setCoinbaseMatured(status);
                        }
                    }
                    deserialize.setStatus(status);
                    utxos.add(deserialize);

                    // 6. 计算金额（原有逻辑）
                    long amount = ByteBuffer.wrap((byte[]) value).order(ByteOrder.LITTLE_ENDIAN).getLong();
                    total.addAndGet(amount);

                } catch (IllegalArgumentException e) {
                    log.error("UTXO反序列化失败，key：{}，异常：{}", bytesToHex((byte[]) key), e.getMessage());
                } catch (Exception e) {
                    log.error("处理UTXO记录失败，key：{}", bytesToHex((byte[]) key), e);
                }
                return true;
            });
        } catch (Exception e) {
            log.error("查询地址{}的UTXO失败", address, e);
        }

        return Map.of("total", total.get(), "utxos", utxos);
    }




    public Block getBlockByHeight(int height) {
        byte[] blockHash = dataBase.get(HEIGHT_TO_HASH, height);
        if (blockHash==null){
            return null;
        }
        return getBlockByHash(blockHash);
    }

    /**
     * 已经上链的交易
     * @param address
     * @return
     */
    public List<Transaction> getTxListByAddres(String address) {

        return null;
    }

    //分页获取这个地址下的交易
    public List<Transaction> getTxListByAddres(String address,int pageNum,int pageSize) {

        return null;
    }




    private static final int DIFFICULTY_ADJUSTMENT_INTERVAL = 20; // 每2016个区块调整一次
    private static final int TARGET_BLOCK_SPACING_SECONDS = 1 * 60; // 目标出块时间：10分钟
    private static final long EXPECTED_TIME_FOR_ADJUSTMENT_INTERVAL = (long) DIFFICULTY_ADJUSTMENT_INTERVAL * TARGET_BLOCK_SPACING_SECONDS; // 2016个区块的预期总时间

    private static final BigInteger MAX_TARGET = new BigInteger(BlockHeader.INIT_DIFFICULTY_TARGET_HEX, 16);


    public int calculateNextBlockDifficulty() {
        ParamsConfig.Params params = config.getParams();

        // 1. 获取当前链的高度
        // 假设你有一个方法可以获取当前链的高度
        int currentHeight = getCurrentChainHeight();
        if (currentHeight == -1) {
            log.error("无法获取当前链高度，无法计算难度。");
            // 返回一个默认的安全难度值
            return convertTargetToBits(MAX_TARGET);
        }

        // 2. 检查是否需要调整难度
        if ((currentHeight + 1) % DIFFICULTY_ADJUSTMENT_INTERVAL != 0) {
            // 如果不是调整间隔的区块，则难度与上一个区块相同
            Block lastBlock = getBlockByHeight(currentHeight);
            if (lastBlock != null) {
                return lastBlock.getBlockHeader().getBits();
            }
            log.warn("无法获取高度为 {} 的区块，返回默认难度。", currentHeight);
            return convertTargetToBits(MAX_TARGET);
        }

        log.info("达到难度调整间隔，正在计算新区块难度...");

        // 3. 获取用于计算的两个关键区块：当前最新区块和2016个区块之前的锚定区块
        Block lastBlock = getBlockByHeight(currentHeight);
        Block anchorBlock = getBlockByHeight(currentHeight - DIFFICULTY_ADJUSTMENT_INTERVAL + 1);

        if (lastBlock == null || anchorBlock == null) {
            log.error("获取用于难度调整的区块失败。lastBlock: {}, anchorBlock: {}",
                    lastBlock != null, anchorBlock != null);
            // 如果历史数据不完整，无法安全调整，保持当前难度
            return lastBlock != null ? lastBlock.getBlockHeader().getBits() : convertTargetToBits(MAX_TARGET);
        }

        // 4. 计算实际耗时
        long actualTimeSpent = lastBlock.getBlockHeader().getTime() - anchorBlock.getBlockHeader().getTime();
        log.info("过去 {} 个区块的实际耗时: {} 秒 (目标: {} 秒)",
                DIFFICULTY_ADJUSTMENT_INTERVAL, actualTimeSpent, EXPECTED_TIME_FOR_ADJUSTMENT_INTERVAL);

        // 5. 计算难度调整系数
        // 使用 BigInteger 进行精确计算
        BigInteger adjustmentFactor = BigInteger.valueOf(actualTimeSpent);
        adjustmentFactor = adjustmentFactor.multiply(BigInteger.valueOf(100000000)); // 乘以一个大数以避免浮点数精度问题
        adjustmentFactor = adjustmentFactor.divide(BigInteger.valueOf(EXPECTED_TIME_FOR_ADJUSTMENT_INTERVAL));
        // 现在 adjustmentFactor 是一个以 100000000 为基数的定点数

        // 6. 获取当前目标值
        BigInteger currentTarget = lastBlock.getBlockHeader().getTargetFromBits();

        // 7. 计算新目标值
        BigInteger newTarget = currentTarget.multiply(adjustmentFactor);
        newTarget = newTarget.divide(BigInteger.valueOf(100000000)); // 除以基数，得到最终结果

        // 8. 边界检查：确保新目标值不会低于协议允许的最小值（即难度不会无限增大）
        // 同时也不能高于最大值（即难度不能低于创世区块）
        if (newTarget.compareTo(MAX_TARGET) > 0) {
            log.warn("计算出的新目标值过高，已将其限制在最大值: {}", MAX_TARGET.toString(16));
            newTarget = MAX_TARGET;
        }

        // 比特币协议还规定了难度调整的最大幅度为4倍（即最多降低到1/4，或增加到4倍）

        BigInteger lowerBound = currentTarget.divide(BigInteger.valueOf(4));
        BigInteger upperBound = currentTarget.multiply(BigInteger.valueOf(4));
        if (newTarget.compareTo(lowerBound) < 0) newTarget = lowerBound;
        if (newTarget.compareTo(upperBound) > 0) newTarget = upperBound;

        // 9. 将新目标值转换为 bits 格式
        int newBits = convertTargetToBits(newTarget);

        log.info("难度调整完成。旧目标: {} (bits: {}), 新目标: {} (bits: {})",
                currentTarget.toString(16), lastBlock.getBlockHeader().getBits(),
                newTarget.toString(16), newBits);

        return newBits;
    }





    private int getCurrentChainHeight() {
        byte[] bytes = dataBase.get(CHAIN, MAIN_CHAIN_TIP_HEIGHT_KEY);
        assert bytes!=null;
        return bytesToInt(bytes);
    }




    public static void main(String[] args) {
        byte[] bytes = "The Times 03/Jan/2009 Chancellor on brink of second bailout for banks".getBytes(StandardCharsets.UTF_8);
        log.info("长度{}",bytes.length);

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
        byte[] bytes = dataBase.get(TUTXO, hexToBytes(key));
        return UTXO.deserialize(bytes);
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

        // 计算要查询的区块数量
        int count = startHeight - endHeight + 1;

        // 限制最多100个
        if (count > 100) {
            endHeight = startHeight - 99;  // 调整结束高度
            count = 100;
        }

        List<Block> blocks = new ArrayList<>(count);

        for (int height = startHeight; height >= endHeight; height--) {
            Block block = getBlockByHeight(height);
            if (block != null) {
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
        if (pageNum < 1) {
            pageNum = 1;
        }
        if (pageSize < 1 || pageSize > 100) {
            pageSize = 10;
        }

        int currentHeight = getCurrentChainHeight();
        List<Transaction> allTransactions = new ArrayList<>();


        int blocksNeeded = pageNum * pageSize;
        int blocksChecked = 0;
        int totalTransactions = 0;

        for (int height = currentHeight; height >= 0 && blocksChecked < blocksNeeded; height--) {
            Block block = getBlockByHeight(height);
            if (block != null) {
                List<Transaction> blockTxs = block.getTransactions();
                //将交易状态都设置为已经确认
                for (Transaction tx : blockTxs) {
                    tx.setStatus(TransactionStatusResolver.CONFIRMED);
                }

                totalTransactions += blockTxs.size();
                allTransactions.addAll(blockTxs);
                blocksChecked++;
            }
        }
        int total = totalTransactions;
        int totalPages = (total + pageSize - 1) / pageSize;
        // 分页提取交易
        int startIndex = (pageNum - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, allTransactions.size());
        List<Transaction> pageTransactions = new ArrayList<>();
        if (startIndex < allTransactions.size()) {
            pageTransactions = allTransactions.subList(startIndex, endIndex);
        }
        return pageTransactions;
    }


    @Override
    public PageResult<Transaction> getTransactionsByPage(String address, int pageSize, String lastKey) {
        // 限制pageSize最大值为20，最小值保底为1避免无效值
        pageSize = Math.max(1, Math.min(pageSize, 20));

        // 获取内存池中的未确认交易
        //Transaction[] txsByAddress = txPool.getTxsByAddress(address);

        try {
            // 地址转换：Base58解码 -> 双重哈希得到前缀
            byte[] pubkey = Base58.decode(address);
            byte[] addr = Sha.applyRIPEMD160(Sha.applySHA256(pubkey));

            // 处理lastKey：空值转为null，非空则十六进制转字节数组
            byte[] lastKeyBytes = (lastKey == null || lastKey.isEmpty()) ? null : hexToBytes(lastKey);
     /*       //lastKeyBytes在前面拼接20字节的addr 重点
            lastKeyBytes = (lastKeyBytes == null) ? null : Bytes.concat(addr, lastKeyBytes);
*/
            // 按前缀分页查询交易ID的复合键
            PageResult<Object> page = dataBase.pageKeyByPrefix(ADDR_TO_TX, addr, pageSize, lastKeyBytes);
            log.info("按地址[{}]分页查询到{}条交易ID键", address, page.getData().size());
            byte[] lastKey1 = page.getLastKey();
            log.info("最后一个键{}",bytesToHex(lastKey1));


            List<Object> data = page.getData();
            if (data.isEmpty()) {
                return null;
            }

            // 解析复合键，提取交易ID，并缓存「区块Hash-交易索引-交易ID」映射
            ArrayList<byte[]> txIdList = new ArrayList<>();
            // 核心优化：存储「区块HashHex -> 交易索引 -> 交易IDHex」的映射，避免遍历
            Map<String, Map<Integer, String>> blockTxIndexMap = new HashMap<>();
            //区块HashHex - > 是否主链
            Map<String, Boolean> blockIsMainMap = new HashMap<>();

            for (Object o : data) {
                if (!(o instanceof byte[])) {
                    log.warn("无效的交易ID键类型：{}", o.getClass().getName());
                    continue;
                }
                byte[] key = (byte[]) o;
                // 校验复合键长度，避免数组越界
                if (key.length <= addr.length) {
                    log.warn("复合键长度异常，前缀长度[{}]，键长度[{}]", addr.length, key.length);
                    continue;
                }
                // 提取纯交易ID（去掉地址前缀）
                byte[] txId = Arrays.copyOfRange(key, addr.length, key.length);
                String txIdHex = bytesToHex(txId);
                log.info("交易ID是{}", txIdHex);
                txIdList.add(txId);

                // 查询交易对应的区块Hash和在区块中的索引
                byte[] blockTxIndex = dataBase.get(TX_TO_BLOCK, txId);
                if (blockTxIndex == null) {
                    log.warn("交易ID[{}]未找到对应的区块索引信息", txIdHex);
                    continue;
                }

                // 解析区块索引信息
                Map<String, Object> blockIndexMap = Transaction.parseBlockIndex(blockTxIndex);
                byte[] blockHash = (byte[]) blockIndexMap.get("hash");
                int txIndexInBlock = (int) blockIndexMap.get("index");
                String blockHashHex = bytesToHex(blockHash);

                // 缓存：区块Hash -> 交易索引 -> 交易ID
                blockTxIndexMap.computeIfAbsent(blockHashHex, k -> new HashMap<>())
                        .put(txIndexInBlock, txIdHex);
            }

            if (blockTxIndexMap.isEmpty()) {
                return null;
            }



            // 遍历区块，通过索引直接获取交易（核心优化点）
            ArrayList<Transaction> transactionList = new ArrayList<>();
            for (Map.Entry<String, Map<Integer, String>> entry : blockTxIndexMap.entrySet()) {
                String blockHashHex = entry.getKey();
                Map<Integer, String> txIndexToIdMap = entry.getValue();

                boolean isMainBlock = isMainBlock(blockHashHex);

                // 查询区块体数据
                byte[] blockHashBytes = hexToBytes(blockHashHex);
                byte[] blockBody = dataBase.get(TBLOCK_BODY, blockHashBytes);
                if (blockBody == null) {
                    log.warn("区块Hash[{}]未找到对应的区块数据", blockHashHex);
                    continue;
                }

                // 反序列化区块体
                BlockBody deserialize = BlockBody.deserialize(blockBody);
                if (deserialize == null || deserialize.getTransactions() == null) {
                    log.warn("区块[{}]反序列化失败或无交易数据", blockHashHex);
                    continue;
                }
                List<Transaction> blockTxs = deserialize.getTransactions();

                // 关键优化：通过索引直接获取交易，无需遍历所有交易
                for (Map.Entry<Integer, String> txIndexEntry : txIndexToIdMap.entrySet()) {
                    int txIndex = txIndexEntry.getKey();
                    String txIdHex = txIndexEntry.getValue();

                    // 校验索引合法性，避免数组越界
                    if (txIndex < 0 || txIndex >= blockTxs.size()) {
                        log.warn("区块[{}]中交易索引[{}]越界，区块交易总数[{}]",
                                blockHashHex, txIndex, blockTxs.size());
                        continue;
                    }

                    // 直接通过索引获取交易
                    Transaction targetTx = blockTxs.get(txIndex);
                    // 二次校验交易ID（防止索引对应关系错误）
                    if (txIdHex.equals(bytesToHex(targetTx.getTxId()))) {
                        List<TxInput> inputs = targetTx.getInputs();
                        for (TxInput txInput:inputs){
                            if (!txInput.isCoinbase() && deserialize.getHeight()!=0){
                                Transaction txByHash = getTxByHash(txInput.getTxId());
                                TxOutput txOutput = txByHash.getOutputs().get(txInput.getIndex());
                                txInput.setOutput(txOutput);
                            }
                        }
                        targetTx.setInputs(inputs);
                        //设置目标交易的状态
                        long status = 0;
                        if (isMainBlock){
                            status = TransactionStatusResolver.setLifecycleStatus(status,TransactionStatusResolver.CONFIRMED);
                        }else {
                            status = TransactionStatusResolver.setLifecycleStatus(status,TransactionStatusResolver.FORK);
                        }
                        targetTx.setStatus(status);
                        transactionList.add(targetTx);
                    } else {
                        log.warn("区块[{}]索引[{}]的交易ID不匹配，预期[{}]，实际[{}]",
                                blockHashHex, txIndex, txIdHex, bytesToHex(targetTx.getTxId()));
                    }
                }
            }
            return new PageResult<>(transactionList, page.getLastKey(), page.isLastPage());
        } catch (Exception e) {
            log.error("分页查询地址[{}]的交易失败", address, e);
            return null;
        }
    }


    //区块是否主链
    @Override
    public boolean isMainBlock(String blockHexHash) {
        byte[] blockHash = hexToBytes(blockHexHash);
        //是否32字节
        assert blockHash != null;
        if (blockHash.length != 32) {
            return false;
        }
        //hash到高度的映射得到高度 再从高度到hash 检查是否一致即可 只有主链区块才有高度到hash
        byte[] height = dataBase.get(HASH_TO_HEIGHT, blockHash);
        byte[] hash = dataBase.get(HEIGHT_TO_HASH, height);
        return Arrays.equals(blockHash, hash);
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
        //是否32字节
        assert blockHash != null;
        if (blockHash.length != 32) {
            return false;
        }
        //hash到高度的映射得到高度 再从高度到hash 检查是否一致即可 只有主链区块才有高度到hash
        byte[] height = dataBase.get(HASH_TO_HEIGHT, blockHash);
        byte[] hash = dataBase.get(HEIGHT_TO_HASH, height);
        return Arrays.equals(blockHash, hash);
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
    public  Landmark findCommonAncestorByLandmark(List<Landmark> remote) {
        int maxHeight = remote.stream().mapToInt(Landmark::getHeight).max().getAsInt();
        log.info("该路标最大高度{}",maxHeight);
        List<Landmark> local = generateSyncLandmarks(maxHeight);

        //如果远程的maxHeight 大于本地直接返回创世 因为无法比较
        if (maxHeight > mainTipBlock.getHeight()) {
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
            if (r.getHeight() == 0) continue;       // 忽略创世（作为兜底返回）
            Landmark exist = remoteByHash.get(rh);
            if (exist == null || r.getHeight() > exist.getHeight()) {
                remoteByHash.put(rh, r);
            }
        }
        // 3. 收集本地有效路标（可规范化且非创世），按高度降序排序
        List<Landmark> localValid = new ArrayList<>();
        for (Landmark l : local) {
            String lh = bytesToHex(l.getHash());
            if (l.getHeight() == 0) continue;
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
    private static Landmark createGenesisLandmark() {
        Landmark genesisLandmark = new Landmark();
        genesisLandmark.setHeight(0);
        // 创世区块的hash需与项目中createGenesisBlock()生成的hash一致
        // 这里复用原有创世区块创建逻辑的hash，保证准确性
        Block genesisBlock = BlockChainServiceImpl.createGenesisBlock();
        genesisLandmark.setHash(genesisBlock.getHash());
        log.debug("创建创世区块路标 | 高度: 0 | Hash: {}", bytesToHex(genesisLandmark.getHash()));
        return genesisLandmark;
    }

}
