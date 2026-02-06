package com.bit.coin.blockchain;

import com.bit.coin.structure.block.HexByteArrayDeserializer;
import com.bit.coin.structure.block.HexByteArraySerializer;
import lombok.Data;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

@Data
public class Landmark {
    @JsonSerialize(using = HexByteArraySerializer.class)
    @JsonDeserialize(using = HexByteArrayDeserializer.class)  // 添加这行
    private byte[] hash;//32
    private int height;//4

    //序列化
    /**
     * 序列化：将Landmark转为36字节数组 [32字节hash][4字节height(大端序)]
     * @return 36字节的序列化结果，格式错误时返回null
     */
    public byte[] serialize() {
        // 边界校验：hash必须是32字节
        if (hash == null || hash.length != 32) {
            throw new IllegalArgumentException("Landmark hash必须是32字节");
        }

        // 1. 初始化36字节缓冲区（32+4）
        ByteBuffer buffer = ByteBuffer.allocate(36)
                .order(ByteOrder.BIG_ENDIAN); // 大端序（区块链行业通用）

        // 2. 写入32字节hash
        buffer.put(hash);
        // 3. 写入4字节height（大端序）
        buffer.putInt(height);

        return buffer.array();
    }

    //反序列化
    /**
     * 反序列化：从36字节数组解析出Landmark对象
     * @param bytes 36字节的序列化数据
     * @return Landmark对象，格式错误时返回null
     */
    public static Landmark deserialize(byte[] bytes) {
        // 边界校验：必须是36字节
        if (bytes == null || bytes.length != 36) {
            throw new IllegalArgumentException("反序列化Landmark必须传入36字节数组");
        }

        Landmark landmark = new Landmark();
        ByteBuffer buffer = ByteBuffer.wrap(bytes)
                .order(ByteOrder.BIG_ENDIAN); // 大端序解析

        // 1. 读取前32字节作为hash
        byte[] hash = new byte[32];
        buffer.get(hash);
        landmark.setHash(hash);

        // 2. 读取后4字节作为height（大端序解析）
        landmark.setHeight(buffer.getInt());

        return landmark;
    }

    /**
     * 序列化Landmark列表
     * 格式：[4字节列表长度(大端序)][N个36字节Landmark数据]
     * @param landmarks Landmark列表（不能为null，内部元素也不能为null）
     * @return 序列化后的字节数组
     */
    public static byte[] serializeList(List<Landmark> landmarks) {
        // 1. 边界校验
        if (landmarks == null) {
            throw new IllegalArgumentException("Landmark列表不能为null");
        }
        int listSize = landmarks.size();

        // 2. 计算总字节数：4字节(列表长度) + 每个Landmark36字节
        int totalLength = 4 + listSize * 36;
        ByteBuffer buffer = ByteBuffer.allocate(totalLength)
                .order(ByteOrder.BIG_ENDIAN); // 保持和单个序列化一致的大端序

        // 3. 写入列表长度（int类型，4字节）
        buffer.putInt(listSize);

        // 4. 遍历写入每个Landmark的序列化数据
        for (Landmark landmark : landmarks) {
            if (landmark == null) {
                throw new IllegalArgumentException("列表中不能包含null的Landmark对象");
            }
            byte[] singleLandmarkBytes = landmark.serialize();
            buffer.put(singleLandmarkBytes);
        }

        return buffer.array();
    }

    /**
     * 反序列化Landmark列表
     * @param bytes 序列化后的字节数组（格式：4字节长度 + N*36字节Landmark数据）
     * @return Landmark列表
     */
    public static List<Landmark> deserializeList(byte[] bytes) {
        // 1. 边界校验
        if (bytes == null) {
            throw new IllegalArgumentException("反序列化的字节数组不能为null");
        }
        if (bytes.length < 4) {
            throw new IllegalArgumentException("Landmark列表字节数组长度不能小于4字节（至少包含列表长度）");
        }

        // 2. 包装字节数组并设置大端序
        ByteBuffer buffer = ByteBuffer.wrap(bytes)
                .order(ByteOrder.BIG_ENDIAN);

        // 3. 读取列表长度
        int listSize = buffer.getInt();

        // 4. 校验剩余字节数是否匹配（剩余字节数必须等于 列表长度 * 36）
        int remainingBytes = buffer.remaining();
        int expectedRemaining = listSize * 36;
        if (remainingBytes != expectedRemaining) {
            throw new IllegalArgumentException(
                    String.format("Landmark列表字节数不匹配：期望剩余%d字节，实际剩余%d字节",
                            expectedRemaining, remainingBytes)
            );
        }

        // 5. 循环解析每个Landmark
        List<Landmark> result = new ArrayList<>(listSize); // 初始化容量提升性能
        for (int i = 0; i < listSize; i++) {
            byte[] singleLandmarkBytes = new byte[36];
            buffer.get(singleLandmarkBytes); // 读取单个Landmark的36字节数据
            Landmark landmark = Landmark.deserialize(singleLandmarkBytes);
            result.add(landmark);
        }

        return result;
    }


}
