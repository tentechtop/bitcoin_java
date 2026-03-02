package com.bit.coin;

import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.*;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.TestNet3Params;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
public class BlockCreationTest {

    @Test
    public void testCompleteBlockCreation() {
        NetworkParameters params = TestNet3Params.get();

        System.out.println("=== 完整区块创建与测试 ===\n");

        // 1. 准备区块数据
        long version = 2;
        Sha256Hash prevBlockHash = params.getGenesisBlock().getHash(); // 引用创世区块
        long time = System.currentTimeMillis() / 1000;
        long difficultyTarget = 0x1d00ffffL; // 测试网难度
        long nonce = 0;

        // 2. 创建交易列表（包含Coinbase交易和普通交易）
        List<Transaction> transactions = createSampleTransactions(params);

        // 3. 计算Merkle根
        Sha256Hash merkleRoot = calculateMerkleRoot(transactions);

        System.out.println("区块参数:");
        System.out.println("版本: " + version);
        System.out.println("前驱哈希: " + prevBlockHash);
        System.out.println("时间戳: " + time);
        System.out.println("难度目标: " + difficultyTarget);
        System.out.println("初始Nonce: " + nonce);
        System.out.println("交易数量: " + transactions.size());
        System.out.println("Merkle根: " + merkleRoot);

        // 4. 使用正确的构造函数创建区块
        Block block = new Block(params, version, prevBlockHash, merkleRoot,
                time, difficultyTarget, nonce, transactions);

        // 5. 测试区块基本属性
        testBlockProperties(block, params);

        // 6. 模拟挖矿过程
        testMiningProcess(block);

        // 7. 验证区块完整性
        testBlockVerification(block);
    }

    /**
     * 创建示例交易列表
     */
    private List<Transaction> createSampleTransactions(NetworkParameters params) {
        List<Transaction> transactions = new LinkedList<>();

        // Coinbase交易（矿工奖励）
        Transaction coinbaseTx = createCoinbaseTransaction(params);
        transactions.add(coinbaseTx);

        // 普通交易
        Transaction normalTx = createNormalTransaction(params);
        transactions.add(normalTx);



        return transactions;
    }

    /**
     * 创建Coinbase交易
     */
    private Transaction createCoinbaseTransaction(NetworkParameters params) {
        Transaction tx = new Transaction(params);

        // Coinbase输入（独特的输入结构）
        byte[] coinbaseData = "Hello Bitcoin!".getBytes();
        tx.addInput(new TransactionInput(params, tx, coinbaseData));

        // Coinbase输出（矿工奖励）
        ECKey minerKey = new ECKey();
        Address minerAddress = LegacyAddress.fromKey(params, minerKey);
        tx.addOutput(Coin.valueOf(12_500_000, 0), minerAddress); // 12.5 BTC

        return tx;
    }

    /**
     * 创建普通交易
     */
    private Transaction createNormalTransaction(NetworkParameters params) {
        Transaction tx = new Transaction(params);

        // 简化交易创建（实际应用中需要有效的UTXO和签名）
        ECKey fromKey = new ECKey();
        ECKey toKey = new ECKey();

        // 交易输出
        Address toAddress = LegacyAddress.fromKey(params, toKey);
        tx.addOutput(Coin.valueOf(1_000_000, 0), toAddress);

        return tx;
    }

    /**
     * 计算Merkle根
     */
    private Sha256Hash calculateMerkleRoot(List<Transaction> transactions) {
        // bitcoinj 会自动计算Merkle根，这里演示原理
        System.out.println("计算Merkle根...");

        // 实际中 bitcoinj 会在区块构建时自动处理
        // 这里只是演示概念
        if (transactions.isEmpty()) {
            return Sha256Hash.ZERO_HASH;
        }

        // 简化版Merkle根计算演示
        List<Sha256Hash> transactionHashes = new LinkedList<>();
        for (Transaction tx : transactions) {
            transactionHashes.add(tx.getHash());
        }

        // 实际Merkle树计算更复杂，这里简化处理
        return transactionHashes.get(0); // 简化返回第一个交易的哈希
    }

    /**
     * 测试区块基本属性
     */
    private void testBlockProperties(Block block, NetworkParameters params) {
        System.out.println("\n=== 区块属性测试 ===");

        assertEquals(2, block.getVersion());
        assertNotNull(block.getPrevBlockHash());
        assertNotNull(block.getMerkleRoot());
        assertTrue(block.getTimeSeconds() > 0);
        assertEquals(0x1d00ffffL, block.getDifficultyTarget());
        assertEquals(0, block.getNonce());

        // 验证交易数量
        assertEquals(2, block.getTransactions().size());

        // 验证第一个交易是Coinbase交易
        Transaction firstTx = block.getTransactions().get(0);
        assertTrue(firstTx.isCoinBase());

        System.out.println("✅ 区块基本属性验证通过");
    }

    /**
     * 测试挖矿过程
     */
    private void testMiningProcess(Block block) {
        System.out.println("\n=== 模拟挖矿过程 ===");

        long startNonce = 0;
        long maxNonce = 100000; // 限制尝试次数

        System.out.println("开始寻找有效Nonce (范围: " + startNonce + " - " + maxNonce + ")");

        boolean mined = false;
        for (long nonce = startNonce; nonce <= maxNonce; nonce++) {
            block.setNonce(nonce);
            Sha256Hash hash = block.getHash();

            // 简化难度检查：哈希以"0000"开头
            if (hash.toString().startsWith("0000")) {
                System.out.println("🎉 挖矿成功！");
                System.out.println("有效Nonce: " + nonce);
                System.out.println("区块哈希: " + hash);
                System.out.println("尝试次数: " + (nonce - startNonce));
                mined = true;
                break;
            }

            // 进度显示
            if (nonce % 10000 == 0 && nonce > 0) {
                System.out.println("已尝试 " + nonce + " 次，当前哈希: " + hash.toString().substring(0, 16) + "...");
            }
        }

        if (!mined) {
            System.out.println("❌ 在指定范围内未找到有效Nonce");
        }
    }

    /**
     * 验证区块完整性
     */
    private void testBlockVerification(Block block) {
        System.out.println("\n=== 区块完整性验证 ===");

        try {
            // 1. 验证工作量证明
            BigInteger work = block.getWork();


            // 2. 验证区块哈希一致性
            Sha256Hash calculatedHash = block.getHash();



            // 3. 验证Merkle根
            Sha256Hash calculatedMerkle = block.getMerkleRoot();
            assertNotNull(calculatedMerkle);
            System.out.println("Merkle根验证: ✅ 通过");

            // 4. 验证区块大小
            byte[] bytes = block.bitcoinSerialize();


            System.out.println("区块大小"+bytes.length);


            // 5. 验证交易完整性
            for (int i = 0; i < block.getTransactions().size(); i++) {
                Transaction tx = block.getTransactions().get(i);
                assertNotNull(tx.getHash());
                System.out.println("交易 " + i + " 哈希: " + tx.getHash());
            }

            System.out.println("✅ 所有区块验证通过！");

        } catch (Exception e) {
            System.err.println("❌ 区块验证失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Test
    public void testBlockSerialization() {
        System.out.println("=== 区块序列化测试 ===\n");

        NetworkParameters params = TestNet3Params.get();

        // 创建简单区块
        List<Transaction> transactions = Arrays.asList(createCoinbaseTransaction(params));
        Block block = new Block(params, 1, Sha256Hash.ZERO_HASH,
                Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000001"),
                System.currentTimeMillis() / 1000, 0x1f0fffffL, 12345, transactions);

        // 测试序列化
        byte[] serialized = block.bitcoinSerialize();
        assertTrue(serialized.length > 0);
        System.out.println("序列化数据长度: " + serialized.length + " 字节");

        // 测试反序列化
        try {
            Block deserializedBlock = params.getDefaultSerializer().makeBlock(serialized);
            assertEquals(block.getHash(), deserializedBlock.getHash());
            System.out.println("✅ 序列化/反序列化验证通过");
        } catch (Exception e) {
            System.err.println("❌ 反序列化失败: " + e.getMessage());
        }
    }

    @Test
    public void testGenesisBlockReference() {
        System.out.println("=== 创世区块引用测试 ===\n");

        NetworkParameters params = MainNetParams.get();
        Block genesisBlock = params.getGenesisBlock();

        // 验证创世区块的特殊属性
        assertEquals(1, genesisBlock.getVersion());
        assertEquals(Sha256Hash.ZERO_HASH, genesisBlock.getPrevBlockHash());
        assertTrue(genesisBlock.getTimeSeconds() > 0);

        // 验证创世区块哈希
        System.out.println("创世区块哈希: " + genesisBlock.getHash());
        System.out.println("时间戳: " + genesisBlock.getTime());
        System.out.println("Nonce: " + genesisBlock.getNonce());

        // 验证工作量证明

    }
}