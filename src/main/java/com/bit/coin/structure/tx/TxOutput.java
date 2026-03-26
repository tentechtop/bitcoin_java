package com.bit.coin.structure.tx;

import com.bit.coin.utils.HexByteArraySerializer;
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

import static com.bit.coin.utils.SerializeUtils.bytesToHex;

@Slf4j
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

    @JsonIgnore
    private WeakReference<Script> scriptPubKeyWk;


    // 常量定义
    public static final int SCRIPT_PUBKEY_LENGTH = 20; // 锁定脚本长度
    public static final int SERIALIZED_LENGTH = 28;    // 序列化总长度: 8+20=28字节


    @JsonIgnore
    public WeakReference<Script> getScriptPubKeyWk() {
        if (scriptPubKeyWk == null || scriptPubKeyWk.get() == null) {
            try {
                log.debug("打印公钥{} 长度{}",bytesToHex(scriptPubKey),scriptPubKey.length);
                Script script = Script.deserialize(scriptPubKey);
                scriptPubKeyWk = new WeakReference<>(script);
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
        //写非固定长度的脚本
        com.bit.coin.utils.SerializeUtils.writeVarInt(dos, scriptPubKey.length);
        if (scriptPubKey.length > 0) {
            dos.write(scriptPubKey);
        }
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

        long scriptPubKeyLength = com.bit.coin.utils.SerializeUtils.readVarInt(dis);
        if (scriptPubKeyLength > 0) {
            byte[] scriptSig = new byte[(int) scriptPubKeyLength];
            dis.readFully(scriptSig);
            output.setScriptPubKey(scriptSig);
        } else {
            output.setScriptPubKey(new byte[0]);
        }
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
