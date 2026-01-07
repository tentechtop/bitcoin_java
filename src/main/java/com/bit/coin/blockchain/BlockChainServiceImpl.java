package com.bit.coin.blockchain;

import com.bit.coin.cache.UTXOCache;
import com.bit.coin.config.SystemConfig;
import com.bit.coin.database.DataBase;
import com.bit.coin.database.rocksDb.RocksDb;
import com.bit.coin.database.rocksDb.TableEnum;
import com.bit.coin.mining.MiningServiceImpl;
import com.bit.coin.structure.block.Block;
import com.bit.coin.structure.block.BlockHeader;
import com.bit.coin.structure.tx.*;
import com.bit.coin.txpool.TxPool;
import com.bit.coin.utils.Sha;
import com.bit.coin.utils.TimeGenerator;
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
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static com.bit.coin.database.rocksDb.RocksDb.bytesToInt;
import static com.bit.coin.database.rocksDb.TableEnum.*;
import static com.bit.coin.structure.block.BlockHeader.convertTargetToBits;
import static com.bit.coin.utils.SerializeUtils.*;



@Slf4j
@Service
public class BlockChainServiceImpl {

    @Autowired
    private SystemConfig config;

    @Autowired
    private DataBase dataBase;

    @Autowired
    private TxPool txPool;



    private static volatile Block mainTipBlock = null;


    //主链顶端高度 key
    public static final byte[] MAIN_CHAIN_TIP_HEIGHT_KEY = "MAIN_CHAIN_TIP_HEIGHT_KEY".getBytes(StandardCharsets.UTF_8);
    //主链顶端Hash
    public static final byte[] MAIN_CHAIN_TIP_HASH_KEY = "MAIN_CHAIN_TIP_HASH_KEY".getBytes(StandardCharsets.UTF_8);



    @PostConstruct
    public void init() {
        //检查创世区块 int转字节
        byte[] hash = dataBase.get(TableEnum.HEIGHT_TO_HASH, 0);
        if (hash==null){
            //初始化
            Block genesisBlock = createGenesisBlock();
            addBlockToChain(genesisBlock,true);
            mainTipBlock=genesisBlock;
            log.info("新创建创世区块高度{} 最新Hash{}",genesisBlock.getHeight(),bytesToHex(genesisBlock.getHash()));
        }else {
            byte[] tipHash = dataBase.get(CHAIN, MAIN_CHAIN_TIP_HASH_KEY);
            byte[] tipHeight = dataBase.get(CHAIN, MAIN_CHAIN_TIP_HEIGHT_KEY);
            byte[] bytes = dataBase.get(TBLOCK, tipHash);
            Block deserialize = Block.deserialize(bytes);
            assert deserialize != null;
            deserialize.setHeight(bytesToInt(tipHeight));
            deserialize.setHash(tipHash);
            mainTipBlock=deserialize;
            log.info("最新区块高度{} 最新Hash{}",deserialize.getHeight(),bytesToHex(tipHash));
        }
    }



    //验证区块
    public boolean verifyBlock(Block block) {
        log.info("区块高度{} 区块哈希{}",block.getHeight(),bytesToHex(block.getHash()));
        //基础格式验证
        byte[] hash = block.getHash();

        //Hash验证

        //工作量验证

        //区块中所有交易验证

        //是否主链延续
        if (Arrays.equals(block.getBlockHeader().getPreviousHash(), mainTipBlock.getHash())){

            log.info("是主链的延续");

            //将区块加入到区块链
            addBlockToChain(block,true);
            mainTipBlock = block;

            // 上链成功后，从交易池删除已确认的交易
            removeConfirmedTxsFromPool(block);

        }else {
            //非主链延续
            byte[] previousHash = block.getBlockHeader().getPreviousHash();
            byte[] bytes = dataBase.get(TBLOCK, previousHash);
            if (bytes==null){
                //孤块（Orphan Block）

                //处理孤块

            }else {
                //分叉区块（Fork Block）
                //分叉总工作量
                Block previousBlock = Block.deserialize(bytes);
                assert previousBlock != null;
                long chainWork = previousBlock.getChainWork();


            }
        }


        return true;
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


    //计算区块链工作总量
    public byte[] calculateChainWork() {

        return null;
    }



    //将区块加入到主链
    public void addBlockToChain(Block block,boolean isMainChain) {
        List<RocksDb.DbOperation> operations = new ArrayList<>();
        operations.add(new RocksDb.DbOperation(
                TableEnum.TBLOCK,
                block.getHash(),
                block.serialize(),
                RocksDb.DbOperation.OpType.INSERT
        ));
        byte[] intHeightToBytes = ByteBuffer.allocate(4).putInt(block.getHeight()).array();
        if (isMainChain){
            operations.add(new RocksDb.DbOperation(
                    TableEnum.HEIGHT_TO_HASH,
                    intHeightToBytes,
                    block.getHash(),
                    RocksDb.DbOperation.OpType.INSERT
            ));
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

            //最新链信息
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
        //交易到区块的索引
        List<Transaction> transactions = block.getTransactions();
        for (int i = 0; i < transactions.size(); i++) {
            Transaction transaction = transactions.get(i);
            byte[] txId = transaction.getTxId();
            byte[] blockIndex = transaction.getBlockIndex(block.getHash(),i);
            operations.add(new RocksDb.DbOperation(
                    TableEnum.TX_TO_BLOCK,
                    txId,
                    blockIndex,
                    RocksDb.DbOperation.OpType.INSERT
            ));
            //地址到交易的索引 获取输出列表所有的锁定脚本
            List<TxOutput> outputs = transaction.getOutputs();
            // 使用LinkedHashSet进行去重，并保留顺序
            Set<byte[]> seenScripts = new LinkedHashSet<>();
            for (TxOutput output : outputs) {
                byte[] scriptPubKey = output.getScriptPubKey();
                // 如果add方法返回true，说明是首次添加，不是重复项
                if (seenScripts.add(scriptPubKey)) {
                    operations.add(new RocksDb.DbOperation(
                            TableEnum.ADDR_TO_TX,
                            scriptPubKey,
                            txId,
                            RocksDb.DbOperation.OpType.INSERT
                    ));
                }
            }
        }
        boolean success = dataBase.dataTransaction(operations);
        if (success) {
            System.out.println("事务成功：将区块加入到主链。");
        } else {
            System.out.println("事务失败：未将区块加入到主链。");
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
        BlockHeader blockHeader = new BlockHeader(1, new byte[32], merkleRoot, 1754287472, convertTargetToBits(new BigInteger(BlockHeader.INIT_DIFFICULTY_TARGET_HEX, 16)), 98456);
        genesisBlock.setBlockHeader(blockHeader);
        genesisBlock.setTxCount(1);
        genesisBlock.setTransactions(transactions);
        genesisBlock.setHash(blockHeader.calculateHash());
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
        byte[] bytes = dataBase.get(TableEnum.TBLOCK,hash);
        if (bytes!=null){
           return Block.deserialize(bytes);
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
        byte[] blockData = dataBase.get(TableEnum.TBLOCK, blockHash);
        if (blockData == null) {
            return null; // 区块数据不存在
        }
        Block deserialize = Block.deserialize(blockData);
        assert deserialize != null;
        Transaction transaction = deserialize.getTransactions().get(txIndexInBlock);

        long status = 0;
        //交易所在的区块是否主连
        int height = deserialize.getHeight();
        byte[] bytes = dataBase.get(HEIGHT_TO_HASH, height);
        if (Arrays.equals(bytes, deserialize.getHash())){
            status = TransactionStatusResolver.setLifecycleStatus(status,TransactionStatusResolver.CONFIRMED);
        }else {
            status = TransactionStatusResolver.setLifecycleStatus(status,TransactionStatusResolver.FORK);
        }
        transaction.setStatus(status);
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
        byte[] bytes = dataBase.get(TableEnum.TBLOCK,hash);
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
        //key:total value:long
        //key:utxoList: value:List<UTXO>
        byte[] pubkey = Base58.decode(address);
        byte[] addr = Sha.applyRIPEMD160(Sha.applySHA256(pubkey));
        //总额
        AtomicLong total = new AtomicLong();
        //UTXOLIST
        log.info("前缀{}",bytesToHex(addr));
        ArrayList<UTXO> utxos = new ArrayList<>();
        dataBase.iterateByPrefix(ADDR_TO_UTXO,addr, (key, value) -> {
            // 在这里处理您找到的每一个键值对
            //ADDR_TO_UTXO 表中 key是 [地址 20字节][交易ID 32字节][索引4字节] value是小端金额
            //去除地址 获得UTXOkey
            byte[] utxoKey = Arrays.copyOfRange((byte[]) key, 20, ((byte[]) key).length);
            byte[] bytes = dataBase.get(TUTXO, utxoKey);
            UTXO deserialize = com.bit.coin.structure.tx.UTXO.deserialize(bytes);
            utxos.add(deserialize);
            //金额
            long amount = ByteBuffer.wrap((byte[]) value).order(ByteOrder.LITTLE_ENDIAN).getLong();
            total.addAndGet(amount);
            return true;
        });
        return Map.of("total",total.get(),"utxos",utxos);
    }




    public Block getBlockByHeight(int height) {
        byte[] blockHash = dataBase.get(HEIGHT_TO_HASH, height);
        if (blockHash==null){
            return null;
        }
        byte[] blockData = dataBase.get(TBLOCK, blockHash);
        assert blockData != null;
        return Block.deserialize(blockData);
    }

    /**
     * 已经上链的交易
     * @param address
     * @return
     */
    public List<Transaction> getTxListByAddres(String address) {

        return null;
    }


    private static final int DIFFICULTY_ADJUSTMENT_INTERVAL = 2016; // 每2016个区块调整一次
    private static final int TARGET_BLOCK_SPACING_SECONDS = 10 * 60; // 目标出块时间：10分钟
    private static final long EXPECTED_TIME_FOR_ADJUSTMENT_INTERVAL = (long) DIFFICULTY_ADJUSTMENT_INTERVAL * TARGET_BLOCK_SPACING_SECONDS; // 2016个区块的预期总时间

    private static final BigInteger MAX_TARGET = new BigInteger(BlockHeader.INIT_DIFFICULTY_TARGET_HEX, 16);


    public int calculateNextBlockDifficulty() {
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
}
