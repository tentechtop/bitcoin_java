package com.bit.coin.p2p.protocol.dto;

import lombok.Data;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

@Data
public class BroadcastResource {

    private byte[] hash;

    // 类型 4个字节  0区块 1交易
    private int type;

    /**
     * 序列化方法：将对象转换为二进制字节数组
     * 格式：hash长度(4字节) + hash内容 + type(4字节)
     * @return 序列化后的字节数组
     * @throws IOException 序列化IO异常
     */
    public byte[] toBytes()  {
        // 字节数组输出流，用于拼接字节
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            // 1. 处理hash字段：先写长度（4字节），再写内容
            if (hash == null) {
                dos.writeInt(0); // hash为null时，长度写0
            } else {
                dos.writeInt(hash.length); // 写入hash的长度（4字节）
                dos.write(hash); // 写入hash的实际字节内容
            }

            // 2. 处理type字段：写入4字节int（DataOutputStream默认大端序，符合网络字节序规范）
            dos.writeInt(type);

            // 转换为最终字节数组
            return baos.toByteArray();
        }catch (Exception e){
            return null;
        }
    }

    /**
     * 反序列化方法：从二进制字节数组还原为对象
     * @param bytes 序列化后的字节数组
     * @return 还原后的BroadcastResource对象
     * @throws IOException 反序列化IO异常（如字节数组长度不足、格式错误）
     */
    public static BroadcastResource fromBytes(byte[] bytes)  {
        if (bytes == null || bytes.length < 8) { // 最少需要：hash长度(4) + type(4) = 8字节
            return null;
        }
        // 字节数组输入流，用于读取字节
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             DataInputStream dis = new DataInputStream(bais)) {

            BroadcastResource resource = new BroadcastResource();

            // 1. 读取hash字段：先读长度，再读内容
            int hashLength = dis.readInt(); // 读取hash长度（4字节）
            if (hashLength > 0) {
                byte[] hash = new byte[hashLength];
                dis.readFully(hash); // 确保读取完整的hash字节（避免部分读取）
                resource.setHash(hash);
            } else {
                resource.setHash(null); // 长度为0时，hash设为null
            }

            // 2. 读取type字段：读取4字节int
            resource.setType(dis.readInt());

            return resource;
        }catch (Exception e){
            return null;
        }
    }
}