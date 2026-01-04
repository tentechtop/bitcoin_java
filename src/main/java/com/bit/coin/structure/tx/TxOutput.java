package com.bit.coin.structure.tx;

import com.bit.coin.structure.block.HexByteArraySerializer;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;


@JsonPropertyOrder({"value", "scriptPubKey"})  // 按指定顺序输出
@Data
public class TxOutput {

    /**
     * [8字节] 金额（单位：satoshi）
     */
    private long value;

    /**
     * [20字节] 公钥哈希
     * 接收方44长度字符串解码后得到32字节公钥 - 经过Sha256 RIPEMD160 后的20字节生成的锁定脚本
     */
    @JsonSerialize(using = HexByteArraySerializer.class)
    private byte[] scriptPubKey;

    // 常量定义
    public static final int SCRIPT_PUBKEY_LENGTH = 20; // 锁定脚本长度
    public static final int SERIALIZED_LENGTH = 28;    // 序列化总长度: 8+20=28字节



    /**
     * 验证字段长度
     */
    public void validate() {
        if (scriptPubKey == null || scriptPubKey.length != SCRIPT_PUBKEY_LENGTH) {
            throw new IllegalStateException("锁定脚本必须为" + SCRIPT_PUBKEY_LENGTH + "字节");
        }
    }

    /**
     * 序列化交易输出为字节数组
     * @return 序列化后的字节数组
     * @throws IOException 如果发生I/O错误
     */
    public byte[] serialize() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.write(com.bit.coin.utils.SerializeUtils.longToLittleEndian(value));
        com.bit.coin.utils.SerializeUtils.writeFixedBytes(dos, scriptPubKey, SCRIPT_PUBKEY_LENGTH);
        dos.close();
        return baos.toByteArray();
    }

    /**
     * 从DataInputStream反序列化交易输出
     * @param dis 数据输入流
     * @return TxOutput对象
     * @throws IOException 如果发生I/O错误
     */
    public static TxOutput deserialize(DataInputStream dis) throws IOException {
        TxOutput output = new TxOutput();
        byte[] valueBytes = new byte[8];
        dis.readFully(valueBytes);
        output.setValue(com.bit.coin.utils.SerializeUtils.littleEndianToLong(valueBytes));
        byte[] scriptPubKey = com.bit.coin.utils.SerializeUtils.readFixedBytes(dis, SCRIPT_PUBKEY_LENGTH);
        output.setScriptPubKey(scriptPubKey);
        return output;
    }

    /**
     * 从字节数组反序列化交易输出
     * @param data 字节数组
     * @return TxOutput对象
     * @throws IOException 如果发生I/O错误
     */
    public static TxOutput deserialize(byte[] data) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        DataInputStream dis = new DataInputStream(bais);
        TxOutput output = deserialize(dis);
        dis.close();
        return output;
    }


}
