package com.bit.coin;

import org.bitcoinj.core.*;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptPattern;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

public class P2WPKHScriptExample {

    @Test
    public void testP2WPKHScript() {
        NetworkParameters params = MainNetParams.get();

        // 1. 创建密钥对（必须使用压缩公钥）
        ECKey key = new ECKey();
        System.out.println("私钥(WIF): " + key.getPrivateKeyAsWiF(params));
        System.out.println("公钥(HEX): " + Utils.HEX.encode(key.getPubKey()));

        // 2. 创建P2WPKH锁定脚本
        Script lockingScript = ScriptBuilder.createP2WPKHOutputScript(key);
        Address segwitAddress = SegwitAddress.fromKey(params, key);

        System.out.println("\n=== P2WPKH锁定脚本 ===");
        System.out.println("SegWit地址: " + segwitAddress);
        System.out.println("脚本字节: " + Utils.HEX.encode(lockingScript.getProgram()));

        // 3. 创建模拟的UTXO
        Transaction previousTx = new Transaction(params);
        previousTx.addOutput(Coin.valueOf(100_000), lockingScript);

        Sha256Hash prevTxHash = previousTx.getTxId();
        int outputIndex = 0;
        TransactionOutput utxoOutput = previousTx.getOutput(outputIndex);

        // 4. 创建花费交易
        Transaction spendingTx = new Transaction(params);

        // ✅ 正确创建输入并连接
        TransactionInput input = spendingTx.addInput(prevTxHash, outputIndex, new ScriptBuilder().build());

        // ✅ 关键修复：使用connect方法
        input.connect(utxoOutput);

        // 添加输出
        ECKey receiverKey = new ECKey();
        Address receiverAddress = SegwitAddress.fromKey(params, receiverKey);
        spendingTx.addOutput(Coin.valueOf(90_000), receiverAddress);

        // 5. 创建签名哈希
        try {
            Script scriptCode = ScriptBuilder.createP2PKHOutputScript(key);

            Sha256Hash sigHash = spendingTx.hashForWitnessSignature(
                    0,
                    scriptCode,
                    utxoOutput.getValue(),
                    Transaction.SigHash.ALL,
                    false
            );

            System.out.println("\n签名哈希: " + sigHash.toString());

            // 6. 使用私钥签名
            ECKey.ECDSASignature ecdsaSig = key.sign(sigHash);
            TransactionSignature txSig = new TransactionSignature(ecdsaSig, Transaction.SigHash.ALL, false);

            // 7. 创建见证数据
            TransactionWitness witness = new TransactionWitness(2);
            witness.setPush(0, txSig.encodeToBitcoin());
            witness.setPush(1, key.getPubKey());
            input.setWitness(witness);

            System.out.println("\n=== P2WPKH见证数据 ===");
            System.out.println("签名: " + Utils.HEX.encode(txSig.encodeToBitcoin()));
            System.out.println("公钥: " + Utils.HEX.encode(key.getPubKey()));

            // 8. 验证交易
            System.out.println("\n=== P2WPKH脚本执行测试 ===");

            boolean isValid = verifyP2WPKH(input, utxoOutput, lockingScript, key);

            if (isValid) {
                System.out.println("✅ P2WPKH验证成功！");
            } else {
                System.out.println("❌ P2WPKH验证失败！");
            }

        } catch (Exception e) {
            System.err.println("❌ 处理过程中发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 手动验证P2WPKH交易
     */
    private boolean verifyP2WPKH(TransactionInput input,
                                 TransactionOutput utxoOutput,
                                 Script lockingScript,
                                 ECKey expectedKey) {
        try {
            // 验证锁定脚本是P2WPKH格式
            if (!ScriptPattern.isP2WPKH(lockingScript)) {
                System.err.println("不是有效的P2WPKH锁定脚本");
                return false;
            }

            // 获取见证数据
            TransactionWitness witness = input.getWitness();
            if (witness.getPushCount() != 2) {
                System.err.println("见证数据应该包含2个元素");
                return false;
            }

            // 提取签名和公钥
            byte[] signature = witness.getPush(0);
            byte[] pubKey = witness.getPush(1);

            // 提取并验证哈希
            byte[] extractedHash = ScriptPattern.extractHashFromP2WH(lockingScript);
            byte[] expectedHash = Utils.sha256hash160(pubKey);

            if (!Arrays.equals(expectedHash, extractedHash)) {
                System.err.println("公钥哈希不匹配");
                return false;
            }

            // 验证公钥匹配
            byte[] expectedPubKeyHash = expectedKey.getPubKeyHash();
            if (!Arrays.equals(expectedPubKeyHash, expectedHash)) {
                System.err.println("预期公钥哈希不匹配");
                return false;
            }

            System.out.println("✅ 所有验证通过");
            return true;

        } catch (Exception e) {
            System.err.println("验证过程中发生错误: " + e.getMessage());
            return false;
        }
    }
}