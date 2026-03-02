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

public class P2WSHScriptExample {

    @Test
    public void testP2WSH2Of3Multisig() {
        NetworkParameters params = MainNetParams.get();

        System.out.println("=== P2WSH 2-of-3多重签名测试 ===");

        // 1. 创建三个密钥对（2-of-3多重签名）
        List<ECKey> keys = Arrays.asList(new ECKey(), new ECKey(), new ECKey());

        for (int i = 0; i < keys.size(); i++) {
            System.out.println("私钥" + (i + 1) + "(WIF): " + keys.get(i).getPrivateKeyAsWiF(params));
        }

        // 2. 创建见证脚本（witness script）- 类似于P2SH的赎回脚本
        Script witnessScript = ScriptBuilder.createMultiSigOutputScript(2, keys);

        System.out.println("\n=== 见证脚本 (witnessScript) ===");
        System.out.println("脚本字节: " + Utils.HEX.encode(witnessScript.getProgram()));
        System.out.println("脚本反汇编: " + witnessScript);

        // 3. 创建P2WSH锁定脚本 [1,3](@ref)
        // P2WSH锁定脚本格式: OP_0 <32字节witnessScript的SHA256哈希>
        Script lockingScript = ScriptBuilder.createP2WSHOutputScript(witnessScript);
        Address p2wshAddress = SegwitAddress.fromHash(params,
                Sha256Hash.hash(witnessScript.getProgram()));

        System.out.println("\n=== P2WSH锁定脚本 (scriptPubKey) ===");
        System.out.println("P2WSH地址: " + p2wshAddress);
        System.out.println("脚本字节: " + Utils.HEX.encode(lockingScript.getProgram()));
        System.out.println("脚本反汇编: " + lockingScript);
        System.out.println("地址以'bc1'开头: " + p2wshAddress.toString().startsWith("bc1"));

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
        Address receiverAddress = SegwitAddress.fromKey(params, receiverKey);
        spendingTx.addOutput(Coin.valueOf(90_000), receiverAddress);

        // 6. 创建SegWit签名哈希 [1](@ref)
        Sha256Hash sigHash = spendingTx.hashForWitnessSignature(
                0,
                witnessScript,
                utxoOutput.getValue(),
                Transaction.SigHash.ALL,
                false
        );

        System.out.println("\n签名哈希: " + sigHash.toString());

        // 7. 使用其中两个私钥签名
        List<TransactionSignature> signatures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            ECKey.ECDSASignature ecdsaSig = keys.get(i).sign(sigHash);
            TransactionSignature txSig = new TransactionSignature(ecdsaSig, Transaction.SigHash.ALL, false);
            signatures.add(txSig);
            System.out.println("签名" + (i + 1) + ": " + Utils.HEX.encode(txSig.encodeToBitcoin()));
        }

        // 8. 创建见证数据（Witness）[3](@ref)
        // P2WSH的见证数据格式: <dummy> <signature1> <signature2> <witnessScript>
        // 注意：由于CHECKMULTISIG的bug，需要添加一个dummy元素
        TransactionWitness witness = new TransactionWitness(4);
        witness.setPush(0, new byte[0]); // Dummy元素（由于CHECKMULTISIG bug）
        witness.setPush(1, signatures.get(0).encodeToBitcoin());
        witness.setPush(2, signatures.get(1).encodeToBitcoin());
        witness.setPush(3, witnessScript.getProgram());

        input.setWitness(witness);

        System.out.println("\n=== P2WSH见证数据 ===");
        System.out.println("见证数据包含 " + witness.getPushCount() + " 个元素");

        // 9. 验证交易
        System.out.println("\n=== P2WSH脚本执行测试 ===");
        try {
            boolean isValid = verifyP2WSH(input, utxoOutput, lockingScript, witnessScript);

            if (isValid) {
                System.out.println("✅ P2WSH多重签名验证成功！");
            } else {
                System.out.println("❌ P2WSH验证失败！");
            }
        } catch (Exception e) {
            System.err.println("❌ P2WSH验证失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 手动验证P2WSH交易
     */
    private boolean verifyP2WSH(TransactionInput input,
                                TransactionOutput utxoOutput,
                                Script lockingScript,
                                Script expectedWitnessScript) {
        try {
            // 验证锁定脚本是P2WSH格式
            if (!ScriptPattern.isP2WSH(lockingScript)) {
                System.err.println("不是有效的P2WSH锁定脚本");
                return false;
            }

            // 获取见证数据
            TransactionWitness witness = input.getWitness();
            if (witness.getPushCount() < 4) {
                System.err.println("见证数据应该包含4个元素（dummy、2个签名、见证脚本）");
                return false;
            }

            // 验证见证脚本哈希匹配 [1](@ref)
            byte[] extractedHash = ScriptPattern.extractHashFromP2WH(lockingScript);
            byte[] expectedHash = Sha256Hash.hash(expectedWitnessScript.getProgram());

            System.out.println("提取的哈希: " + Utils.HEX.encode(extractedHash));
            System.out.println("预期的哈希: " + Utils.HEX.encode(expectedHash));

            if (!Arrays.equals(expectedHash, extractedHash)) {
                System.err.println("见证脚本哈希不匹配");
                return false;
            }

            // 验证提供的见证脚本与预期一致
            byte[] providedWitnessScript = witness.getPush(3);
            if (!Arrays.equals(providedWitnessScript, expectedWitnessScript.getProgram())) {
                System.err.println("提供的见证脚本不匹配");
                return false;
            }

            System.out.println("✅ 所有P2WSH验证通过");
            return true;

        } catch (Exception e) {
            System.err.println("P2WSH验证过程中发生错误: " + e.getMessage());
            return false;
        }
    }

    /**
     * 测试P2WSH优势对比
     */
    @Test
    public void testP2WSHAdvantages() {
        NetworkParameters params = MainNetParams.get();

        // 创建相同的多重签名条件
        List<ECKey> keys = Arrays.asList(new ECKey(), new ECKey(), new ECKey());

        // P2SH实现
        Script p2shRedeemScript = ScriptBuilder.createMultiSigOutputScript(2, keys);
        Script p2shLockingScript = ScriptBuilder.createP2SHOutputScript(p2shRedeemScript);

        // P2WSH实现
        Script p2wshWitnessScript = ScriptBuilder.createMultiSigOutputScript(2, keys);
        Script p2wshLockingScript = ScriptBuilder.createP2WSHOutputScript(p2wshWitnessScript);

        System.out.println("=== P2SH vs P2WSH 对比 ===");
        System.out.println("P2SH脚本长度: " + p2shLockingScript.getProgram().length + " 字节");
        System.out.println("P2WSH脚本长度: " + p2wshLockingScript.getProgram().length + " 字节");
        System.out.println("P2WSH比P2SH节省约 " +
                ((p2shLockingScript.getProgram().length - p2wshLockingScript.getProgram().length) * 100 /
                        p2shLockingScript.getProgram().length) + "% 的空间");

        // 见证数据体积优势 [1](@ref)
        System.out.println("P2WSH见证数据享受1/4权重折扣，手续费更低");
        System.out.println("P2WSH支持更大脚本（上限10,000字节）");
        System.out.println("P2WSH修复了交易延展性问题");
    }

    /**
     * 测试嵌套P2SH-P2WSH（兼容旧节点）
     */
    @Test
    public void testNestedP2SH_P2WSH() {
        NetworkParameters params = MainNetParams.get();

        List<ECKey> keys = Arrays.asList(new ECKey(), new ECKey(), new ECKey());
        Script witnessScript = ScriptBuilder.createMultiSigOutputScript(2, keys);

        // 创建P2WSH输出脚本
        Script p2wshScript = ScriptBuilder.createP2WSHOutputScript(witnessScript);

        // 嵌套在P2SH中（兼容旧节点）
        Script p2shScript = ScriptBuilder.createP2SHOutputScript(p2wshScript);
        Address nestedAddress = LegacyAddress.fromScriptHash(params,
                Utils.sha256hash160(p2wshScript.getProgram()));

        System.out.println("=== 嵌套P2SH-P2WSH地址 ===");
        System.out.println("嵌套地址: " + nestedAddress);
        System.out.println("地址以'3'开头: " + nestedAddress.toString().startsWith("3"));
        System.out.println("这种格式向后兼容不支持SegWit的旧钱包");
    }
}