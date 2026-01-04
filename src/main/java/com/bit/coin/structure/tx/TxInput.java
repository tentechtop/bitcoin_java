package com.bit.coin.structure.tx;

import com.bit.coin.structure.block.HexByteArraySerializer;
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
import java.util.Arrays;

import static com.bit.coin.structure.tx.Transaction.TX_ID_LENGTH;

@Slf4j
@Data
@JsonPropertyOrder({"txId", "index", "scriptSig"})  // 按指定顺序输出
public class TxInput {

    /**
     * [32字节] 交易ID
     */
    @JsonSerialize(using = HexByteArraySerializer.class)
    private byte[] txId;

    /**
     * [4字节] 交易的输出索引
     */
    private int index;

    /**
     * [可变长度] 解锁脚本
     * 包含签名和公钥 [64][32]
     */
    @JsonSerialize(using = HexByteArraySerializer.class)
    private byte[] scriptSig;


    // 常量定义
    public static final int SCRIPT_SIG_LENGTH = 96; // 解锁脚本长度: 64+32
    public static final int SERIALIZED_LENGTH = 132; // 序列化总长度: 32+4+96=132字节


    /**
     * 验证输入合法性
     */
    public boolean verify(TxOutput output) {
        byte[] scriptPubKey = output.getScriptPubKey();
        //解锁脚本中的公钥经过Sha256 160后是否和锁定脚本一致
        byte[] publicKey = this.getPublicKey();
        byte[] bytes = Sha.applySHA256(Sha.applyRIPEMD160(publicKey));
        if (!Arrays.equals(bytes, scriptPubKey)){
            return false;
        }


        //如果不一致返回false
        //如果一致则验证签名
        return true;
    }


    /**
     * 验证字段长度
     */
    public void validate() {
        if (txId == null || txId.length != TX_ID_LENGTH) {
            throw new IllegalStateException("交易ID必须为" + TX_ID_LENGTH + "字节");
        }
        if (scriptSig == null || scriptSig.length != SCRIPT_SIG_LENGTH) {
            throw new IllegalStateException("解锁脚本必须为" + SCRIPT_SIG_LENGTH + "字节");
        }
    }

    /**
     * 获取签名字节（解锁脚本的前64字节）
     */
    @JsonIgnore
    public byte[] getSignature() {
        if (scriptSig == null || scriptSig.length < 64) {
            return new byte[64];
        }
        byte[] signature = new byte[64];
        System.arraycopy(scriptSig, 0, signature, 0, 64);
        return signature;
    }

    /**
     * 获取公钥字节（解锁脚本的后32字节）
     */
    @JsonIgnore
    public byte[] getPublicKey() {
        if (scriptSig == null || scriptSig.length < 96) {
            return new byte[32];
        }
        byte[] publicKey = new byte[32];
        System.arraycopy(scriptSig, 64, publicKey, 0, 32);
        return publicKey;
    }

    /**
     * 设置签名和公钥
     * @param signature 64字节签名字节数组
     * @param publicKey 32字节公钥字节数组
     */
    public void setSignatureAndPublicKey(byte[] signature, byte[] publicKey) {
        if (signature == null || signature.length != 64) {
            throw new IllegalArgumentException("签名必须为64字节");
        }
        if (publicKey == null || publicKey.length != 32) {
            throw new IllegalArgumentException("公钥必须为32字节");
        }

        byte[] scriptSig = new byte[SCRIPT_SIG_LENGTH];
        System.arraycopy(signature, 0, scriptSig, 0, 64);
        System.arraycopy(publicKey, 0, scriptSig, 64, 32);
        this.setScriptSig(scriptSig);
    }


    /**
     * 创建Coinbase交易输入
     * @param scriptSig Coinbase交易的特殊脚本（任意长度）
     */
    public static TxInput createCoinbaseInput(byte[] scriptSig) {
        TxInput input = new TxInput();
        // Coinbase交易的txId为全0
        input.setTxId(new byte[TX_ID_LENGTH]);
        // Coinbase交易的index为0xFFFFFFFF
        input.setIndex(0xFFFFFFFF);
        // 使用提供的脚本
        input.setScriptSig(scriptSig != null ? scriptSig : new byte[0]);
        return input;
    }

    /**
     * 判断是否为Coinbase交易输入
     */
    @JsonIgnore
    public boolean isCoinbase() {
        // 检查txId是否全0
        if (txId == null || txId.length != TX_ID_LENGTH) {
            return false;
        }
        for (byte b : txId) {
            if (b != 0) {
                return false;
            }
        }
        return index == 0;
    }

    /**
     * 序列化交易输入为字节数组
     * @return 序列化后的字节数组
     * @throws IOException 如果发生I/O错误
     */
    public byte[] serialize()  {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            com.bit.coin.utils.SerializeUtils.writeFixedBytes(dos, txId, TX_ID_LENGTH);
            com.bit.coin.utils.SerializeUtils.writeLittleEndianInt(dos, index);
            //如果是coinbase输入非固定长度
            if (isCoinbase()) {
                // 如果是Coinbase输入，脚本长度可变，先写入长度(VarInt)，再写入内容
                com.bit.coin.utils.SerializeUtils.writeVarInt(dos, scriptSig.length);
                if (scriptSig.length > 0) {
                    dos.write(scriptSig);
                }
            }else {
                com.bit.coin.utils.SerializeUtils.writeFixedBytes(dos, scriptSig, SCRIPT_SIG_LENGTH);
            }
            dos.close();
            return baos.toByteArray();
        }catch (Exception e){
            log.info("交易详情{}",this);
            throw new IllegalArgumentException("交易输入序列化失败",e);
        }
    }

    /**
     * 从DataInputStream反序列化交易输入
     * @param dis 数据输入流
     * @return TxInput对象
     * @throws IOException 如果发生I/O错误
     */
    public static TxInput deserialize(DataInputStream dis) throws IOException {
        TxInput input = new TxInput();

        // 1. 读取交易ID (32字节)
        byte[] txId = com.bit.coin.utils.SerializeUtils.readFixedBytes(dis, TX_ID_LENGTH);
        input.setTxId(txId);

        // 2. 读取输出索引 (4字节，小端)
        int index = com.bit.coin.utils.SerializeUtils.readLittleEndianInt(dis);
        input.setIndex(index);

        // 3. 读取解锁脚本
        // 先判断是否为Coinbase输入
        boolean isCoinbase = input.isCoinbase();
        if (isCoinbase) {
            // 如果是Coinbase输入，先读取长度(VarInt)，再读取内容
            long scriptSigLength = com.bit.coin.utils.SerializeUtils.readVarInt(dis);
            if (scriptSigLength > 0) {
                byte[] scriptSig = new byte[(int) scriptSigLength];
                dis.readFully(scriptSig);
                input.setScriptSig(scriptSig);
            } else {
                input.setScriptSig(new byte[0]);
            }
        } else {
            // 如果是普通输入，读取固定长度的脚本
            byte[] scriptSig = com.bit.coin.utils.SerializeUtils.readFixedBytes(dis, SCRIPT_SIG_LENGTH);
            input.setScriptSig(scriptSig);
        }

        return input;
    }


    /**
     * 从字节数组反序列化交易输入
     * @param data 字节数组
     * @return TxInput对象
     * @throws IOException 如果发生I/O错误
     */
    public static TxInput deserialize(byte[] data) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        DataInputStream dis = new DataInputStream(bais);
        TxInput input = deserialize(dis);
        dis.close();
        return input;
    }


}
