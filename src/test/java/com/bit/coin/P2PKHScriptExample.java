package com.bit.coin;

import org.bitcoinj.core.*;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptOpCodes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class P2PKHScriptExample {

    @Test
    public void testP2PKHScript() {
        NetworkParameters params = MainNetParams.get();

        // 1. 创建密钥对
        ECKey key = new ECKey();
        System.out.println("私钥(WIF): " + key.getPrivateKeyAsWiF(params));
        System.out.println("公钥(HEX): " + Utils.HEX.encode(key.getPubKey()));



        System.out.println("私钥(WIF)长度: " + key.getPrivKey());
        System.out.println("公钥(HEX)长度: " + key.getPubKey().length);


        // 2. 创建P2PKH锁定脚本
        Address address = LegacyAddress.fromKey(params, key);
        Script lockingScript = ScriptBuilder.createOutputScript(address);


        System.out.println("\n=== 锁定脚本 (scriptPubKey) ===");
        System.out.println("地址: " + address);
        System.out.println("脚本字节: " + Utils.HEX.encode(lockingScript.getProgram()));
        System.out.println("脚本反汇编: " + lockingScript);

        // 3. 创建模拟的UTXO（前一笔交易的输出）
        Transaction previousTx = new Transaction(params);
        previousTx.addOutput(Coin.valueOf(100_000), lockingScript);

        // 创建UTXO引用
        Sha256Hash prevTxHash = previousTx.getTxId();
        int outputIndex = 0;  // 第一个输出
        TransactionOutput utxoOutput = previousTx.getOutput(outputIndex); // 获取要花费的UTXO输出

        // 4. 创建要签名的交易（花费UTXO）
        Transaction spendingTx = new Transaction(params);

        // 添加输入，指向之前的UTXO
        TransactionInput input = spendingTx.addInput(
                prevTxHash,
                outputIndex,
                new Script(new byte[0])  // 初始为空脚本
        );

        // !!! 关键修复：将输入连接到对应的UTXO输出 !!!
        // 这是解决"Not connected"错误的核心步骤
        input.connect(previousTx.getOutput(outputIndex));

        // 添加输出（发送到新地址）
        ECKey receiverKey = new ECKey();
        Address receiverAddress = LegacyAddress.fromKey(params, receiverKey);
        spendingTx.addOutput(Coin.valueOf(90_000), receiverAddress);

        // 5. 创建签名哈希
        Sha256Hash sigHash = spendingTx.hashForSignature(
                0,
                lockingScript,
                Transaction.SigHash.ALL,
                false
        );

        System.out.println("\n签名哈希: " + sigHash.toString());

        // 6. 使用私钥签名
        ECKey.ECDSASignature ecdsaSig = key.sign(sigHash);
        TransactionSignature txSig = new TransactionSignature(
                ecdsaSig,
                Transaction.SigHash.ALL,
                false
        );

        byte[] bytes = txSig.encodeToBitcoin();
        System.out.println("数组长度{}"+bytes.length);
        byte[] pubKey = key.getPubKey();

        // 7. 创建解锁脚本 (包含签名和公钥)
        Script unlockingScript = new ScriptBuilder()
                .data(txSig.encodeToBitcoin())
                .data(key.getPubKey())
                .build();

        System.out.println("\n=== 解锁脚本 (scriptSig) ===");
        System.out.println("脚本字节: " + Utils.HEX.encode(unlockingScript.getProgram()));
        System.out.println("脚本反汇编: " + unlockingScript);
        System.out.println("签名: " + Utils.HEX.encode(txSig.encodeToBitcoin()));
        System.out.println("公钥: " + Utils.HEX.encode(key.getPubKey()));

        // 8. 设置输入的解锁脚本
        input.setScriptSig(unlockingScript);

        Sha256Hash txId = spendingTx.getTxId();
        System.out.println("交易ID"+txId);

        // 9. 测试脚本执行
        System.out.println("\n=== 脚本执行测试 ===");

        try {
            // 现在验证应该可以正常进行
            // verify() 方法会执行标准的P2PKH脚本验证流程：
            // 1. 将解锁脚本（签名和公钥）压入栈
            // 2. 执行锁定脚本（OP_DUP, OP_HASH160, 公钥哈希, OP_EQUALVERIFY, OP_CHECKSIG）
            // 3. 验证签名和公钥哈希是否匹配[1](@ref)
            input.verify(utxoOutput); // 显式传入对应的UTXO进行验证

            System.out.println("✅ 脚本验证成功！P2PKH交易有效。");

            // 可选：进一步验证脚本执行结果
            Script script = unlockingScript;
            Script chunks = lockingScript;
            int flags = 0; // 使用标准验证标志
            // 这里可以添加更详细的脚本执行步骤日志

        } catch (Exception e) {
            System.err.println("❌ 脚本验证失败: " + e.getMessage());
            e.printStackTrace();
        }



    }


}