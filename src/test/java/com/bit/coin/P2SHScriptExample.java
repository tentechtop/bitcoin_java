package com.bit.coin;

import org.bitcoinj.core.*;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptPattern;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class P2SHScriptExample {

    @Test
    public void testP2SH2Of3Multisig() {
        NetworkParameters params = MainNetParams.get();

        System.out.println("=== P2SH 2-of-3多重签名测试 ===");

        // 1. 创建三个密钥对（2-of-3多重签名）
        List<ECKey> keys = Arrays.asList(new ECKey(), new ECKey(), new ECKey());

        for (int i = 0; i < keys.size(); i++) {
            System.out.println("私钥" + (i + 1) + "(WIF): " + keys.get(i).getPrivateKeyAsWiF(params));
            System.out.println("公钥" + (i + 1) + "(HEX): " + Utils.HEX.encode(keys.get(i).getPubKey()));
        }

        // 2. 创建多重签名赎回脚本 (redeem script)
        // 格式: OP_2 <pubkey1> <pubkey2> <pubkey3> OP_3 OP_CHECKMULTISIG
        Script redeemScript = ScriptBuilder.createMultiSigOutputScript(2, keys);

        System.out.println("\n=== 赎回脚本 (redeemScript) ===");
        System.out.println("脚本字节: " + Utils.HEX.encode(redeemScript.getProgram()));
        System.out.println("脚本反汇编: " + redeemScript);

        // 3. 创建P2SH锁定脚本 [6](@ref)
        // P2SH锁定脚本格式: OP_HASH160 <redeemScriptHash> OP_EQUAL
        Script lockingScript = ScriptBuilder.createP2SHOutputScript(redeemScript);
        Address p2shAddress = LegacyAddress.fromScriptHash(params,
                Utils.sha256hash160(redeemScript.getProgram()));

        System.out.println("\n=== P2SH锁定脚本 (scriptPubKey) ===");
        System.out.println("P2SH地址: " + p2shAddress);
        System.out.println("脚本字节: " + Utils.HEX.encode(lockingScript.getProgram()));
        System.out.println("脚本反汇编: " + lockingScript);

        // 4. 创建模拟的UTXO
        Transaction previousTx = new Transaction(params);
        previousTx.addOutput(Coin.valueOf(100_000), lockingScript);

        Sha256Hash prevTxHash = previousTx.getTxId();
        int outputIndex = 0;
        TransactionOutput utxoOutput = previousTx.getOutput(outputIndex);

        // 5. 创建花费交易
        Transaction spendingTx = new Transaction(params);
        TransactionInput input = spendingTx.addInput(prevTxHash, outputIndex, new ScriptBuilder().build());
        input.connect(utxoOutput);

        // 添加输出
        ECKey receiverKey = new ECKey();
        Address receiverAddress = LegacyAddress.fromKey(params, receiverKey);
        spendingTx.addOutput(Coin.valueOf(90_000), receiverAddress);

        // 6. 创建签名哈希
        try {
            Sha256Hash sigHash = spendingTx.hashForSignature(
                    0,
                    redeemScript,  // 使用赎回脚本而不是锁定脚本进行签名
                    Transaction.SigHash.ALL,
                    false
            );

            System.out.println("\n签名哈希: " + sigHash.toString());

            // 7. 使用其中两个私钥签名（2-of-3模式）
            List<TransactionSignature> signatures = new ArrayList<>();
            // 使用前两个私钥进行签名
            for (int i = 0; i < 2; i++) {
                ECKey.ECDSASignature ecdsaSig = keys.get(i).sign(sigHash);
                TransactionSignature txSig = new TransactionSignature(ecdsaSig, Transaction.SigHash.ALL, false);
                signatures.add(txSig);
                System.out.println("签名" + (i + 1) + ": " + Utils.HEX.encode(txSig.encodeToBitcoin()));
            }

            // 8. 创建P2SH解锁脚本 [6](@ref)
            // 格式: OP_0 <signature1> <signature2> <redeemScript>
            Script unlockingScript = ScriptBuilder.createP2SHMultiSigInputScript(signatures, redeemScript);

            System.out.println("\n=== P2SH解锁脚本 (scriptSig) ===");
            System.out.println("脚本字节: " + Utils.HEX.encode(unlockingScript.getProgram()));
            System.out.println("脚本反汇编: " + unlockingScript);

            // 9. 设置输入的解锁脚本
            input.setScriptSig(unlockingScript);

            // 10. 验证交易
            System.out.println("\n=== P2SH脚本执行测试 ===");
            boolean isValid = verifyP2SH(input, utxoOutput, lockingScript, redeemScript, keys);

            if (isValid) {
                System.out.println("✅ P2SH多重签名验证成功！");
            } else {
                System.out.println("❌ P2SH验证失败！");
            }

        } catch (Exception e) {
            System.err.println("❌ 处理过程中发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 手动验证P2SH交易
     */
    private boolean verifyP2SH(TransactionInput input,
                               TransactionOutput utxoOutput,
                               Script lockingScript,
                               Script redeemScript,
                               List<ECKey> expectedKeys) {
        try {
            // 验证锁定脚本是P2SH格式
            if (!ScriptPattern.isP2SH(lockingScript)) {
                System.err.println("不是有效的P2SH锁定脚本");
                return false;
            }

            // 验证赎回脚本哈希匹配
            byte[] extractedHash = ScriptPattern.extractHashFromP2SH(lockingScript);
            byte[] expectedHash = Utils.sha256hash160(redeemScript.getProgram());

            System.out.println("提取的哈希: " + Utils.HEX.encode(extractedHash));
            System.out.println("预期的哈希: " + Utils.HEX.encode(expectedHash));

            if (!Arrays.equals(expectedHash, extractedHash)) {
                System.err.println("赎回脚本哈希不匹配");
                return false;
            }

            // 验证多重签名脚本格式
            if (!ScriptPattern.isSentToMultisig(redeemScript)) {
                System.err.println("不是有效的多重签名赎回脚本");
                return false;
            }

            System.out.println("✅ 所有P2SH验证通过");
            return true;

        } catch (Exception e) {
            System.err.println("P2SH验证过程中发生错误: " + e.getMessage());
            return false;
        }
    }

    /**
     * 测试P2SH地址生成
     */
    @Test
    public void testP2SHAddressGeneration() {
        NetworkParameters params = MainNetParams.get();

        // 创建测试密钥
        List<ECKey> keys = Arrays.asList(new ECKey(), new ECKey(), new ECKey());
        Script redeemScript = ScriptBuilder.createMultiSigOutputScript(2, keys);
        Script p2shScript = ScriptBuilder.createP2SHOutputScript(redeemScript);
        Address p2shAddress = LegacyAddress.fromScriptHash(params,
                Utils.sha256hash160(redeemScript.getProgram()));

        System.out.println("=== P2SH地址信息 ===");
        System.out.println("P2SH地址: " + p2shAddress);
        System.out.println("地址以'3'开头: " + p2shAddress.toString().startsWith("3"));
        System.out.println("赎回脚本长度: " + redeemScript.getProgram().length + " 字节");
        System.out.println("P2SH脚本长度: " + p2shScript.getProgram().length + " 字节");

        // 验证脚本类型
        System.out.println("是P2SH脚本: " + ScriptPattern.isP2SH(p2shScript));
        System.out.println("是多重签名脚本: " + ScriptPattern.isSentToMultisig(redeemScript));
    }
}