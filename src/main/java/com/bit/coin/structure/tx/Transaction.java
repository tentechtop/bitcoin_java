package com.bit.coin.structure.tx;

import com.bit.coin.utils.HexByteArraySerializer;
import com.bit.coin.structure.script.Script;
import com.bit.coin.structure.script.ScriptExecutor;
import com.bit.coin.utils.Ed25519Signer;
import com.bit.coin.utils.SerializeUtils;
import com.bit.coin.utils.Sha;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.annotation.JsonSerialize;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.bit.coin.config.SystemConfig.TX_TIME_FORMATTER;
import static com.bit.coin.structure.script.ScriptExecutor.combineScripts;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;

@Slf4j
@Data
@JsonPropertyOrder({"txId", "version", "lockTime","inputs","outputs","time","timeStr","height"})  // 按指定顺序输出
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


    //当交易版本大于100时 TxInput和TxOutput 是用的脚本
    //交易版本是0和1时 是固定为P2PKH
    //当交易版本大于10000时 支持执行虚拟机



    //..............................扩展字段不参与序列化.........................
    /**
     * 参与交易待花费的UTXO
     */
    @JsonIgnore
    private Map<String,UTXO> utxoMap;

    /**
     * 手续费
     */
    @JsonIgnore
    private long fee;

    @JsonIgnore
    private long size;

    /**
     * 区块时间
     */
    private Long time;

    private String timeStr;
    public String getTimeStr() {
        // 校验：时间戳非空 且 为有效正整数
        if (time != null && time > 0) {
            // 秒级时间戳 → 转换为Instant → 格式化为字符串
            return TX_TIME_FORMATTER.format(Instant.ofEpochSecond(time));
        }
        // 无时间时返回空字符串
        return "";
    }


    /**
     * 区块高度
     */
    private Integer height;

    /**
     * 区块Hash
     */
    @JsonSerialize(using = HexByteArraySerializer.class)
    private byte[] hash;


    /**
     * 加入交易池时间
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long joinTime;

    /**
     * 最近一次的广播时间
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long broadcastTime;




    /**
     * 状态
     */
    private long status;

    private String statusStr;

    public String getStatusStr(){
        return TransactionStatusResolver.toString(status);
    }




    //验证交易合法性
    //TODO 将固定的改成脚本验证即可
    public boolean validate() {
        return TransactionValidator.validate(this);
    }


    public static final int TX_ID_LENGTH = 32;      // 交易ID长度

    // SIGHASH类型
    public static final byte SIGHASH_ALL = 0x01;//锁定输入和输出 不允许篡改
    public static final byte SIGHASH_NONE = 0x02;//锁定所有输入 不锁定输出 空支票
    public static final byte SIGHASH_SINGLE = 0x03; //所有输入 定向支付 只签名对应的输出 第 1 个输入的签名只锁定第 1 个输出。如果输入比输出多，多余的输入对应的输出视为 “空”。这用于需要将多个输入的资金分别发送到不同地址的场景。
    public static final byte SIGHASH_ANYONECANPAY = (byte) 0x80; // 只保留当前输入 输入锁定  控制其他输入是否可修改
    //组合体
    public static final byte SIGHASH_ALL_ANYONECANPAY = SIGHASH_ALL | SIGHASH_ANYONECANPAY;    // 0x01 | 0x10
    public static final byte SIGHASH_NONE_ANYONECANPAY = SIGHASH_NONE | SIGHASH_ANYONECANPAY;   // 0x02 | 0x10
    public static final byte SIGHASH_SINGLE_ANYONECANPAY = SIGHASH_SINGLE | SIGHASH_ANYONECANPAY;; // 0x03 | 0x10

    //SIGHASH_ALL (0x01) - 标准支付
    // 含义：
    // 输入：锁定所有输入（不能添加/删除/修改任何输入）
    // 输出：锁定所有输出（不能添加/删除/修改任何输出）
    // 应用场景：
    // - 普通转账
    // - 大多数钱包默认使用
    // - 最安全，防止任何篡改

    //SIGHASH_NONE (0x02) - 空支票
    // 允许修改输出的"空支票"
    // 含义：
    // 输入：锁定所有输入
    // 输出：不锁定任何输出（可以任意修改、添加、删除输出）
    // 应用场景：
    // - 委托他人决定资金去向
    // - 复杂多方协商的第一阶段
    // - 高级合约场景

    //SIGHASH_SINGLE (0x03) - 定向支付
    // 每个输入对应一个输出
    // 第i个输入只锁定第i个输出
    // 含义：
    // 输入：锁定所有输入
    // 输出：只锁定与签名输入相同索引的输出
    // 特殊情况：如果输入索引 >= 输出数量，则不锁定任何输出
    // 应用场景：
    // - 合并多个输入，分别支付给不同人
    // - CoinJoin隐私交易
    // - 复杂的资金分配


    //CoinJoin隐私交易 多个用户合并输入，但只关心自己的部分
    // Alice和Bob各自只签名自己的输入
    // 但锁定所有输出（确保资金去向正确）
    // 任何人都可以添加更多输入（ANYONECANPAY）
    //众筹/捐款
    // 捐款人：
    // 1. 不关心其他输入（ANYONECANPAY：允许添加更多捐款人）
    // 2. 但关心输出必须转到项目地址（ALL：锁定所有输出）

    //SIGHASH_ALL|ANYONECANPAY (0x81) - 联合支付
    // 任何人都可以添加输入，但输出固定
    //signature = sign(tx, SIGHASH_ALL | SIGHASH_ANYONECANPAY);
    // 含义：
    //输入：只锁定当前输入（其他人可以添加/修改其他输入）
    //输出：锁定所有输出
    //应用场景：
    //众筹捐款
    //多人联合支付
    //支付通道的协作开通


    //SIGHASH_NONE|ANYONECANPAY (0x82) - 灵活联合 最灵活的模式
    //含义：
    //输入：只锁定当前输入
    //输出：不锁定任何输出





    // 序列化相关常量
    public static final int VERSION_SIZE = 4;       // 版本号长度
    public static final int LOCK_TIME_SIZE = 4;     // 锁定时间长度
    public static final int TX_ID_SIZE = 32;        // 交易ID长度


    public byte[] getTxId(){
        if (txId==null){
            txId=calculateTxId();
        }
        return txId;
    }


    public byte[] calculateTxId()  {
        byte[] serialize = this.serializeWithOutTxId();
        return Sha.applySHA256(serialize);
    }


    //使用私钥给一笔交易签名(交易所有输入数据的解锁脚本都为空)
    public void sign(byte[] privateKey, byte[] publicKey) {
        //创建临时交易用于签名
        for (int i = 0; i < inputs.size(); i++){
            TxInput input = inputs.get(i);
            byte[] txId1 = input.getTxId();
            int index = input.getIndex();
            UTXO utxo = utxoMap.get(bytesToHex(UTXO.getKey(txId1, index)));
            byte[] scriptPubKey = utxo.getScript();
            byte[] signature = Ed25519Signer.fastSign(privateKey, Sha.applySHA256(prepareDataForSigning(i, scriptPubKey, SIGHASH_ALL)));
            //将签名和公钥组合为解锁脚本
            input.setSignatureAndPublicKey(signature,SIGHASH_ALL, publicKey);
        }
    }


    /**
     * 准备签名数据（支持所有 SIGHASH 类型）。
     * 此方法会根据给定的 sighashType 创建一个交易副本，并对其进行修改，
     * 以生成最终用于哈希计算的字节数组。
     *
     * @param inputIndex   当前正在签名的输入的索引。
     * @param scriptPubKey 与当前输入关联的前一个输出的锁定脚本。
     * @param sighashType  签名哈希类型 (e.g., SIGHASH_ALL, SIGHASH_SINGLE | SIGHASH_ANYONECANPAY)。
     * @return 用于 SHA256(SHA256(data)) 哈希计算的字节数组。
     */
    public byte[] prepareDataForSigning(int inputIndex, byte[] scriptPubKey, byte sighashType) {
        if (!TransactionValidator.validSignatureType(sighashType) || inputIndex < 0 || inputIndex >= inputs.size()) {
            throw new IllegalArgumentException("Invalid signature type or input index");
        }
        int base = Byte.toUnsignedInt(sighashType) & 0x1f;
        boolean anyoneCanPay = (sighashType & SIGHASH_ANYONECANPAY) != 0;
        if (base == SIGHASH_SINGLE && inputIndex >= outputs.size()) {
            throw new IllegalArgumentException("SIGHASH_SINGLE requires a corresponding output");
        }
        Transaction copy = new Transaction();
        copy.setVersion(version);
        copy.setLockTime(lockTime);
        for (int i = 0; i < inputs.size(); i++) {
            if (anyoneCanPay && i != inputIndex) continue;
            TxInput original = inputs.get(i);
            TxInput input = new TxInput();
            input.setTxId(original.getTxId());
            input.setIndex(original.getIndex());
            input.setSequence(i != inputIndex && base != SIGHASH_ALL ? 0 : original.getSequence());
            input.setScriptSig(i == inputIndex ? scriptPubKey : new byte[0]);
            copy.getInputs().add(input);
        }
        if (base == SIGHASH_ALL) {
            copy.setOutputs(new ArrayList<>(outputs));
        } else if (base == SIGHASH_SINGLE) {
            for (int i = 0; i < inputIndex; i++) {
                TxOutput placeholder = new TxOutput();
                placeholder.setValue(-1L);
                placeholder.setScriptPubKey(new byte[0]);
                copy.getOutputs().add(placeholder);
            }
            copy.getOutputs().add(outputs.get(inputIndex));
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.write(copy.serializeWithOutTxId());
            SerializeUtils.writeLittleEndianInt(out, Byte.toUnsignedInt(sighashType));
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot serialize signing data", e);
        }
    }

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
        if (data == null || data.length > TransactionValidator.MAX_TRANSACTION_BYTES) {
            throw new IOException("Transaction exceeds byte limit");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            Transaction tx = deserializeWithOutTxId(in);
            if (in.available() != 0) throw new IOException("Trailing transaction data");
            return tx;
        }
    }

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
            if (data == null || data.length < TX_ID_SIZE || data.length > TransactionValidator.MAX_TRANSACTION_BYTES) {
                throw new IllegalArgumentException("Invalid transaction size");
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
        if (inputCount < 1 || inputCount > TransactionValidator.MAX_INPUTS) throw new IOException("Invalid input count");
        for (int i = 0; i < inputCount; i++) {
            TxInput input = TxInput.deserialize(dis);
            tx.getInputs().add(input);
        }

        // 读取输出数量
        int outputCount = SerializeUtils.readVarInt(dis);
        if (outputCount < 1 || outputCount > TransactionValidator.MAX_OUTPUTS) throw new IOException("Invalid output count");
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
        return inputs != null && inputs.size() == 1 && inputs.getFirst() != null && inputs.getFirst().isCoinbase();
    }


    public static void main(String[] args) throws IOException {
        System.out.println("打印{}"+SIGHASH_ALL_ANYONECANPAY);
        System.out.println("打印{}"+SIGHASH_NONE_ANYONECANPAY);
        System.out.println("打印{}"+SIGHASH_SINGLE_ANYONECANPAY);

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
        System.out.println("序列化结果（前64字节）: " + bytesToHex(
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

    @JsonIgnore
    public List<String> getSpendingUTXOKeyList() {
        boolean coinbase = isCoinbase();
        if (coinbase){
            return null;
        }
        ArrayList<String> utxos = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            TxInput input = inputs.get(i);
            int index = input.getIndex();
            byte[] txId1 = input.getTxId();
            utxos.add(UTXO.getKeyStr(txId1, index));
        }
        return utxos;
    }


    @JsonIgnore
    public List<String> getPendingConfirmationOfReceipt() {
        boolean coinbase = isCoinbase();
        if (coinbase){
            return null;
        }
        ArrayList<String> utxos = new ArrayList<>();
        for (int i = 0; i < outputs.size(); i++) {
            String keyStr = UTXO.getKeyStr(getTxId(), i);
            utxos.add(keyStr);
        }
        return utxos;
    }

    //每一笔交易最小手续费是1000 + size*(10 * 交易池拥塞程度)

    /**
     * 获取交易大小（字节数）
     * 计算交易序列化（不含txId）后的实际字节长度，用于手续费计算
     * @return 交易的字节大小
     */
    public long getSize() {
        // 初始化大小为0，使用long避免整数溢出
        long totalSize = 0;

        try {
            // 1. 版本号：固定4字节（小端序）
            totalSize += VERSION_SIZE;

            // 2. 输入数量的VarInt编码字节数
            totalSize += estimateVarIntSize(inputs.size());

            // 3. 所有交易输入的字节大小（逐个计算每个输入的序列化长度）
            for (TxInput input : inputs) {
                totalSize += input.serialize().length;
            }

            // 4. 输出数量的VarInt编码字节数
            totalSize += estimateVarIntSize(outputs.size());

            // 5. 所有交易输出的字节大小（逐个计算每个输出的序列化长度）
            for (TxOutput output : outputs) {
                totalSize += output.serialize().length;
            }

            // 6. 锁定时间：固定4字节（小端序）
            totalSize += LOCK_TIME_SIZE;

            // 将计算结果赋值给实例变量size，保持数据一致性
            this.size =  totalSize;

            return totalSize;
        } catch (Exception e) {
            log.error("计算交易大小失败", e);
            // 异常时返回0，也可根据业务需求调整为抛出异常
            return 0;
        }
    }



    public byte[] calculateSigHash(int inputIndex,byte signatureType) {
        // 1. 参数合法性校验
        if (inputIndex < 0 || inputIndex >= inputs.size()) {
            throw new IllegalArgumentException("输入索引非法：inputIndex=" + inputIndex + "，输入总数=" + inputs.size());
        }
        if (utxoMap == null || utxoMap.isEmpty()) {
            throw new IllegalArgumentException("UTXO映射表为空，无法获取锁定脚本");
        }

        // 2. 获取当前输入对应的UTXO锁定脚本（scriptPubKey）
        TxInput input = inputs.get(inputIndex);
        String utxoKey = bytesToHex(UTXO.getKey(input.getTxId(), input.getIndex()));
        UTXO referencedUtxo = utxoMap.get(utxoKey);

        // 校验UTXO金额一致性（防止金额篡改）
        if (referencedUtxo == null) {
            throw new IllegalArgumentException("找不到输入对应的UTXO：utxoKey=" + utxoKey);
        }
        byte[] scriptPubKey = referencedUtxo.getScript();

        // 3. 准备待签名的原始数据（复用已实现的 prepareDataForSigning 方法）
        byte[] dataToSign = prepareDataForSigning(inputIndex, scriptPubKey, signatureType);

        // 4. 计算双重 SHA256 哈希（比特币标准：SHA256(SHA256(data))）
        byte[] firstHash = Sha.applySHA256(dataToSign);


        log.debug("SigHash计算完成 | 输入索引={} | SighashType=0x{} | SigHash={}",
                inputIndex,
                Integer.toHexString(SIGHASH_ALL & 0xFF),
                bytesToHex(firstHash));

        return firstHash;
    }



}
