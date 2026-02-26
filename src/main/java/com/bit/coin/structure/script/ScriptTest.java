package com.bit.coin.structure.script;

import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.structure.tx.TxInput;
import com.bit.coin.structure.tx.TxOutput;
import com.bit.coin.structure.tx.UTXO;
import com.bit.coin.utils.Ed25519Signer;
import com.bit.coin.utils.SerializeUtils;
import com.bit.coin.utils.Sha;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiFunction;

import static com.bit.coin.structure.script.ScriptExecutor.*;
import static com.bit.coin.structure.tx.Transaction.SIGHASH_ALL;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static com.bit.coin.utils.SerializeUtils.hexToBytes;


@Slf4j
public class ScriptTest {

    //{
    //    "txId": "7cf155bd127aed86c6208b2af0afa20007af717728215613901a2e04a11c993c",
    //    "version": 1,
    //    "lockTime": 0,
    //    "inputs": [
    //        {
    //            "txId": "06071a12ea4acf1b977fdf0c13c4adc7d65eb6cc5bb6f931fcaddb15e73df1cd",
    //            "index": 0,
    //            "sequence": "4294967295",
    //            "scriptSig": "45d5a3aaa5c4eb767f82785b35da1e16f968be6029df82cb47e763564dc9e0c0e2871d9946065a228972f981fb0fdc76759d16626ee1b27b9964fe8156e3b40f01dd5dbac2d2e1a7a9b849adc1dc89acb44c9f9518259933d031950cf2f1e17cd1",
    //            "output": {
    //                "value": 5000000000,
    //                "scriptPubKey": "a57f11a1cbb1d0a7841b5b1c374fbc46f42f8c56"
    //            }
    //        },
    //        {
    //            "txId": "e9e11fb89505f0efb292316cb54d504d3ff9a26777c997c6469d7ae097b8fb2d",
    //            "index": 0,
    //            "sequence": "4294967295",
    //            "scriptSig": "19954135d76a96e408dd11a3ae8814a5ff1fc816c9fc0996f50534d3e76c5aad42d046a125a4d9a379c28a2b77e3f2312ff39c927e7f792ac5c30e81c3df490101dd5dbac2d2e1a7a9b849adc1dc89acb44c9f9518259933d031950cf2f1e17cd1",
    //            "output": {
    //                "value": 5000000000,
    //                "scriptPubKey": "a57f11a1cbb1d0a7841b5b1c374fbc46f42f8c56"
    //            }
    //        }
    //    ],
    //    "outputs": [
    //        {
    //            "value": 5000000000,
    //            "scriptPubKey": "76934d8111b97796af8d22443628399ebb612124"
    //        },
    //        {
    //            "value": 4999995780,
    //            "scriptPubKey": "a57f11a1cbb1d0a7841b5b1c374fbc46f42f8c56"
    //        }
    //    ],
    //    "status": 2130303778818,
    //    "statusStr": "交易池 | 已验证 | 签名验证 | 脚本验证 | 输入验证 | 输出验证"
    //}

    public static void main(String[] args) throws IOException, ScriptExecutionException {

        testP2PKH();

        test1();
        test2();

        testStackOperations();

        testNumericOperations();

        testTimeLockOperations();


    }

    //OP_DUP OP_HASH160 与 OP_OVER OP_HASH160
    /**
     * 测试P2PKH脚本中 OP_DUP OP_HASH160 与 OP_OVER OP_HASH160 的差异和一致性
     * 修复：明确OP_OVER的正确使用方式，解释验证失败的根源
     */
    public static void testP2PKH() {
        try {
            log.info("\n===== 开始测试P2PKH中的OP_DUP vs OP_OVER =====");

            // ========== 步骤1：生成测试用密钥对和基础数据 ==========
            byte[] privateKey = hexToBytes("1ed02d6a330a20ae961a2bd921e80b9373874dd248d354e7c2410f67cef5cbc8");
            byte[] publicKey = hexToBytes("dd5dbac2d2e1a7a9b849adc1dc89acb44c9f9518259933d031950cf2f1e17cd1");
            byte[] pubKeyHash = Sha.applyRIPEMD160(Sha.applySHA256(publicKey));

            log.info("测试公钥: {}", bytesToHex(publicKey));
            log.info("公钥哈希: {}", bytesToHex(pubKeyHash));

            // ========== 步骤2：构造测试交易 ==========
            Transaction testTx = new Transaction();
            testTx.setVersion(1);
            testTx.setLockTime(0);

            TxInput txInput = new TxInput();
            txInput.setTxId(hexToBytes("06071a12ea4acf1b977fdf0c13c4adc7d65eb6cc5bb6f931fcaddb15e73df1cd"));
            txInput.setIndex(0);
            txInput.setSequence(0xFFFFFFFFL);
            testTx.getInputs().add(txInput);

            TxOutput txOutput = new TxOutput();
            txOutput.setValue(5000000000L);
            txOutput.setScriptPubKey(hexToBytes("76934d8111b97796af8d22443628399ebb612124"));
            testTx.getOutputs().add(txOutput);

            // 构造UTXO映射
            Map<String, UTXO> utxoMap = new HashMap<>();
            UTXO utxo = new UTXO();
            utxo.setTxId(txInput.getTxId());
            utxo.setIndex(txInput.getIndex());
            utxo.setValue(5000000000L);
            utxo.setScript(hexToBytes("76a914" + bytesToHex(pubKeyHash) + "88ac"));
            utxoMap.put(bytesToHex(UTXO.getKey(txInput.getTxId(), txInput.getIndex())), utxo);
            testTx.setUtxoMap(utxoMap);

            // ========== 步骤3：生成交易签名 ==========
            byte[] sigHash = testTx.prepareDataForSigning(0, utxo.getScript(), SIGHASH_ALL);
            byte[] signature = Ed25519Signer.fastSign(privateKey, Sha.applySHA256(sigHash));

            byte[] signatureWithHashType = new byte[signature.length + 1];
            System.arraycopy(signature, 0, signatureWithHashType, 0, signature.length);
            signatureWithHashType[signature.length] = SIGHASH_ALL;

            log.info("交易签名: {}", bytesToHex(signatureWithHashType).substring(0, 40) + "...");

            // ========== 步骤4：构造解锁脚本 ==========
            Script unlockingScript = new Script();
            unlockingScript.addData(signatureWithHashType);
            unlockingScript.addData(publicKey);
            log.info("解锁脚本: {}", unlockingScript.toString());


            // ========== 步骤5：测试1 - 标准OP_DUP脚本（预期成功） ==========
            Script standardLockingScript = new Script();
            standardLockingScript.addOpCode(Script.OP_DUP);
            standardLockingScript.addOpCode(Script.OP_HASH160);
            standardLockingScript.addData(pubKeyHash);
            standardLockingScript.addOpCode(Script.OP_EQUALVERIFY);
            standardLockingScript.addOpCode(Script.OP_CHECKSIG);

            log.info("\n标准P2PKH锁定脚本: {}", standardLockingScript.toString());

            ScriptExecutor executor1 = new ScriptExecutor();
            Script combinedStandard = combineScripts(unlockingScript, standardLockingScript);
            boolean standardResult = executor1.execute(combinedStandard, testTx, 0);
            log.info("标准P2PKH脚本验证结果: {} (预期: true)", standardResult);

            // 打印标准脚本执行后的栈状态（调试用）
            log.info("标准脚本执行后栈大小: {}", executor1.getStack().size());
            if (!executor1.getStack().isEmpty()) {
                log.info("标准脚本栈顶结果: {}", castToBool(executor1.getStack().get(0)));
            }

            //将解锁脚本和锁定脚本设置到输入和输出 并序列化和反序列化
            txInput.setScriptSig(unlockingScript.serialize());
            txOutput.setScriptPubKey(standardLockingScript.serialize());
            byte[] txSerialized = testTx.serialize();
            log.info("交易序列化后字节长度: {}", txSerialized.length);
            log.info("交易序列化后前64字节: {}", bytesToHex(Arrays.copyOf(txSerialized, Math.min(64, txSerialized.length))));
            // 4. 交易反序列化
            Transaction txDeserialized = Transaction.deserialize(txSerialized);
            log.info("交易反序列化完成，版本号: {}", txDeserialized.getVersion());

            List<TxOutput> outputs = txDeserialized.getOutputs();
            for (TxOutput output : outputs) {
                log.info("输出脚本: {}", output.getScriptPubKeyWk());
            }




            // ========== 步骤6：测试2 - 直接替换OP_OVER（预期失败，解释原因） ==========
            Script overLockingScript = new Script();
            overLockingScript.addOpCode(Script.OP_OVER); // 直接替换DUP为OVER
            overLockingScript.addOpCode(Script.OP_HASH160);
            overLockingScript.addData(pubKeyHash);
            overLockingScript.addOpCode(Script.OP_EQUALVERIFY);
            overLockingScript.addOpCode(Script.OP_CHECKSIG);
            log.info("\n直接替换OP_OVER的锁定脚本: {}", overLockingScript.toString());

            ScriptExecutor executor2 = new ScriptExecutor();
            Script combinedOver = combineScripts(unlockingScript, overLockingScript);
            boolean overResult = executor2.execute(combinedOver, testTx, 0);
            log.info("直接替换OP_OVER脚本验证结果: {} (预期: false)", overResult);

            // 调试：打印OP_OVER执行后的栈状态，明确失败原因
            log.info("OP_OVER脚本执行后栈大小: {}", executor2.getStack().size());
            if (executor2.getStack().size() >= 4) {
                byte[] hashFromOver = executor2.getStack().get(2); // OP_OVER复制的是签名，哈希后的值
                log.info("OP_OVER复制的元素哈希值: {}", bytesToHex(hashFromOver));
                log.info("目标公钥哈希值: {}", bytesToHex(pubKeyHash));
                log.info("哈希对比结果: {}", Arrays.equals(hashFromOver, pubKeyHash));
            }

            // ========== 步骤7：测试3 - 修正后的OP_OVER脚本（先调整栈顺序，预期成功） ==========
            // 修复思路：先交换栈中签名和公钥的顺序，让公钥成为第二个元素
            Script fixedOverLockingScript = new Script();
            fixedOverLockingScript.addOpCode(Script.OP_SWAP); // 交换栈顶两个元素：[公钥, 签名]
            fixedOverLockingScript.addOpCode(Script.OP_OVER); // 复制第二个元素（公钥）到栈顶：[公钥, 签名, 公钥]
            fixedOverLockingScript.addOpCode(Script.OP_HASH160);
            fixedOverLockingScript.addData(pubKeyHash);
            fixedOverLockingScript.addOpCode(Script.OP_EQUALVERIFY);
            fixedOverLockingScript.addOpCode(Script.OP_SWAP); // 恢复栈顺序：[签名, 公钥]
            fixedOverLockingScript.addOpCode(Script.OP_CHECKSIG);
            log.info("\n修正后的OP_OVER锁定脚本: {}", fixedOverLockingScript.toString());

            ScriptExecutor executor3 = new ScriptExecutor();
            Script combinedFixedOver = combineScripts(unlockingScript, fixedOverLockingScript);
            boolean fixedOverResult = executor3.execute(combinedFixedOver, testTx, 0);
            log.info("修正后的OP_OVER脚本验证结果: {} (预期: true)", fixedOverResult);

            // ========== 步骤8：栈操作原理对比 ==========
            log.info("\n===== 核心原理对比 =====");
            log.info("1. 解锁脚本执行后栈状态: [签名, 公钥]");
            log.info("2. OP_DUP: 复制栈顶（公钥）→ [签名, 公钥, 公钥] → 哈希后与目标一致");
            log.info("3. 直接OP_OVER: 复制第二个元素（签名）→ [签名, 公钥, 签名] → 哈希后与目标不一致 → 验证失败");
            log.info("4. 修正后OP_OVER: OP_SWAP→[公钥, 签名] → OP_OVER→[公钥, 签名, 公钥] → 哈希后一致 → 验证成功");

        } catch (Exception e) {
            log.error("P2PKH测试过程出错: ", e);
        }
    }



    // ========== 新增main方法 ==========
    public static void test1() {
        try {

            // ========== 步骤1：生成Ed25519密钥对 ==========
            log.info("===== 1. 生成Ed25519密钥对 =====");

            byte[] privateKey = hexToBytes("1ed02d6a330a20ae961a2bd921e80b9373874dd248d354e7c2410f67cef5cbc8");
            byte[] publicKey = hexToBytes("dd5dbac2d2e1a7a9b849adc1dc89acb44c9f9518259933d031950cf2f1e17cd1");
            byte[] pubKeyHash = Sha.applyRIPEMD160(Sha.applySHA256(publicKey)); // 公钥哈希 = RIPEMD160(SHA256(公钥))

            log.info("私钥: {}", bytesToHex(privateKey));
            log.info("公钥: {}", bytesToHex(publicKey));
            log.info("公钥哈希: {}", bytesToHex(pubKeyHash));

            // ========== 步骤2：创建测试交易 ==========
            log.info("\n===== 2. 创建测试交易 =====");
            Transaction tx = new Transaction();

            // 添加交易输入
            TxInput txInput1 = new TxInput();
            txInput1.setTxId(hexToBytes("06071a12ea4acf1b977fdf0c13c4adc7d65eb6cc5bb6f931fcaddb15e73df1cd")); // 前序交易哈希（测试用空值）
            txInput1.setIndex(0);
            txInput1.setSequence(0xFFFFFFFFL);

            TxInput txInput2 = new TxInput();
            txInput2.setTxId(hexToBytes("e9e11fb89505f0efb292316cb54d504d3ff9a26777c997c6469d7ae097b8fb2d")); // 前序交易哈希（测试用空值）
            txInput2.setIndex(0);
            txInput2.setSequence(0xFFFFFFFFL);

            ArrayList<TxInput> txInputs = new ArrayList<>();
            txInputs.add(txInput1);
            txInputs.add(txInput2);
            tx.setInputs(txInputs);


            // 添加交易输出
            TxOutput txOutput1 = new TxOutput();
            txOutput1.setValue(5000000000L); // 输出金额（单位：satoshi）
            txOutput1.setScriptPubKey(hexToBytes("76934d8111b97796af8d22443628399ebb612124")); // 锁定脚本后续构造
            TxOutput txOutput2 = new TxOutput();
            txOutput2.setValue(4999995780L); // 输出金额（单位：satoshi）
            txOutput2.setScriptPubKey(hexToBytes("a57f11a1cbb1d0a7841b5b1c374fbc46f42f8c56")); // 锁定脚本后续构造
            ArrayList<TxOutput> txOutputs = new ArrayList<>();
            txOutputs.add(txOutput1);
            txOutputs.add(txOutput2);
            tx.setOutputs(txOutputs);
            tx.setVersion(1);
            tx.setLockTime(0);


            //根据模拟交易构建UTXOMap
            Map<String, UTXO> utxoMap = new HashMap<>();
            String txId1 = "06071a12ea4acf1b977fdf0c13c4adc7d65eb6cc5bb6f931fcaddb15e73df1cd";
            int index1 = 0;
            long value1 = 5000000000L; // 注意加L避免整数溢出（50亿satoshi）
            byte[] scriptPubKey1 = hexToBytes("a57f11a1cbb1d0a7841b5b1c374fbc46f42f8c56");
            UTXO utxo1 = new UTXO();
            utxo1.setTxId(hexToBytes(txId1));          // 前序交易ID
            utxo1.setIndex(index1);        // 前序交易输出索引
            utxo1.setValue(value1);        // 输出金额（satoshi）
            utxo1.setScript(scriptPubKey1); // 锁定脚本
            // UTXO唯一标识：交易ID + 输出索引（核心键规则）
            byte[] key = UTXO.getKey(hexToBytes(txId1), index1);
            utxoMap.put(bytesToHex(key), utxo1);

            // 2. 构建第二个UTXO（对应模拟交易第二个输入的前序输出）
            // 注意：原JSON中该txId疑似少1位，按原始内容处理
            String txId2 = "e9e11fb89505f0efb292316cb54d504d3ff9a26777c997c6469d7ae097b8fb2d";
            int index2 = 0;
            long value2 = 5000000000L;
            byte[] scriptPubKey2 = hexToBytes("a57f11a1cbb1d0a7841b5b1c374fbc46f42f8c56");
            UTXO utxo2 = new UTXO();
            utxo2.setTxId(hexToBytes(txId2));
            utxo2.setIndex(index2);
            utxo2.setValue(value2);
            utxo2.setScript(scriptPubKey2);
            byte[] utxoKey2 = UTXO.getKey(hexToBytes(txId2), index2);
            utxoMap.put(bytesToHex(utxoKey2), utxo2);
            tx.setUtxoMap(utxoMap);


            // ========== 步骤3：签名交易 ==========
            log.info("\n===== 3. 对交易进行签名 =====");
            byte[] sigHash = tx.prepareDataForSigning(0, hexToBytes("a57f11a1cbb1d0a7841b5b1c374fbc46f42f8c56"), SIGHASH_ALL);
            byte[] signature = Ed25519Signer.fastSign(privateKey, Sha.applySHA256(sigHash));



            log.info("签名哈希: {}", SerializeUtils.bytesToHex(sigHash));
            log.info("交易签名: {}", SerializeUtils.bytesToHex(signature));

            //实现在签名signature的后面加上1字节的SIGHASH_ALL
            byte[] signatureWithHashType = new byte[signature.length + 1];
            System.arraycopy(signature, 0, signatureWithHashType, 0, signature.length);
            signatureWithHashType[signature.length] = SIGHASH_ALL; // 追加0x01


            // ========== 步骤4：构造P2PKH脚本 ==========
            log.info("\n===== 4. 构造P2PKH解锁/锁定脚本 =====");
            // 解锁脚本：[签名] [公钥]
            Script unlockingScript = new Script();
            unlockingScript.addData(signatureWithHashType);
            unlockingScript.addData(publicKey);

            // 锁定脚本：OP_DUP OP_HASH160 [公钥哈希] OP_EQUALVERIFY OP_CHECKSIG
            Script lockingScript = new Script();
            lockingScript.addOpCode(Script.OP_DUP);
            lockingScript.addOpCode(Script.OP_HASH160);
            lockingScript.addData(pubKeyHash);
            lockingScript.addOpCode(Script.OP_EQUALVERIFY);
            lockingScript.addOpCode(Script.OP_CHECKSIG);

            log.info("解锁脚本: {}", unlockingScript.toString());
            log.info("锁定脚本: {}", lockingScript.toString());
            log.info("解锁脚本十六进制: {}", unlockingScript.toHex());
            log.info("锁定脚本十六进制: {}", lockingScript.toHex());

            // ========== 步骤5：合并脚本并执行验证 ==========
            log.info("\n===== 5. 执行脚本验证 =====");
            // 合并解锁脚本 + 锁定脚本
            Script combinedScript = combineScripts(unlockingScript, lockingScript);
            log.info("合并后脚本: {}", combinedScript.toString());

            // 创建脚本执行器并验证
            ScriptExecutor executor = new ScriptExecutor();
            boolean verifyResult = executor.execute(
                    combinedScript,
                    tx,
                    0
            );
            //验证结果
            log.info("验证结果: {}", verifyResult);



        } catch (Exception e) {
            log.error("脚本验证过程出错: ", e);
        }
    }


    //OP_DUP OP_HASH160 与 OP_OVER OP_HASH160
    public static void test2() {
        try {
            byte[] privateKey1 = hexToBytes("1ed02d6a330a20ae961a2bd921e80b9373874dd248d354e7c2410f67cef5cbc8");
            byte[] publicKey1 = hexToBytes("dd5dbac2d2e1a7a9b849adc1dc89acb44c9f9518259933d031950cf2f1e17cd1");

            byte[] privateKey2 = hexToBytes("fafe8913e58be4b29cd914317ca4bdd2e5ccc62d1fb7e25d180b1dc19b590c1f");
            byte[] publicKey2 = hexToBytes("eacdc6e4aec120c247cd35959e6e875e0a35add4cf80e9f05e6d5aa29d511f53");

            byte[] privateKey3 = hexToBytes("ac58a008c9e6c89d884b4611415750bba6b50d40da5e28cc087b590fc8a207c3");
            byte[] publicKey3 = hexToBytes("f7908979baf0c46cc37cba0d0348bf6baa99098b4e8943b0860fa9edf5e6e092");

            //谁能提供一个脚本，它的哈希值等于我指定的值，并且这个脚本能被成功执行，钱就归谁

            //测试多签 OP_2 <公钥1> <公钥2> <公钥3> OP_3 OP_CHECKMULTISIG
            //收款方创建脚本 并提供脚本的Hash160 byte[] 20字节 -> scriptHash

            //付款方 创建锁定脚本 OP_HASH160 <scriptHash> OP_EQUAL

            //收款方使用这笔钱要提供: 原始的赎回脚本（包含复杂的解锁条件，如多个公钥和签名要求）。

            Script redeemScript = new Script();
            redeemScript.addOpCode(Script.OP_2);  // 需要2个签名
            redeemScript.addData(publicKey1);     // 公钥1
            redeemScript.addData(publicKey2);     // 公钥2
            redeemScript.addData(publicKey3);     // 公钥3
            redeemScript.addOpCode(Script.OP_3);  // 总共3个公钥
            redeemScript.addOpCode(Script.OP_CHECKMULTISIG);  // 多重签名检查

            String redeemScriptHex = redeemScript.toHex();
            log.info("赎回脚本: {}", redeemScript.toString());
            log.info("赎回脚本长度: {} 字节", redeemScriptHex.length() / 2);
            log.info("赎回脚本十六进制: {}\n", redeemScriptHex);

            byte[] redeemScriptHash = Sha.applyRIPEMD160(Sha.applySHA256(redeemScript.serialize()));
            log.info("赎回脚本哈希 (20字节): {}", bytesToHex(redeemScriptHash));

            Script p2shLockingScript = new Script();
            p2shLockingScript.addOpCode(Script.OP_HASH160);
            p2shLockingScript.addData(redeemScriptHash);  // 20字节的脚本哈希
            p2shLockingScript.addOpCode(Script.OP_EQUAL);
            log.info("P2SH锁定脚本: {}", p2shLockingScript.toString());
            log.info("P2SH锁定脚本十六进制: {}\n", p2shLockingScript.toHex());

            String previousTxId = "a1b2c3d4e5f6789012345678901234567890abcdef1234567890abcdef123456";
            int previousOutputIndex = 0;
            long utxoValue = 100000000L;  // 1 BTC = 100,000,000 satoshis

            UTXO utxo = new UTXO();
            utxo.setTxId(hexToBytes(previousTxId));
            utxo.setIndex(previousOutputIndex);
            utxo.setValue(utxoValue);
            utxo.setScript(p2shLockingScript.serialize());  // 锁定脚本是P2SH脚本
            log.info("UTXO交易ID: {}", previousTxId);
            log.info("UTXO输出索引: {}", previousOutputIndex);
            log.info("UTXO金额: {} satoshis ({} BTC)", utxoValue, utxoValue / 100000000.0);
            log.info("UTXO锁定脚本: {}\n", p2shLockingScript.toString());

            // ========== 6. 创建花费交易 ==========
            log.info("======= 6. 创建花费交易 =======");
            Transaction spendingTx = new Transaction();
            spendingTx.setVersion(1);
            spendingTx.setLockTime(0);
            // 添加交易输入（引用UTXO）
            TxInput txInput = new TxInput();
            txInput.setTxId(hexToBytes(previousTxId));
            txInput.setIndex(previousOutputIndex);
            txInput.setSequence(0xFFFFFFFFL);
            // 解锁脚本稍后设置
            ArrayList<TxInput> inputs = new ArrayList<>();
            inputs.add(txInput);
            spendingTx.setInputs(inputs);
            // 添加交易输出（发送到新地址）
            TxOutput txOutput = new TxOutput();
            txOutput.setValue(utxoValue - 1000);  // 减去手续费

            // 假设发送到一个简单的P2PKH地址
            byte[] recipientPubKeyHash = hexToBytes("76934d8111b97796af8d22443628399ebb612124");
            Script recipientScript = new Script();
            recipientScript.addOpCode(Script.OP_DUP);
            recipientScript.addOpCode(Script.OP_HASH160);
            recipientScript.addData(recipientPubKeyHash);
            recipientScript.addOpCode(Script.OP_EQUALVERIFY);
            recipientScript.addOpCode(Script.OP_CHECKSIG);
            txOutput.setScriptPubKey(recipientScript.serialize());

            ArrayList<TxOutput> outputs = new ArrayList<>();
            outputs.add(txOutput);
            spendingTx.setOutputs(outputs);

            // 设置UTXO映射（用于签名）
            Map<String, UTXO> utxoMap2 = new HashMap<>();
            byte[] utxoKey = UTXO.getKey(hexToBytes(previousTxId), previousOutputIndex);
            utxoMap2.put(bytesToHex(utxoKey), utxo);
            spendingTx.setUtxoMap(utxoMap2);
            log.info("花费交易创建成功");
            log.info("输入数量: 1");
            log.info("输出数量: 1");
            log.info("手续费: 1000 satoshis\n");
            // ========== 7. 创建签名 ==========
            log.info("======= 7. 创建多重签名 =======");

            // 对于P2SH，签名时需要将锁定脚本替换为赎回脚本
            // 准备签名数据
            byte[] serialize = redeemScript.serialize();
            log.info("脚本数据{}",bytesToHex(serialize));
            log.info("脚本数据Hash160{}",bytesToHex(Sha.applyRIPEMD160(Sha.applySHA256(serialize))));

            byte[] sigHash2 = spendingTx.prepareDataForSigning(
                    0,  // 输入索引
                    p2shLockingScript.serialize(),  // 使用P2SH锁定脚本
                    SIGHASH_ALL
            );
            log.info("签名哈希: {}", bytesToHex(sigHash2).substring(0, 20) + "...");
            // 使用私钥1和私钥2进行签名（2-of-3）
            byte[] sigHashDigest = Sha.applySHA256(sigHash2);
            log.info("原始签名数据{}",bytesToHex(sigHashDigest));

            byte[] bytes = spendingTx.calculateSigHash(0,SIGHASH_ALL);
            log.info("原始签名数据2{}",bytesToHex(bytes));

            // 第一个签名
            byte[] signature1 = Ed25519Signer.fastSign(privateKey1, sigHashDigest);
            byte[] signatureWithHashType1 = new byte[signature1.length + 1];
            System.arraycopy(signature1, 0, signatureWithHashType1, 0, signature1.length);
            signatureWithHashType1[signature1.length] = SIGHASH_ALL;


            // 第二个签名
            byte[] signature2 = Ed25519Signer.fastSign(privateKey2, sigHashDigest);
            byte[] signatureWithHashType2 = new byte[signature2.length + 1];
            System.arraycopy(signature2, 0, signatureWithHashType2, 0, signature2.length);
            signatureWithHashType2[signature2.length] = SIGHASH_ALL;

            log.info("签名1 (使用私钥1): {}...", bytesToHex(signature1).substring(0, 20));
            log.info("签名2 (使用私钥2): {}...\n", bytesToHex(signature2).substring(0, 20));

            // ========== 8. 构造P2SH解锁脚本 ==========
            log.info("======= 8. 构造P2SH解锁脚本 =======");

            // 解锁脚本格式: [OP_0] [signature1] [signature2] [redeemScript]
            Script unlockingScript2 = new Script();

            // 添加两个签名（顺序必须与赎回脚本中的公钥顺序一致）
            unlockingScript2.addData(signatureWithHashType1);  // 对应公钥1
            unlockingScript2.addData(signatureWithHashType2);  // 对应公钥2
            // 添加完整的赎回脚本
            unlockingScript2.addData(redeemScript.serialize());

            log.info("解锁脚本: {}", unlockingScript2.toString());
            log.info("解锁脚本十六进制: {}\n", unlockingScript2.toHex());
            // 将解锁脚本设置到交易输入中
            txInput.setScriptSig(unlockingScript2.serialize());
            // ========== 9. 执行脚本验证 ==========
            log.info("======= 9. 执行脚本验证 =======");

            // 创建脚本执行器
            ScriptExecutor executor1 = new ScriptExecutor();


            Script combinedP2SHScript = combineScripts(unlockingScript2, p2shLockingScript);


            // 执行验证
            boolean verificationResult = executor1.execute(
                    combinedP2SHScript,
                    spendingTx,
                    0
            );

            if (verificationResult) {
                log.info("✅ 验证成功！2-of-3多重签名P2SH交易有效。");
            } else {
                log.info("❌ 验证失败！交易无效。");
            }
            // ========== 第二步：执行赎回脚本的多签逻辑 ==========

            List<Script.ScriptElement> unlockElements = new ArrayList<>(unlockingScript2.getElements());
            if (unlockElements.isEmpty()) {
                log.error("解锁脚本为空");
            }
            unlockElements.remove(unlockElements.size() - 1); // 移除最后一个元素（赎回脚本）
            Script unlockWithoutRedeem = new Script(unlockElements);

            Script combinedRedeemScript = combineScripts(unlockWithoutRedeem, redeemScript);
            // 4. 执行赎回脚本（真正的多签验证）
            ScriptExecutor redeemExecutor = new ScriptExecutor();
            boolean redeemVerify = redeemExecutor.execute(combinedRedeemScript, spendingTx, 0);
            if (!redeemVerify) {
                log.error("P2SH赎回脚本执行失败（多签验证不通过）");
            }else {
                log.info("✅ P2SH赎回脚本执行成功（多签验证通过）");
            }

        } catch (Exception e) {
            log.error("脚本验证过程出错: ", e);
        }
    }


    private static void testStackOperations() throws IOException {
        // 1. OP_DUP 测试（增加容错，先判断栈大小）
        Script dupScript = new Script();
        dupScript.addData(hexToBytes("010203"));  // 压入数据
        dupScript.addOpCode(Script.OP_DUP);       // 复制栈顶
        ScriptExecutor executor = new ScriptExecutor();
        boolean result = executor.execute(dupScript, null, 0);
        List<byte[]> stack = executor.getStack();
        log.info("OP_DUP测试 - 执行结果: {} | 栈大小: {} | 预期栈大小: 2",
                result, stack.size(), 2);

        // 修复：先判断栈大小，再访问元素，避免越界
        if (stack.size() >= 2) {
            log.info("OP_DUP栈内容 - 栈顶1: {} | 栈顶2: {}",
                    bytesToHex(stack.get(0)), bytesToHex(stack.get(1)));
        } else {
            log.warn("OP_DUP栈大小异常，实际: {}，预期: 2", stack.size());
            // 打印现有栈内容（便于调试）
            for (int i = 0; i < stack.size(); i++) {
                log.info("异常栈内容[{}]: {}", i, bytesToHex(stack.get(i)));
            }
        }

        // 2. OP_SWAP 测试（增加容错）
        Script swapScript = new Script();
        swapScript.addData(hexToBytes("01"));
        swapScript.addData(hexToBytes("02"));
        swapScript.addOpCode(Script.OP_SWAP);
        executor = new ScriptExecutor();
        executor.execute(swapScript, null, 0);
        stack = executor.getStack();
        log.info("OP_SWAP测试 - 栈大小: {} | 预期: 2", stack.size(), 2);
        if (stack.size() >= 2) {
            log.info("OP_SWAP栈内容 - 栈顶1: {} | 栈顶2: {} | 预期: 01 02",
                    bytesToHex(stack.get(0)), bytesToHex(stack.get(1)));
        } else {
            log.warn("OP_SWAP栈大小异常，实际: {}", stack.size());
        }

        // 3. OP_2DUP 测试（增加容错）
        Script twoDupScript = new Script();
        twoDupScript.addData(hexToBytes("01"));
        twoDupScript.addData(hexToBytes("02"));
        twoDupScript.addOpCode(Script.OP_2DUP);
        executor = new ScriptExecutor();
        executor.execute(twoDupScript, null, 0);
        stack = executor.getStack();
        log.info("OP_2DUP测试 - 栈大小: {} | 预期: 4", stack.size(), 4);
        if (stack.size() >= 4) {
            log.info("OP_2DUP栈内容 - {} {} {} {}",
                    bytesToHex(stack.get(0)), bytesToHex(stack.get(1)),
                    bytesToHex(stack.get(2)), bytesToHex(stack.get(3)));
        }

        // 4. OP_ROT 测试（增加容错）
        Script rotScript = new Script();
        rotScript.addData(hexToBytes("01"));
        rotScript.addData(hexToBytes("02"));
        rotScript.addData(hexToBytes("03"));
        rotScript.addOpCode(Script.OP_ROT);
        executor = new ScriptExecutor();
        executor.execute(rotScript, null, 0);
        stack = executor.getStack();
        log.info("OP_ROT测试 - 栈大小: {} | 预期: 3", stack.size(), 3);
        if (stack.size() >= 3) {
            log.info("OP_ROT栈内容 - {} {} {} | 预期: 02 03 01",
                    bytesToHex(stack.get(0)), bytesToHex(stack.get(1)), bytesToHex(stack.get(2)));
        }
    }


    /**
     * 数值操作指令测试（增加鲁棒性，修复越界问题）
     */
    private static void testNumericOperations() throws IOException {
        // 1. OP_1ADD 测试（5+1=6）
        Script oneAddScript = new Script();
        oneAddScript.addInteger(BigInteger.valueOf(5));
        oneAddScript.addOpCode(Script.OP_1ADD);
        ScriptExecutor executor = new ScriptExecutor();
        boolean executeResult = executor.execute(oneAddScript, null, 0);
        List<byte[]> stack = executor.getStack();

        // 增加判空逻辑
        if (!executeResult || stack.isEmpty()) {
            log.error("OP_1ADD测试执行失败，栈为空");
        } else {
            BigInteger result = decodeNumber(stack.get(0));
            log.info("OP_1ADD测试 - 结果: {} | 预期: 6", result, 6);
        }

        // 2. OP_ADD 测试（3+5=8）
        Script addScript = new Script();
        addScript.addInteger(BigInteger.valueOf(3));
        addScript.addInteger(BigInteger.valueOf(5));
        addScript.addOpCode(Script.OP_ADD);
        executor = new ScriptExecutor();
        executeResult = executor.execute(addScript, null, 0);
        stack = executor.getStack();
        if (!executeResult || stack.isEmpty()) {
            log.error("OP_ADD测试执行失败，栈为空");
        } else {
            BigInteger result = decodeNumber(stack.get(0));
            log.info("OP_ADD测试 - 结果: {} | 预期: 8", result, 8);
        }

        // 3. OP_NUMEQUAL 测试（10==10 → true）
        Script numEqualScript = new Script();
        numEqualScript.addInteger(BigInteger.valueOf(10));
        numEqualScript.addInteger(BigInteger.valueOf(10));
        numEqualScript.addOpCode(Script.OP_NUMEQUAL);
        executor = new ScriptExecutor();
        executeResult = executor.execute(numEqualScript, null, 0);
        stack = executor.getStack();
        if (!executeResult || stack.isEmpty()) {
            log.error("OP_NUMEQUAL测试执行失败，栈为空");
        } else {
            boolean equalResult = castToBool(stack.get(0));
            log.info("OP_NUMEQUAL测试 - 结果: {} | 预期: true", equalResult, true);
        }

        // 4. OP_LESSTHAN 测试（5<10 → true）
        Script lessThanScript = new Script();
        lessThanScript.addInteger(BigInteger.valueOf(5));
        lessThanScript.addInteger(BigInteger.valueOf(10));
        lessThanScript.addOpCode(Script.OP_LESSTHAN);
        executor = new ScriptExecutor();
        executeResult = executor.execute(lessThanScript, null, 0);
        stack = executor.getStack();
        if (!executeResult || stack.isEmpty()) {
            log.error("OP_LESSTHAN测试执行失败，栈为空");
        } else {
            boolean lessResult = castToBool(stack.get(0));
            log.info("OP_LESSTHAN测试 - 结果: {} | 预期: true", lessResult, true);
        }
    }

    /**
     * 逻辑操作指令测试（无越界问题，保留原逻辑）
     */
    private static void testLogicalOperations() throws IOException {
        // 1. OP_EQUAL 测试（相同数据）
        Script equalScript = new Script();
        equalScript.addData(hexToBytes("a1b2c3"));
        equalScript.addData(hexToBytes("a1b2c3"));
        equalScript.addOpCode(Script.OP_EQUAL);
        ScriptExecutor executor = new ScriptExecutor();
        executor.execute(equalScript, null, 0);
        List<byte[]> stack = executor.getStack();
        boolean result = castToBool(stack.get(0));
        log.info("OP_EQUAL测试（相同） - 结果: {} | 预期: true", result, true);

        // 2. OP_EQUAL 测试（不同数据）
        equalScript = new Script();
        equalScript.addData(hexToBytes("a1b2c3"));
        equalScript.addData(hexToBytes("d4e5f6"));
        equalScript.addOpCode(Script.OP_EQUAL);
        executor = new ScriptExecutor();
        executor.execute(equalScript, null, 0);
        stack = executor.getStack();
        result = castToBool(stack.get(0));
        log.info("OP_EQUAL测试（不同） - 结果: {} | 预期: false", result, false);

        // 3. OP_BOOLAND 测试（true && true → true）
        Script boolAndScript = new Script();
        boolAndScript.addInteger(BigInteger.ONE);
        boolAndScript.addInteger(BigInteger.ONE);
        boolAndScript.addOpCode(Script.OP_BOOLAND);
        executor = new ScriptExecutor();
        executor.execute(boolAndScript, null, 0);
        stack = executor.getStack();
        result = castToBool(stack.get(0));
        log.info("OP_BOOLAND测试 - 结果: {} | 预期: true", result, true);

        // 4. OP_BOOLOR 测试（true || false → true）
        Script boolOrScript = new Script();
        boolOrScript.addInteger(BigInteger.ONE);
        boolOrScript.addInteger(BigInteger.ZERO);
        boolOrScript.addOpCode(Script.OP_BOOLOR);
        executor = new ScriptExecutor();
        executor.execute(boolOrScript, null, 0);
        stack = executor.getStack();
        result = castToBool(stack.get(0));
        log.info("OP_BOOLOR测试 - 结果: {} | 预期: true", result, true);
    }


    /**
     * 加密哈希操作指令测试（无越界问题，保留原逻辑）
     */
    private static void testCryptoOperations() throws IOException {
        String hexStr = bytesToHex("hello world".getBytes(StandardCharsets.UTF_8));
        byte[] testData = hexToBytes(hexStr); // 此时 hexStr 是偶数长度的十六进制字符串

        // 1. OP_SHA256 测试
        Script sha256Script = new Script();
        sha256Script.addData(testData);
        sha256Script.addOpCode(Script.OP_SHA256);
        ScriptExecutor executor = new ScriptExecutor();
        executor.execute(sha256Script, null, 0);
        List<byte[]> stack = executor.getStack();
        String sha256Result = bytesToHex(stack.get(0));
        log.info("OP_SHA256测试 - 结果: {} | 预期长度: 64", sha256Result, 64);

        // 2. OP_HASH160 测试
        Script hash160Script = new Script();
        hash160Script.addData(testData);
        hash160Script.addOpCode(Script.OP_HASH160);
        executor = new ScriptExecutor();
        executor.execute(hash160Script, null, 0);
        stack = executor.getStack();
        String hash160Result = bytesToHex(stack.get(0));
        log.info("OP_HASH160测试 - 结果: {} | 预期长度: 40", hash160Result, 40);

        // 3. OP_RIPEMD160 测试
        Script ripemd160Script = new Script();
        ripemd160Script.addData(testData);
        ripemd160Script.addOpCode(Script.OP_RIPEMD160);
        executor = new ScriptExecutor();
        executor.execute(ripemd160Script, null, 0);
        stack = executor.getStack();
        String ripemd160Result = bytesToHex(stack.get(0));
        log.info("OP_RIPEMD160测试 - 结果: {} | 预期长度: 40", ripemd160Result, 40);
    }

    /**
     * 条件控制指令测试（无越界问题，保留原逻辑）
     */
    private static void testConditionalOperations() throws IOException {
        // 1. OP_IF 真分支测试
        Script ifTrueScript = new Script();
        // 手动编码 1 为正确字节数组，替换直接addInteger(BigInteger.ONE)
        byte[] encoded1 = encodeNumber(BigInteger.ONE);
        ifTrueScript.addData(encoded1); // 改用addData添加编码后的字节数组
        ifTrueScript.addOpCode(Script.OP_IF);
        // 手动编码 100 为正确字节数组
        byte[] encoded100 = encodeNumber(BigInteger.valueOf(100));
        ifTrueScript.addData(encoded100);
        ifTrueScript.addOpCode(Script.OP_ENDIF);

        ScriptExecutor executor = new ScriptExecutor();
        executor.execute(ifTrueScript, null, 0);
        List<byte[]> stack = executor.getStack();
        BigInteger result = decodeNumber(stack.get(0));
        log.info("OP_IF真分支测试 - 结果: {} | 预期: 100", result, 100);

        // 2. OP_IF+OP_ELSE 假分支测试
        Script ifFalseScript = new Script();
        // 手动编码 0 为正确字节数组（空数组）
        byte[] encoded0 = encodeNumber(BigInteger.ZERO);
        ifFalseScript.addData(encoded0);
        ifFalseScript.addOpCode(Script.OP_IF);
        // 编码 100（不会执行，仅占位）
        ifFalseScript.addData(encoded100);
        ifFalseScript.addOpCode(Script.OP_ELSE);
        // 手动编码 200 为正确字节数组（关键修复）
        byte[] encoded200 = encodeNumber(BigInteger.valueOf(200));
        ifFalseScript.addData(encoded200);
        ifFalseScript.addOpCode(Script.OP_ENDIF);

        executor = new ScriptExecutor();
        executor.execute(ifFalseScript, null, 0);
        stack = executor.getStack();
        result = decodeNumber(stack.getFirst());

        log.info("栈内容：{}", stack.size());
        log.info("OP_IF假分支测试 - 结果: {} | 预期: 200", result, 200);
    }

    private static void testSpecialOperations() throws IOException {
        byte[] testData = hexToBytes("a1b2c3d4");

        // 1. OP_SIZE 测试（数据长度4）
        Script sizeScript = new Script();
        sizeScript.addData(testData);  // 原始数据无需编码，直接添加
        sizeScript.addOpCode(Script.OP_SIZE);
        ScriptExecutor executor = new ScriptExecutor();
        executor.execute(sizeScript, null, 0);
        List<byte[]> stack = executor.getStack();
        // 修复：取栈顶元素（列表最后一个元素），而非栈底（列表第一个）
        BigInteger result = decodeNumber(stack.get(stack.size() - 1));
        log.info("OP_SIZE测试 - 结果: {} | 预期: 4", result, 4);

        // 2. OP_NOT 测试（非0 → 0）
        Script notScript = new Script();
        byte[] encoded5 = encodeNumber(BigInteger.valueOf(5));
        notScript.addData(encoded5);
        notScript.addOpCode(Script.OP_NOT);
        executor = new ScriptExecutor();
        executor.execute(notScript, null, 0);
        stack = executor.getStack();
        result = decodeNumber(stack.get(stack.size() - 1)); // 统一取栈顶
        log.info("OP_NOT测试（非0） - 结果: {} | 预期: 0", result, 0);

        // 3. OP_0NOTEQUAL 测试（0 → false）
        Script zeroNotEqualScript = new Script();
        byte[] encoded0 = encodeNumber(BigInteger.ZERO);
        zeroNotEqualScript.addData(encoded0);
        zeroNotEqualScript.addOpCode(Script.OP_0NOTEQUAL);
        executor = new ScriptExecutor();
        executor.execute(zeroNotEqualScript, null, 0);
        stack = executor.getStack();
        boolean boolResult = castToBool(stack.get(stack.size() - 1)); // 统一取栈顶
        log.info("OP_0NOTEQUAL测试（0） - 结果: {} | 预期: false", boolResult, false);
    }

    /**
     * 边界情况测试（无越界问题，保留原逻辑）
     */
    private static void testEdgeCases() throws IOException {
        // 1. OP_DUP 栈下溢测试（预期失败）
        Script dupUnderflowScript = new Script();
        dupUnderflowScript.addOpCode(Script.OP_DUP);  // 空栈执行DUP
        ScriptExecutor executor = new ScriptExecutor();
        boolean result = executor.execute(dupUnderflowScript, null, 0);
        log.info("OP_DUP栈下溢测试 - 结果: {} | 预期: false", result, false);

        // 2. 无效操作码测试（预期失败）
        Script invalidOpScript = new Script();
        invalidOpScript.addOpCode(0xFF);  // 无效操作码
        executor = new ScriptExecutor();
        result = executor.execute(invalidOpScript, null, 0);
        log.info("无效操作码测试 - 结果: {} | 预期: false", result, false);

        // 3. OP_RETURN 测试（立即失败）
        Script returnScript = new Script();
        returnScript.addOpCode(Script.OP_RETURN);
        executor = new ScriptExecutor();
        result = executor.execute(returnScript, null, 0);
        log.info("OP_RETURN测试 - 结果: {} | 预期: false", result, false);
    }



    /**
     * OP_CHECKLOCKTIMEVERIFY (CLTV) 和 OP_CHECKSEQUENCEVERIFY (CSV) 测试
     * 覆盖：合法场景、类型不匹配、数值不足、sequence失效等核心场景
     */
    private static void testTimeLockOperations() throws IOException {
        // ========== 初始化基础数据 ==========
        // 1. 生成测试用密钥对（仅用于构造合法交易，不影响时间锁定逻辑）
        byte[] privateKey = hexToBytes("1ed02d6a330a20ae961a2bd921e80b9373874dd248d354e7c2410f67cef5cbc8");
        byte[] publicKey = hexToBytes("dd5dbac2d2e1a7a9b849adc1dc89acb44c9f9518259933d031950cf2f1e17cd1");
        byte[] pubKeyHash = Sha.applyRIPEMD160(Sha.applySHA256(publicKey));

        // 2. 构造基础交易（用于时间锁定测试）
        Transaction baseTx = new Transaction();
        baseTx.setVersion(1);
        baseTx.setLockTime(0); // 初始锁定时间，后续按需修改

        // 添加测试输入（核心：控制sequence和UTXO）
        TxInput testInput = new TxInput();
        testInput.setTxId(hexToBytes("06071a12ea4acf1b977fdf0c13c4adc7d65eb6cc5bb6f931fcaddb15e73df1cd"));
        testInput.setIndex(0);
        testInput.setSequence(0xFFFFFFFFL); // 默认最终确认，后续按需修改
        baseTx.getInputs().add(testInput);

        // 添加测试输出（避免交易无输出）
        TxOutput testOutput = new TxOutput();
        testOutput.setValue(100000000L);
        testOutput.setScriptPubKey(hexToBytes("76934d8111b97796af8d22443628399ebb612124"));
        baseTx.getOutputs().add(testOutput);

        // 构造UTXO映射（避免脚本执行时UTXO为空）
        Map<String, UTXO> utxoMap = new HashMap<>();
        UTXO utxo = new UTXO();
        utxo.setTxId(testInput.getTxId());
        utxo.setIndex(testInput.getIndex());
        utxo.setValue(100000000L);
        utxo.setScript(hexToBytes("76a914" + bytesToHex(pubKeyHash) + "88ac")); // 简单P2PKH锁定脚本
        utxoMap.put(bytesToHex(UTXO.getKey(testInput.getTxId(), testInput.getIndex())), utxo);
        baseTx.setUtxoMap(utxoMap);

        // ========== 测试1：OP_CHECKLOCKTIMEVERIFY (CLTV) ==========
        log.info("\n===== 1. OP_CHECKLOCKTIMEVERIFY (CLTV) 测试 =====");

        // 场景1.1：合法场景（区块高度匹配：交易锁定时间≥脚本锁定时间）
        {
            // 脚本逻辑：[300000] OP_CHECKLOCKTIMEVERIFY
            Script cltvValidScript = new Script();
            cltvValidScript.addData(encodeNumber(BigInteger.valueOf(300000))); // 脚本指定锁定高度300000
            cltvValidScript.addOpCode(Script.OP_CHECKLOCKTIMEVERIFY);

            // 交易锁定时间设为300000（≥脚本值，且为区块高度）
            Transaction tx1 = copyTransaction(baseTx);
            tx1.setLockTime(300000);
            tx1.getInputs().get(0).setSequence(0xFFFFFFFE); // 非最终确认（禁用CLTV失效规则）

            ScriptExecutor executor1 = new ScriptExecutor();
            boolean result1 = executor1.execute(cltvValidScript, tx1, 0);
            log.info("CLTV合法场景（区块高度） - 结果: {} | 预期: true", result1, true);
        }

        // 场景1.2：非法场景（交易锁定时间 < 脚本锁定时间）
        {
            Script cltvInvalidValueScript = new Script();
            cltvInvalidValueScript.addData(encodeNumber(BigInteger.valueOf(300000)));
            cltvInvalidValueScript.addOpCode(Script.OP_CHECKLOCKTIMEVERIFY);

            Transaction tx2 = copyTransaction(baseTx);
            tx2.setLockTime(299999); // 小于脚本指定的300000
            tx2.getInputs().get(0).setSequence(0xFFFFFFFE);

            ScriptExecutor executor2 = new ScriptExecutor();
            boolean result2 = executor2.execute(cltvInvalidValueScript, tx2, 0);
            log.info("CLTV非法场景（数值不足） - 结果: {} | 预期: false", result2, false);
        }

        // 场景1.3：非法场景（类型不匹配：脚本=区块高度，交易=时间戳）
        {
            Script cltvTypeMismatchScript = new Script();
            cltvTypeMismatchScript.addData(encodeNumber(BigInteger.valueOf(300000))); // 区块高度（<500000000）
            cltvTypeMismatchScript.addOpCode(Script.OP_CHECKLOCKTIMEVERIFY);

            Transaction tx3 = copyTransaction(baseTx);
            tx3.setLockTime(1711234567L); // 时间戳（≥500000000）
            tx3.getInputs().get(0).setSequence(0xFFFFFFFE);

            ScriptExecutor executor3 = new ScriptExecutor();
            boolean result3 = executor3.execute(cltvTypeMismatchScript, tx3, 0);
            log.info("CLTV非法场景（类型不匹配） - 结果: {} | 预期: false", result3, false);
        }

        // 场景1.4：CLTV失效（sequence=0xFFFFFFFF）
        {
            Script cltvDisabledScript = new Script();
            cltvDisabledScript.addData(encodeNumber(BigInteger.valueOf(300000)));
            cltvDisabledScript.addOpCode(Script.OP_CHECKLOCKTIMEVERIFY);

            Transaction tx4 = copyTransaction(baseTx);
            tx4.setLockTime(299999); // 本应不满足，但sequence失效
            tx4.getInputs().get(0).setSequence(0xFFFFFFFFL); // 最终确认，CLTV失效

            ScriptExecutor executor4 = new ScriptExecutor();
            boolean result4 = executor4.execute(cltvDisabledScript, tx4, 0);
            log.info("CLTV失效场景（sequence=0xFFFFFFFF） - 结果: {} | 预期: true", result4, true);
        }

        // ========== 测试2：OP_CHECKSEQUENCEVERIFY (CSV) ==========
        log.info("\n===== 2. OP_CHECKSEQUENCEVERIFY (CSV) 测试 =====");

        // 场景2.1：合法场景（相对区块高度匹配）
        {
            // 脚本指定：相对高度100（低30位=100，高2位=0→区块高度）
            long csvValue = 100; // 0x00000064
            Script csvValidScript = new Script();
            csvValidScript.addData(encodeNumber(BigInteger.valueOf(csvValue)));
            csvValidScript.addOpCode(Script.OP_CHECKSEQUENCEVERIFY);

            // 输入sequence：0x00000064（低30位=100，高2位=0）≥ 脚本值
            Transaction tx5 = copyTransaction(baseTx);
            tx5.getInputs().get(0).setSequence(0x00000064L);

            ScriptExecutor executor5 = new ScriptExecutor();
            boolean result5 = executor5.execute(csvValidScript, tx5, 0);
            log.info("CSV合法场景（区块高度） - 结果: {} | 预期: true", result5, true);
        }

        // 场景2.2：非法场景（数值不足）
        {
            Script csvInvalidValueScript = new Script();
            csvInvalidValueScript.addData(encodeNumber(BigInteger.valueOf(100))); // 脚本要求100
            csvInvalidValueScript.addOpCode(Script.OP_CHECKSEQUENCEVERIFY);

            // 输入sequence=99 < 脚本值100
            Transaction tx6 = copyTransaction(baseTx);
            tx6.getInputs().get(0).setSequence(0x00000063L);

            ScriptExecutor executor6 = new ScriptExecutor();
            boolean result6 = executor6.execute(csvInvalidValueScript, tx6, 0);
            log.info("CSV非法场景（数值不足） - 结果: {} | 预期: false", result6, false);
        }

        // 场景2.3：非法场景（类型不匹配：脚本=区块高度，输入=时间戳）
        {
            Script csvTypeMismatchScript = new Script();
            csvTypeMismatchScript.addData(encodeNumber(BigInteger.valueOf(100))); // 高2位=0→区块高度
            csvTypeMismatchScript.addOpCode(Script.OP_CHECKSEQUENCEVERIFY);

            // 输入sequence：高2位=1→时间戳，低30位=100
            long sequenceTime = 0x40000064L; // 高2位=1，低30位=100
            Transaction tx7 = copyTransaction(baseTx);
            tx7.getInputs().get(0).setSequence(sequenceTime);

            ScriptExecutor executor7 = new ScriptExecutor();
            boolean result7 = executor7.execute(csvTypeMismatchScript, tx7, 0);
            log.info("CSV非法场景（类型不匹配） - 结果: {} | 预期: false", result7, false);
        }

        // 场景2.4：合法场景（相对时间戳匹配）
        {
            // 脚本指定：相对时间戳20（高2位=1→时间戳，低30位=20 → 20*512=10240秒）
            long csvTimeValue = 0x40000014L; // 高2位=1，低30位=20
            Script csvTimeValidScript = new Script();
            csvTimeValidScript.addData(encodeNumber(BigInteger.valueOf(csvTimeValue)));
            csvTimeValidScript.addOpCode(Script.OP_CHECKSEQUENCEVERIFY);

            // 输入sequence：0x40000014 ≥ 脚本值
            Transaction tx8 = copyTransaction(baseTx);
            tx8.getInputs().get(0).setSequence(0x40000014L);

            ScriptExecutor executor8 = new ScriptExecutor();
            boolean result8 = executor8.execute(csvTimeValidScript, tx8, 0);
            log.info("CSV合法场景（时间戳） - 结果: {} | 预期: true", result8, true);
        }
    }


    /**
     * 辅助方法：复制交易（避免修改原交易影响后续测试）
     */
    private static Transaction copyTransaction(Transaction original) {
        Transaction copy = new Transaction();
        copy.setVersion(original.getVersion());
        copy.setLockTime(original.getLockTime());
        copy.setInputs(new ArrayList<>(original.getInputs()));
        copy.setOutputs(new ArrayList<>(original.getOutputs()));
        copy.setUtxoMap(new HashMap<>(original.getUtxoMap()));
        return copy;
    }


    //{
    //    "version": 1,
    //    "lockTime": 0,
    //    "inputs": [
    //        {
    //            "txId": "3d41ded755686020e0798e7380cb27cf9fc66c2728366ce9bbab10dca74e7f87",
    //            "index": 0,
    //            "scriptSig": "c4b304aa374747e1684f7d67adfc17ca6c98bea45f43616bbbad3b9d6f88165938f6796801a0393eefeb00aeb871f5c326ee460d242950f8ca7be49829af770e0130641fd84c1e33c880dd96abfb3b83b93b8385d55a1d1636e2cca13c5561c5db"
    //        }
    //    ],
    //    "outputs": [
    //        {
    //            "value": 1200000000,
    //            "scriptPubKey": "a57f11a1cbb1d0a7841b5b1c374fbc46f42f8c56"
    //        },
    //        {
    //            "value": 3799990000,
    //            "scriptPubKey": "76934d8111b97796af8d22443628399ebb612124"
    //        }
    //    ]
    //}


}
