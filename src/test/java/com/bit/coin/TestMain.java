package com.bit.coin;


import org.bitcoinj.core.*;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptChunk;
import org.bitcoinj.script.ScriptException;
import org.bouncycastle.util.encoders.Hex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import static com.bit.coin.utils.SerializeUtils.hexToBytes;

public class TestMain {

    //main
    public static void main(String[] args) throws IOException {

/*
        NetworkParameters params = TestNet3Params.get(); // 使用测试网络

        System.out.println("=== 区块创建与哈希计算测试 ===\n");


        // 选择网络参数：主网（MainNetParams）/ 测试网（TestNet3Params）
        NetworkParameters NET_PARAMS = MainNetParams.get(); // 测试网更适合开发调试


        // 1. 创建空交易（基于指定网络参数）
        Transaction tx = new Transaction(NET_PARAMS);
        byte[] bytes = tx.bitcoinSerialize();

        // 2. 添加交易输入（前序交易哈希 + 输出索引）
        // 模拟前序交易哈希（32字节）
        Sha256Hash prevTxHash = Sha256Hash.wrap(Hex.decode("0000000000000000000000000000000000000000000000000000000000000000"));
        // 前序交易的第0个输出
        TransactionInput txInput = new TransactionInput(NET_PARAMS, tx, new byte[0]);
        tx.addInput(txInput);



        // 3. 添加交易输出（转账金额 + 锁定脚本）
        // 转账金额：10000聪（0.0001比特币）
        Coin amount = Coin.valueOf(10000);
        // 锁定脚本（对应接收地址的脚本，这里模拟一个P2PKH脚本）
        Address receiveAddress = Address.fromString(NET_PARAMS, "1MWwQSDJX7ZWUjXTRThYFWunnyiN3xmybF"); // 测试网地址
        Script scriptPubKey = ScriptBuilder.createOutputScript(receiveAddress);
        TransactionOutput txOutput = new TransactionOutput(NET_PARAMS, tx, amount, scriptPubKey.getProgram());
        tx.addOutput(txOutput);





        // 4. 设置交易版本号和锁定时间
        tx.setVersion(1); // 版本号1（比特币默认）
        tx.setLockTime(0); // 0表示立即生效

        // 5. 交易序列化（符合比特币原生协议，变长字节数组）
        byte[] serializedTx = tx.bitcoinSerialize();
        System.out.println("交易序列化后字节长度：" + serializedTx.length);
        System.out.println("交易序列化十六进制：" + Hex.encode(serializedTx));

        // 6. 反序列化字节数组为Transaction对象
        try {
            Transaction deserializedTx = new Transaction(NET_PARAMS, serializedTx);
            System.out.println("\n反序列化成功，交易TxID：" + deserializedTx.getTxId());
            System.out.println("反序列化后转账金额：" + deserializedTx.getOutput(0).getValue().toFriendlyString());
        } catch (ProtocolException e) {
            throw new RuntimeException("交易反序列化失败", e);
        }

        // 7. 其他常用操作：获取交易TxID、计算交易大小等
        Sha256Hash txId = tx.getTxId();
        int txSize = tx.getMessageSize();
        System.out.println("\n交易TxID：" + txId);
        System.out.println("交易大小（字节）：" + txSize);

*/


        try {
            // ========== 1. 构建符合最小推送规则的ScriptChunk ==========
            List<ScriptChunk> chunks = new LinkedList<>();
            // 推送1字节数据01：opcode=1（数据长度），符合最小推送
            byte[] data1 = hexToBytes("01");
            chunks.add(new ScriptChunk(data1.length, data1));
            // 推送1字节数据02：opcode=1
            byte[] data2 = hexToBytes("02");
            chunks.add(new ScriptChunk(data2.length, data2));
            // 推送1字节数据03：opcode=1
            byte[] data3 = hexToBytes("03");
            chunks.add(new ScriptChunk(data3.length, data3));
            // 执行OP_ROT：opcode=123
            chunks.add(new ScriptChunk(123, null));

            // ========== 2. 序列化为字节数组 ==========
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            for (ScriptChunk chunk : chunks) {
                chunk.write(bos);
            }
            byte[] programBytes = bos.toByteArray();

            // ========== 3. 创建Script对象 ==========
            Script rotScript = new Script(programBytes);
            System.out.println("测试脚本：" + rotScript);

            // ========== 4. 执行脚本（保留MINIMALDATA校验） ==========
            LinkedList<byte[]> stack = new LinkedList<>();
            Set<Script.VerifyFlag> flags = EnumSet.allOf(Script.VerifyFlag.class); // 包含MINIMALDATA
            Script.executeScript(null, 0, rotScript, stack, flags);

            // ========== 5. 输出结果 ==========
            System.out.println("\nOP_ROT执行后栈内容（栈顶→栈底）：");
            int index = 0;
            for (byte[] data : stack) {
                System.out.printf("栈元素[%d]: %s%n", index++, Utils.HEX.encode(data));
            }

        } catch (ScriptException | IOException e) {
            e.printStackTrace();
        }


    }
}
