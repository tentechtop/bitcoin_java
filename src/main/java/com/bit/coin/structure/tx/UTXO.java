package com.bit.coin.structure.tx;

import com.bit.coin.structure.block.HexByteArraySerializer;
import com.bit.coin.structure.script.Script;
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
import java.lang.ref.WeakReference;

import static com.bit.coin.structure.tx.UTXOStatusResolver.PENDING_SPENT;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;

@Slf4j
@Data
@JsonPropertyOrder({"txId", "index", "value","script","coinbase","height"})  // 按指定顺序输出
public class UTXO {
    //..............................核心字段..................................

    /**
     * [32字节] 交易ID
     */
    @JsonSerialize(using = HexByteArraySerializer.class)
    private byte[] txId;

    /**
     * [4字节] 输出索引
     */
    private int index;

    /**
     * [8字节] 金额
     */
    private long value;

    /**
     * [1字节] 是否为Coinbase交易输出
     */
    private boolean coinbase;

    /**
     * [4字节] 所在区块高度
     */
    private int height;

    /**
     * [20字节公钥哈希] 锁定脚本
     */
    @JsonSerialize(using = HexByteArraySerializer.class)
    private byte[] script;

    @JsonIgnore
    private WeakReference<Script> scriptPubKeyWk;


    @JsonIgnore
    public WeakReference<Script> getScriptPubKeyWk() {
        if (scriptPubKeyWk == null || scriptPubKeyWk.get() == null) {
            try {
                log.info("打印公钥{} 长度{}",bytesToHex(script),script.length);
                Script deserialized = Script.deserialize(script);
                scriptPubKeyWk = new WeakReference<>(deserialized);
            } catch (IOException e) {
                log.error("解析解锁脚本失败：{}", e.getMessage());
            }
        }
        return scriptPubKeyWk;
    }


    public String getScriptPubKeyString() {
        WeakReference<Script> scriptPubKeyWk1 = getScriptPubKeyWk();
        Script script = scriptPubKeyWk1.get();
        if (script==null){
            return "";
        }
        return script.toString();
    }

    //..............................非核心字段 不参与序列化反序列化..................................
    /**
     * UTXO状态
     */
    private long status;

    private String statusStr;

    public String getStatusStr(){
        return UTXOStatusResolver.toString(status);
    }


    // 常量定义
    public static final int TX_ID_LENGTH = 32;         // 交易ID长度
    public static final int SCRIPT_LENGTH = 20;        // 锁定脚本长度
    public static final int SERIALIZED_LENGTH = 69;    // 核心字段序列化总长度: 32+4+8+1+4+20=69字节
    public static final int WITH_STATUS_LENGTH = 73;   // 包含状态字段长度: 69+4=73字节


    /**
     * 验证核心字段长度
     */
    public void validate() {
        if (txId == null || txId.length != TX_ID_LENGTH) {
            throw new IllegalStateException("交易ID必须为" + TX_ID_LENGTH + "字节");
        }
        if (script == null || script.length != SCRIPT_LENGTH) {
            throw new IllegalStateException("锁定脚本必须为" + SCRIPT_LENGTH + "字节");
        }
        if (index < 0 || index > 0xFFFFFFFFL) {
            throw new IllegalStateException("输出索引超出int范围: " + index);
        }
    }

    /**
     * 序列化UTXO核心字段为字节数组
     * @return 序列化后的字节数组
     * @throws IOException 如果发生I/O错误
     */
    public byte[] serialize() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            com.bit.coin.utils.SerializeUtils.writeFixedBytes(dos, txId, TX_ID_LENGTH);
            dos.write(com.bit.coin.utils.SerializeUtils.intToLittleEndian(index));
            dos.write(com.bit.coin.utils.SerializeUtils.longToLittleEndian(value));
            com.bit.coin.utils.SerializeUtils.writeBoolean(dos, coinbase);
            com.bit.coin.utils.SerializeUtils.writeLittleEndianInt(dos, height);
            com.bit.coin.utils.SerializeUtils.writeVarInt(dos, script.length);
            if (script.length > 0) {
                dos.write(script);
            }
            dos.close();
            return baos.toByteArray();
        }catch (Exception e){
            throw new IllegalArgumentException("序列化失败",e);
        }
    }

    /**
     * 从DataInputStream反序列化UTXO核心字段
     * @param dis 数据输入流
     * @return UTXO对象
     * @throws IOException 如果发生I/O错误
     */
    public static UTXO deserialize(DataInputStream dis) throws IOException {
        UTXO utxo = new UTXO();
        byte[] txId = com.bit.coin.utils.SerializeUtils.readFixedBytes(dis, TX_ID_LENGTH);
        utxo.setTxId(txId);
        byte[] indexBytes = new byte[4];
        dis.readFully(indexBytes);
        utxo.setIndex(com.bit.coin.utils.SerializeUtils.readLittleEndianBytesToInt(indexBytes));
        byte[] valueBytes = new byte[8];
        dis.readFully(valueBytes);
        utxo.setValue(com.bit.coin.utils.SerializeUtils.littleEndianToLong(valueBytes));
        utxo.setCoinbase(com.bit.coin.utils.SerializeUtils.readBoolean(dis));

        // 读取高度（小端字节序）
        byte[] heightBytes = new byte[4];
        dis.readFully(heightBytes);
        utxo.setHeight(com.bit.coin.utils.SerializeUtils.littleEndianToInt(heightBytes));
        long scriptPubKeyLength = com.bit.coin.utils.SerializeUtils.readVarInt(dis);
        if (scriptPubKeyLength > 0) {
            byte[] scriptSig = new byte[(int) scriptPubKeyLength];
            dis.readFully(scriptSig);
            utxo.setScript(scriptSig);
        } else {
            utxo.setScript(new byte[0]);
        }
        return utxo;
    }

    /**
     * 从字节数组反序列化UTXO核心字段
     * @param data 字节数组
     * @return UTXO对象
     * @throws IOException 如果发生I/O错误
     */
    public static UTXO deserialize(byte[] data)  {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            DataInputStream dis = new DataInputStream(bais);
            UTXO utxo = deserialize(dis);
            dis.close();
            return utxo;
        }catch (Exception e){
            return null;
        }
    }

    /**
     * 序列化UTXO所有字段（包含状态字段）为字节数组
     * @return 序列化后的字节数组
     * @throws IOException 如果发生I/O错误
     */
    public byte[] serializeWithStatus() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.write(serialize());
        com.bit.coin.utils.SerializeUtils.writeLittleEndianLong(dos, status);
        dos.close();
        return baos.toByteArray();
    }

    /**
     * 从DataInputStream反序列化UTXO所有字段（包含状态字段）
     * @param dis 数据输入流
     * @return UTXO对象
     * @throws IOException 如果发生I/O错误
     */
    public static UTXO deserializeWithStatus(DataInputStream dis) throws IOException {
        UTXO utxo = deserialize(dis);

        // 读取状态（小端字节序）
        byte[] statusBytes = new byte[8];
        dis.readFully(statusBytes);
        utxo.setStatus(com.bit.coin.utils.SerializeUtils.littleEndianToLong(statusBytes));
        return utxo;
    }

    /**
     * 从字节数组反序列化UTXO所有字段（包含状态字段）
     * @param data 字节数组
     * @return UTXO对象
     * @throws IOException 如果发生I/O错误
     */
    public static UTXO deserializeWithStatus(byte[] data) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        DataInputStream dis = new DataInputStream(bais);
        UTXO utxo = deserializeWithStatus(dis);
        dis.close();
        return utxo;
    }


    //获取KEY [交易ID][索引]
    @JsonIgnore
    public byte[] getKey() {
        return com.bit.coin.utils.SerializeUtils.concat(txId, com.bit.coin.utils.SerializeUtils.intToLittleEndian(index));
    }

    @JsonIgnore
    public String getKeyStr() {
        return bytesToHex(getKey());
    }

    @JsonIgnore
    public static byte[] getKey(byte[] txId1, int index) {
        return com.bit.coin.utils.SerializeUtils.concat(txId1, com.bit.coin.utils.SerializeUtils.intToLittleEndian(index));
    }

    @JsonIgnore
    public static String getKeyStr(byte[] txId1, int index) {
        return bytesToHex(getKey(txId1,index));
    }



    //获取地址索引 [地址][交易ID][索引]
    @JsonIgnore
    public byte[] getAddressKey() {
        return com.bit.coin.utils.SerializeUtils.concat(script,getKey());
    }


    //克隆
    public UTXO clone() {
        UTXO utxo = new UTXO();
        utxo.setTxId(txId);
        utxo.setIndex(index);
        utxo.setValue(value);
        utxo.setCoinbase(coinbase);
        utxo.setHeight(height);
        utxo.setScript(script);
        utxo.setStatus(status);
        return utxo;
    }

    public boolean isSpendable() {
        return UTXOStatusResolver.isSpendable(status);
    }
}
