package com.bit.coin.structure.tx;

import com.bit.coin.structure.block.HexByteArraySerializer;
import com.bit.coin.utils.SerializeUtils;
import com.bit.coin.utils.Sha;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.annotation.JsonSerialize;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Data
@JsonPropertyOrder({"txId", "version", "lockTime","inputs","outputs"})  // 按指定顺序输出
public class Transaction {
    /**
     * 交易特征
     */
    @JsonSerialize(using = HexByteArraySerializer.class)
    private byte[] txId;

    /**
     * 版本
     */
    private int version;

    /**
     * 锁定时间
     */
    private long lockTime;
    
    /**
     * 交易输入
     */
    private List<TxInput> inputs = new ArrayList<>();

    /**
     * 交易输出
     */
    private List<TxOutput> outputs = new ArrayList<>();


    public static final int TX_ID_LENGTH = 32;      // 交易ID长度

    // SIGHASH类型
    public static final int SIGHASH_ALL = 0x01;
    public static final int SIGHASH_NONE = 0x02;
    public static final int SIGHASH_SINGLE = 0x03;
    public static final int SIGHASH_ANYONECANPAY = 0x80;



    // 序列化相关常量
    public static final int VERSION_SIZE = 4;       // 版本号长度
    public static final int LOCK_TIME_SIZE = 4;     // 锁定时间长度
    public static final int TX_ID_SIZE = 32;        // 交易ID长度


    public byte[] getTxId(){
        if (txId!=null){
            txId=calculateTxId();
        }
        return txId;
    }


    public byte[] calculateTxId()  {
        byte[] serialize = this.serializeWithOutTxId();
        return Sha.applySHA256(serialize);
    }

    /**
     * 序列化交易为字节数组
     * @return 序列化后的字节数组
     * @throws IOException 如果发生I/O错误
     */
    public byte[] serializeWithOutTxId()  {
        try{
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            com.bit.coin.utils.SerializeUtils.writeLittleEndianInt(dos, version);
            com.bit.coin.utils.SerializeUtils.writeVarInt(dos, inputs.size());
            for (TxInput input : inputs) {
                dos.write(input.serialize());
            }
            com.bit.coin.utils.SerializeUtils.writeVarInt(dos, outputs.size());
            for (TxOutput output : outputs) {
                dos.write(output.serialize());
            }
            com.bit.coin.utils.SerializeUtils.writeLittleEndianInt(dos, (int) lockTime);
            dos.close();
            return baos.toByteArray();
        }catch (Exception e){
            throw new IllegalArgumentException("序列化失败:",e);
        }
    }

    /**
     * 从字节数组反序列化交易
     * @param data 字节数组
     * @return Transaction对象
     * @throws IOException 如果发生I/O错误
     */
    public static Transaction deserializeWithOutTxId(byte[] data) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        DataInputStream dis = new DataInputStream(bais);
        Transaction tx = new Transaction();

        // 读取版本号（小端字节序）
        byte[] versionBytes = new byte[4];
        dis.readFully(versionBytes);
        tx.setVersion(com.bit.coin.utils.SerializeUtils.littleEndianToInt(versionBytes));

        // 读取输入数量
        int inputCount = com.bit.coin.utils.SerializeUtils.readVarInt(dis);
        for (int i = 0; i < inputCount; i++) {
            TxInput input = TxInput.deserialize(dis);
            tx.getInputs().add(input);
        }

        // 读取输出数量
        int outputCount = com.bit.coin.utils.SerializeUtils.readVarInt(dis);
        for (int i = 0; i < outputCount; i++) {
            TxOutput output = TxOutput.deserialize(dis);
            tx.getOutputs().add(output);
        }

        // 读取锁定时间（小端字节序）
        byte[] lockTimeBytes = new byte[4];
        dis.readFully(lockTimeBytes);
        tx.setLockTime(com.bit.coin.utils.SerializeUtils.littleEndianToInt(lockTimeBytes) & 0xFFFFFFFFL);

        dis.close();
        return tx;
    }

    //包含交易ID的序列化 反序列化


    /**
     * 序列化整个交易（包含txId）
     * @return 序列化后的字节数组
     * @throws IOException 如果发生I/O错误
     */
    public byte[] serialize() throws IOException {
        // 1. 确保txId已经计算出来
        byte[] txId = this.getTxId();

        // 2. 获取不包含txId的交易数据
        byte[] txData = this.serializeWithOutTxId();

        // 3. 合并txId和交易数据
        byte[] result = new byte[TX_ID_SIZE + txData.length];
        System.arraycopy(txId, 0, result, 0, TX_ID_SIZE);
        System.arraycopy(txData, 0, result, TX_ID_SIZE, txData.length);

        return result;
    }

    /**
     * 从字节数组反序列化整个交易（包含txId）
     * @param data 字节数组
     * @return Transaction对象
     * @throws IOException 如果发生I/O错误
     */
    public static Transaction deserialize(byte[] data)  {
        try {
            // 1. 检查数据长度是否足够
            if (data.length < TX_ID_SIZE) {
                throw new IOException("数据长度不足，无法反序列化交易。");
            }

            // 2. 读取txId
            byte[] txId = Arrays.copyOfRange(data, 0, TX_ID_SIZE);

            // 3. 反序列化剩余的数据
            byte[] txData = Arrays.copyOfRange(data, TX_ID_SIZE, data.length);
            Transaction tx = deserializeWithOutTxId(txData);

            // 4. 设置txId并返回
            tx.setTxId(txId);
            return tx;
        }catch (Exception e){
            throw new IllegalArgumentException("交易序列化失败",e);
        }
    }

    /**
     * 从 DataInputStream 反序列化整个交易（包含txId）。
     * 这个方法是为了解决在流中定位交易边界的问题而创建的。
     * @param dis 数据输入流
     * @return Transaction 对象
     * @throws IOException 如果发生I/O错误或数据格式不正确
     */
    public static Transaction deserializeFromStream(DataInputStream dis) throws IOException {
        // 1. 读取交易ID (32字节)
        byte[] txId = SerializeUtils.readFixedBytes(dis, TX_ID_SIZE);
        if (txId == null) {
            return null; // 流已读完
        }

        // 2. 读取交易的其余部分（裸交易数据）
        Transaction tx = deserializeWithOutTxId(dis);

        // 3. 设置交易ID并返回
        if (tx != null) {
            tx.setTxId(txId);
        }
        return tx;
    }


    /**
     * 从 DataInputStream 反序列化交易数据（不包含txId）。
     * @param dis 数据输入流
     * @return Transaction 对象
     * @throws IOException 如果发生I/O错误
     */
    public static Transaction deserializeWithOutTxId(DataInputStream dis) throws IOException {
        Transaction tx = new Transaction();

        // 读取版本号（小端字节序）
        tx.setVersion(SerializeUtils.readLittleEndianInt(dis));

        // 读取输入数量
        int inputCount = SerializeUtils.readVarInt(dis);
        for (int i = 0; i < inputCount; i++) {
            TxInput input = TxInput.deserialize(dis);
            tx.getInputs().add(input);
        }

        // 读取输出数量
        int outputCount = SerializeUtils.readVarInt(dis);
        for (int i = 0; i < outputCount; i++) {
            TxOutput output = TxOutput.deserialize(dis);
            tx.getOutputs().add(output);
        }

        // 读取锁定时间（小端字节序）
        tx.setLockTime(SerializeUtils.readLittleEndianInt(dis) & 0xFFFFFFFFL);

        return tx;
    }




    /**
     * 估算VarInt的大小
     * @param value 要编码的值
     * @return VarInt的字节数
     */
    private int estimateVarIntSize(long value) {
        if (value < 0xFD) {
            return 1;
        } else if (value <= 0xFFFF) {
            return 3;
        } else if (value <= 0xFFFFFFFFL) {
            return 5;
        } else {
            return 9;
        }
    }




    /**
     * 判断是否为铸币交易
     */
    @JsonIgnore
    public boolean isCoinbase() {
        return inputs.size() == 1 && inputs.getFirst().isCoinbase();
    }


    public static void main(String[] args) throws IOException {
        System.out.println("===== Transaction 序列化/反序列化测试 =====\n");

        // 创建交易
        Transaction tx1 = new Transaction();
        tx1.setVersion(1);
        tx1.setLockTime(0);

        // 创建输入
        TxInput input1 = new TxInput();
        byte[] txId1 = new byte[32];
        txId1[0] = 0x01;
        txId1[31] = (byte) 0xFF;
        input1.setTxId(txId1);
        input1.setIndex(0);
        byte[] scriptSig1 = new byte[96];
        for (int i = 0; i < 96; i++) {
            scriptSig1[i] = (byte) (i % 256);
        }
        input1.setScriptSig(scriptSig1);

        TxInput input2 = new TxInput();
        byte[] txId2 = new byte[32];
        txId2[0] = 0x02;
        input2.setTxId(txId2);
        input2.setIndex(1);
        byte[] scriptSig2 = new byte[96];
        for (int i = 0; i < 96; i++) {
            scriptSig2[i] = (byte) ((i + 50) % 256);
        }
        input2.setScriptSig(scriptSig2);

        tx1.getInputs().add(input1);
        tx1.getInputs().add(input2);

        // 创建输出
        TxOutput output1 = new TxOutput();
        output1.setValue(1000000L); // 1 BTC in satoshis
        byte[] scriptPubKey1 = new byte[20];
        for (int i = 0; i < 20; i++) {
            scriptPubKey1[i] = (byte) (i * 2);
        }
        output1.setScriptPubKey(scriptPubKey1);

        TxOutput output2 = new TxOutput();
        output2.setValue(500000L); // 0.5 BTC in satoshis
        byte[] scriptPubKey2 = new byte[20];
        for (int i = 0; i < 20; i++) {
            scriptPubKey2[i] = (byte) ((i * 3) % 256);
        }
        output2.setScriptPubKey(scriptPubKey2);

        tx1.getOutputs().add(output1);
        tx1.getOutputs().add(output2);

        System.out.println("=== 原始交易信息 ===");
        System.out.println("版本: " + tx1.getVersion());
        System.out.println("锁定时间: " + tx1.getLockTime());
        System.out.println("输入数量: " + tx1.getInputs().size());
        System.out.println("输出数量: " + tx1.getOutputs().size());

        // 序列化
        System.out.println("\n=== 序列化交易 ===");
        byte[] serialized = tx1.serializeWithOutTxId();
        System.out.println("序列化后字节长度: " + serialized.length + " 字节");
        System.out.println("序列化结果（前64字节）: " + com.bit.coin.utils.SerializeUtils.bytesToHex(
            serialized.length > 64 ? java.util.Arrays.copyOf(serialized, 64) : serialized));

        // 反序列化
        System.out.println("\n=== 反序列化交易 ===");
        Transaction tx2 = Transaction.deserializeWithOutTxId(serialized);

        // 验证数据
        System.out.println("版本: " + tx2.getVersion() + " (匹配: " + (tx2.getVersion() == tx1.getVersion()) + ")");
        System.out.println("锁定时间: " + tx2.getLockTime() + " (匹配: " + (tx2.getLockTime() == tx1.getLockTime()) + ")");
        System.out.println("输入数量: " + tx2.getInputs().size() + " (匹配: " + (tx2.getInputs().size() == tx1.getInputs().size()) + ")");
        System.out.println("输出数量: " + tx2.getOutputs().size() + " (匹配: " + (tx2.getOutputs().size() == tx1.getOutputs().size()) + ")");

        // 验证输入
        System.out.println("\n=== 输入验证 ===");
        for (int i = 0; i < tx2.getInputs().size(); i++) {
            TxInput originalInput = tx1.getInputs().get(i);
            TxInput deserializedInput = tx2.getInputs().get(i);
            System.out.println("输入 " + i + ":");
            System.out.println("  交易ID匹配: " + java.util.Arrays.equals(originalInput.getTxId(), deserializedInput.getTxId()));
            System.out.println("  索引匹配: " + (originalInput.getIndex() == deserializedInput.getIndex()));
            System.out.println("  脚本匹配: " + java.util.Arrays.equals(originalInput.getScriptSig(), deserializedInput.getScriptSig()));
        }

        // 验证输出
        System.out.println("\n=== 输出验证 ===");
        for (int i = 0; i < tx2.getOutputs().size(); i++) {
            TxOutput originalOutput = tx1.getOutputs().get(i);
            TxOutput deserializedOutput = tx2.getOutputs().get(i);
            System.out.println("输出 " + i + ":");
            System.out.println("  金额匹配: " + (originalOutput.getValue() == deserializedOutput.getValue()));
            System.out.println("  脚本匹配: " + java.util.Arrays.equals(originalOutput.getScriptPubKey(), deserializedOutput.getScriptPubKey()));
        }

        // 再次序列化反序列化以验证一致性
        System.out.println("\n=== 一致性验证 ===");
        byte[] serialized2 = tx2.serializeWithOutTxId();
        System.out.println("第二次序列化长度: " + serialized2.length + " (匹配: " + (serialized2.length == serialized.length) + ")");
        System.out.println("第二次序列化内容匹配: " + java.util.Arrays.equals(serialized, serialized2));

        Transaction tx3 = deserializeWithOutTxId(serialized2);
        byte[] serialize3 = tx3.serializeWithOutTxId();
        System.out.println("第三次序列化长度: " + serialize3.length + " (匹配: " + (serialize3.length == serialized.length) + ")");
        System.out.println("第三次序列化内容匹配: " + java.util.Arrays.equals(serialized, serialize3));

        System.out.println("\n===== 测试完成 =====");
    }


    /**
     * 区块索引 [区块Hash 32字节][4字节位置索引]
     *
     * @param hash
     * @param index
     * @return
     */
    @JsonIgnore
    public byte[] getBlockIndex(byte[] hash, int index) {
        if (hash == null || hash.length != 32) {
            throw new IllegalArgumentException("区块哈希必须为32字节");
        }
        if (index < 0) {
            throw new IllegalArgumentException("交易索引不能为负数");
        }
        // 创建36字节的数组：32字节哈希 + 4字节索引
        byte[] blockIndex = new byte[36];
        // 拷贝区块哈希（前32字节）
        System.arraycopy(hash, 0, blockIndex, 0, 32);
        // 将索引转换为小端字节序并放在后4字节
        blockIndex[32] = (byte) (index & 0xFF);
        blockIndex[33] = (byte) ((index >> 8) & 0xFF);
        blockIndex[34] = (byte) ((index >> 16) & 0xFF);
        blockIndex[35] = (byte) ((index >> 24) & 0xFF);
        return blockIndex;
    }


    /**
     * 从区块索引字节数组中解析区块哈希和交易索引
     * @param blockIndex 区块索引字节数组（36字节）
     * @return 包含区块哈希和交易索引的Map
     */
    @JsonIgnore
    public static java.util.Map<String, Object> parseBlockIndex(byte[] blockIndex) {
        if (blockIndex == null || blockIndex.length != 36) {
            throw new IllegalArgumentException("区块索引必须为36字节");
        }

        // 提取区块哈希（前32字节）
        byte[] hash = new byte[32];
        System.arraycopy(blockIndex, 0, hash, 0, 32);

        // 提取交易索引（后4字节，小端字节序）
        int index = (blockIndex[32] & 0xFF) |
                ((blockIndex[33] & 0xFF) << 8) |
                ((blockIndex[34] & 0xFF) << 16) |
                ((blockIndex[35] & 0xFF) << 24);

        // 返回包含两者的Map
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("hash", hash);
        result.put("index", index);

        return result;
    }

}
