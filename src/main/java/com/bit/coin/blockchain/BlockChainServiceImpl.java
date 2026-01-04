package com.bit.coin.blockchain;

import com.bit.coin.config.SystemConfig;
import com.bit.coin.database.DataBase;
import com.bit.coin.database.rocksDb.RocksDb;
import com.bit.coin.database.rocksDb.TableEnum;
import com.bit.coin.structure.block.Block;
import com.bit.coin.structure.block.BlockHeader;
import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.structure.tx.TxInput;
import com.bit.coin.structure.tx.TxOutput;
import com.bit.coin.structure.tx.UTXO;
import com.bit.coin.utils.Sha;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.bit.coin.structure.block.BlockHeader.convertTargetToBits;
import static com.bit.coin.utils.SerializeUtils.*;
import static org.bouncycastle.util.Pack.intToLittleEndian;


@Slf4j
@Service
public class BlockChainServiceImpl {

    @Autowired
    private SystemConfig config;

    private DataBase dataBase;

    @PostConstruct
    public void init() {
        dataBase = config.getDataBase();
        //检查创世区块 int转字节
        byte[] hash = dataBase.get(TableEnum.HEIGHT_TO_HASH, 0);
        if (hash==null){
            //初始化
            Block genesisBlock = createGenesisBlock();
            //hash - > 区块
            //高度到 -> hash
            //将区块中的UTXO加入到UTXO集合

            List<RocksDb.DbOperation> operations = new ArrayList<>();
            operations.add(new RocksDb.DbOperation(
                    TableEnum.BLOCK,
                    genesisBlock.getHash(),
                    genesisBlock.serialize(),
                    RocksDb.DbOperation.OpType.INSERT
            ));
            operations.add(new RocksDb.DbOperation(
                    TableEnum.HEIGHT_TO_HASH,
                    intToLittleEndian(0),
                    genesisBlock.getHash(),
                    RocksDb.DbOperation.OpType.INSERT
            ));
            List<UTXO> utxOs = genesisBlock.getUTXOs();
            for (UTXO utxo : utxOs){
                operations.add(new RocksDb.DbOperation(
                        TableEnum.UTXO,
                        utxo.getKey(),
                        utxo.serialize(),
                        RocksDb.DbOperation.OpType.INSERT
                ));
                //地址到UTXO value是金额 帮助快速找到地址的UTXO
                operations.add(new RocksDb.DbOperation(
                        TableEnum.UTXO,
                        utxo.getAddressKey(),
                        longToLittleEndian(utxo.getValue()),
                        RocksDb.DbOperation.OpType.INSERT
                ));
            }
            //交易到区块的索引
            List<Transaction> transactions = genesisBlock.getTransactions();
            for (int i = 0; i < transactions.size(); i++) {
                Transaction transaction = transactions.get(i);
                byte[] txId = transaction.getTxId();
                byte[] blockIndex = transaction.getBlockIndex(genesisBlock.getHash(),i);
                operations.add(new RocksDb.DbOperation(
                        TableEnum.TX_TO_BLOCK,
                        txId,
                        blockIndex,
                        RocksDb.DbOperation.OpType.INSERT
                ));
            }
            boolean success = dataBase.dataTransaction(operations);
            if (success) {
                System.out.println("事务成功：将区块加入到主链。");
            } else {
                System.out.println("事务失败：未将区块加入到主链。");
            }
        }else {
            byte[] bytes = dataBase.get(TableEnum.BLOCK, hash);
            Block deserialize = Block.deserialize(bytes);
            assert deserialize != null;
            log.info("创世区块Hash{}",bytesToHex(deserialize.getHash()));
        }
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
        genesisBlock.setTransactions(transactions);
        genesisBlock.setHash(blockHeader.calculateHash());
        return genesisBlock;
    }



    private static byte[] calculateMerkleRoot(List<Transaction> transactions) {
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
        byte[] bytes = "The Times 03/Jan/2009 Chancellor on brink of second bailout for banks".getBytes();
        input.setScriptSig(bytes);
        List<TxInput> inputs = new ArrayList<>();
        inputs.add(input);
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







    public static void main(String[] args) {
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



        byte[] userKey = "user_123".getBytes();
        byte[] userValue = "{\"name\":\"Alice\"}".getBytes();

        byte[] orderKey = "order_456".getBytes();
        byte[] orderValue = "{\"item\":\"Book\"}".getBytes();


        // 2. 创建跨表的操作列表
        List<RocksDb.DbOperation> operations = new ArrayList<>();


        // 操作1：向 USER_TABLE 插入数据
        operations.add(new RocksDb.DbOperation(
                TableEnum.TEST1,
                userKey,
                userValue,
                RocksDb.DbOperation.OpType.INSERT
        ));


        // 操作2：向 ORDER_TABLE 插入数据
        operations.add(new RocksDb.DbOperation(
                TableEnum.TEST2,
                orderKey,
                orderValue,
                RocksDb.DbOperation.OpType.INSERT
        ));


        // 3. 执行原子事务
        boolean success = new RocksDb().dataTransaction(operations);

        if (success) {
            System.out.println("事务成功：用户和订单已原子性地创建。");
        } else {
            System.out.println("事务失败：用户和订单都没有被创建。");
        }

    }

    public Block getBlockByHash(byte[] hash) {
        byte[] bytes = dataBase.get(TableEnum.BLOCK,hash);
        if (bytes!=null){
           return Block.deserialize(bytes);
        }else {
            return null;
        }
    }

    public Transaction getTxByHash(byte[] txId) {
        byte[] blockIndex = dataBase.get(TableEnum.TX_TO_BLOCK,txId);
        Map<String, Object> stringObjectMap = Transaction.parseBlockIndex(blockIndex);
        byte[] hash = (byte[])stringObjectMap.get("hash");
        int index = (int)stringObjectMap.get("index");
        byte[] bytes = dataBase.get(TableEnum.BLOCK, hash);
        Block deserialize = Block.deserialize(bytes);
        assert deserialize != null;
        return deserialize.getTransactions().get(index);
    }

    public BlockHeader getBlockHeaderByHash(byte[] hash) {
        byte[] bytes = dataBase.get(TableEnum.BLOCK,hash);
        if (bytes!=null){
            //获取前80字节
            byte[] headerBytes = Arrays.copyOfRange(bytes, 0, 80);
            //序列化
            return BlockHeader.deserialize(headerBytes);
        }else {
            return null;
        }
    }
}
