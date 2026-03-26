package com.bit.coin.p2p.conn;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static com.bit.coin.p2p.conn.QuicFrameEnum.*;

/**
 * 数据帧 连接下的数据（无对象池化 + 自定义序列化/反序列化）
 */
@Slf4j
@Data
public class QuicFrame {
    private long connectionId; // 雪花算法生成的连接ID
    private long dataId; // 雪花算法生成的数据ID
    private int total; // 分片数量 4字节
    private byte frameType; // 帧类型 1字节
    private int sequence; // 序列号（分片顺序）0-total 最大序列号是total-1 4字节
    private int frameTotalLength; // 帧总长 4字节
    private byte[] payload; // 有效载荷  ACK帧会携带4字节接收窗口

    // 扩展字段 接收时写入
    private InetSocketAddress remoteAddress;
    // 发送时间 不参与序列化反序列化
    private long time;
    //是否重传帧
    private boolean isRetransmit; // 新增：是否为重传帧（首次发送=false，重传=true）


    // 固定头部长度 = connectionId(8) + dataId(8) + total(4) + frameType(1) + sequence(4) + frameTotalLength(4)
    public static final int FIXED_HEADER_LENGTH = 8 + 8 + 4 + 1 + 4 + 4;

    // 无参构造器
    public QuicFrame() {
    }

    public static final ByteBufAllocator ALLOCATOR = ByteBufAllocator.DEFAULT;



    // ========== 通用转换方法（提取重复逻辑） ==========
    /**
     * 将long值以大端序写入字节数组指定位置
     * @param value 要写入的long值
     * @param bytes 目标字节数组
     * @param index 起始索引
     * @return 写入后的索引位置
     */
    private static int writeLongToBytes(long value, byte[] bytes, int index) {
        bytes[index++] = (byte) (value >> 56 & 0xFF);
        bytes[index++] = (byte) (value >> 48 & 0xFF);
        bytes[index++] = (byte) (value >> 40 & 0xFF);
        bytes[index++] = (byte) (value >> 32 & 0xFF);
        bytes[index++] = (byte) (value >> 24 & 0xFF);
        bytes[index++] = (byte) (value >> 16 & 0xFF);
        bytes[index++] = (byte) (value >> 8 & 0xFF);
        bytes[index++] = (byte) (value & 0xFF);
        return index;
    }

    /**
     * 从字节数组指定位置以大端序读取long值
     * @param bytes 源字节数组
     * @param index 起始索引
     * @return 读取到的long值
     */
    private static long readLongFromBytes(byte[] bytes, int index) {
        long value = 0;
        value |= ((long) (bytes[index++] & 0xFF)) << 56;
        value |= ((long) (bytes[index++] & 0xFF)) << 48;
        value |= ((long) (bytes[index++] & 0xFF)) << 40;
        value |= ((long) (bytes[index++] & 0xFF)) << 32;
        value |= ((long) (bytes[index++] & 0xFF)) << 24;
        value |= ((long) (bytes[index++] & 0xFF)) << 16;
        value |= ((long) (bytes[index++] & 0xFF)) << 8;
        value |= (long) (bytes[index++] & 0xFF);
        return value;
    }

    /**
     * 将int值以大端序写入字节数组指定位置
     * @param value 要写入的int值
     * @param bytes 目标字节数组
     * @param index 起始索引
     * @return 写入后的索引位置
     */
    private static int writeIntToBytes(int value, byte[] bytes, int index) {
        bytes[index++] = (byte) (value >> 24 & 0xFF);
        bytes[index++] = (byte) (value >> 16 & 0xFF);
        bytes[index++] = (byte) (value >> 8 & 0xFF);
        bytes[index++] = (byte) (value & 0xFF);
        return index;
    }

    /**
     * 从字节数组指定位置以大端序读取int值
     * @param bytes 源字节数组
     * @param index 起始索引
     * @return 读取到的int值
     */
    private static int readIntFromBytes(byte[] bytes, int index) {
        int value = 0;
        value |= (bytes[index++] & 0xFF) << 24;
        value |= (bytes[index++] & 0xFF) << 16;
        value |= (bytes[index++] & 0xFF) << 8;
        value |= (bytes[index++] & 0xFF);
        return value;
    }

    // ========== 核心：自定义序列化（转字节数组） ==========
    public byte[] serialize() {
        // 前置校验
        if (!isValid()) {
            throw new IllegalStateException("QuicFrame is invalid, cannot serialize: " + this);
        }

        // 1. 计算总长度：固定头部 + payload长度
        int payloadLen = payload == null ? 0 : payload.length;
        int totalLen = FIXED_HEADER_LENGTH + payloadLen;

        // 2. 创建字节数组并写入数据（大端序）
        byte[] result = new byte[totalLen];
        int index = 0;

        // 写入connectionId（8字节）
        index = writeLongToBytes(connectionId, result, index);

        // 写入dataId（8字节）
        index = writeLongToBytes(dataId, result, index);

        // 写入total（4字节）
        index = writeIntToBytes(total, result, index);

        // 写入frameType（1字节）
        result[index++] = frameType;

        // 写入sequence（4字节）
        index = writeIntToBytes(sequence, result, index);

        // 写入frameTotalLength（4字节）
        index = writeIntToBytes(frameTotalLength, result, index);

        // 写入payload（剩余字节）
        if (payloadLen > 0 && payload != null) {
            System.arraycopy(payload, 0, result, index, payloadLen);
        }

        return result;
    }

    // ========== 核心：自定义反序列化（从字节数组还原） ==========
    public static QuicFrame deserialize(byte[] data) {
        // 前置校验
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Deserialization data is empty");
        }
        if (data.length < FIXED_HEADER_LENGTH) {
            throw new IllegalArgumentException(
                    "Insufficient bytes for header: need " + FIXED_HEADER_LENGTH
                            + ", actual " + data.length
            );
        }

        QuicFrame frame = new QuicFrame();
        int index = 0;

        try {
            // 读取connectionId（8字节）
            frame.connectionId = readLongFromBytes(data, index);
            index += 8; // long占8字节，索引后移

            // 读取dataId（8字节）
            frame.dataId = readLongFromBytes(data, index);
            index += 8; // long占8字节，索引后移

            // 读取total（4字节）
            frame.total = readIntFromBytes(data, index);
            index += 4; // int占4字节，索引后移

            // 读取frameType（1字节）
            frame.frameType = data[index++];

            // 读取sequence（4字节）
            frame.sequence = readIntFromBytes(data, index);
            index += 4; // int占4字节，索引后移

            // 读取frameTotalLength（4字节）
            frame.frameTotalLength = readIntFromBytes(data, index);
            index += 4; // int占4字节，索引后移

            // 校验总长度合法性
            if (frame.frameTotalLength != data.length) {
                throw new IllegalArgumentException(
                        "Frame total length mismatch: expected " + frame.frameTotalLength
                                + ", actual " + data.length
                );
            }

            // 读取payload（剩余字节）
            int payloadLen = frame.frameTotalLength - FIXED_HEADER_LENGTH;
            if (payloadLen > 0) {
                frame.payload = new byte[payloadLen];
                System.arraycopy(data, index, frame.payload, 0, payloadLen);
            } else {
                frame.payload = new byte[0];
            }

            // 校验帧有效性
            if (!frame.isValid()) {
                throw new IllegalStateException("Deserialized QuicFrame is invalid: " + frame);
            }

            return frame;
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize QuicFrame from bytes", e);
        }
    }

    // ========== Netty ByteBuf 编解码（保留原有核心功能） ==========
    public ByteBuf encodeToByteBuf() {
        int payloadLen = payload == null ? 0 : payload.length;
        this.frameTotalLength = FIXED_HEADER_LENGTH + payloadLen;

        if (!isValid()) {
            throw new IllegalStateException("QuicFrame is invalid, cannot encode: " + this);
        }

        ByteBuf buf = Unpooled.buffer(this.frameTotalLength);
        try {
            buf.writeLong(connectionId);
            buf.writeLong(dataId);
            buf.writeInt(total);
            buf.writeByte(frameType);
            buf.writeInt(sequence);
            buf.writeInt(frameTotalLength);

            if (payload != null && payload.length > 0) {
                buf.writeBytes(payload);
            }
            return buf;
        } catch (Exception e) {
            buf.release();
            throw new RuntimeException("Failed to encode QuicFrame to ByteBuf", e);
        }
    }


    //使用池化版本
    public void encode(ByteBuf buf) {
        // 前置校验：帧必须有效
        if (!isValid()) {
            throw new IllegalStateException("QuicFrame is invalid, cannot encode: " + this);
        }
        try {
            // 1. 写入固定头部字段（大端序）
            buf.writeLong(connectionId);   // 8字节连接ID
            buf.writeLong(dataId);         // 8字节数据ID
            buf.writeInt(total);           // 4字节分片总数
            buf.writeByte(frameType);      // 1字节帧类型
            buf.writeInt(sequence);        // 4字节分片序列号
            buf.writeInt(frameTotalLength);// 4字节总长度

            // 2. 写入payload（有效载荷）
            if (payload != null){
                buf.writeBytes(payload);//非引用传递
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode QuicFrame to ByteBuf", e);
        }
    }




    public static QuicFrame decodeFromByteBuf(ByteBuf buf, InetSocketAddress remoteAddress) {
        buf.markReaderIndex();
        QuicFrame frame = new QuicFrame();

        try {
            if (buf.readableBytes() < FIXED_HEADER_LENGTH) {
                throw new IllegalArgumentException(
                        "Insufficient bytes for header: need " + FIXED_HEADER_LENGTH
                                + ", actual " + buf.readableBytes()
                );
            }

            frame.connectionId = buf.readLong();
            frame.dataId = buf.readLong();
            frame.total = buf.readInt();
            frame.frameType = buf.readByte();
            frame.sequence = buf.readInt();
            frame.frameTotalLength = buf.readInt();

            if (frame.frameTotalLength < FIXED_HEADER_LENGTH) {
                throw new IllegalArgumentException(
                        "Invalid frameTotalLength: " + frame.frameTotalLength
                                + ", must be ≥ " + FIXED_HEADER_LENGTH
                );
            }

            int payloadLen = frame.frameTotalLength - FIXED_HEADER_LENGTH;
            if (payloadLen > 0) {
                if (buf.readableBytes() < payloadLen) {
                    throw new IllegalArgumentException(
                            "Payload length mismatch: expected " + payloadLen
                                    + ", actual " + buf.readableBytes()
                    );
                }
                frame.payload = new byte[payloadLen];
                buf.readBytes(frame.payload);
            } else {
                frame.payload = new byte[0];
            }

            frame.remoteAddress = remoteAddress;

            if (!frame.isValid()) {
                throw new IllegalStateException("Deserialized QuicFrame is invalid: " + frame);
            }
            return frame;
        } catch (Exception e) {
            buf.resetReaderIndex();
            throw new RuntimeException("Failed to decode QuicFrame from ByteBuf", e);
        }
    }

    // ========== 辅助方法 ==========
    public boolean isValid() {
        return frameTotalLength >= FIXED_HEADER_LENGTH  // 总长度至少包含固定头部
                && sequence >= 0                       // 序列号非负
                && total >= 0                          // 分片数允许为0（非分片）
                && (total == 0 || sequence < total);   // 分片数为0时忽略sequence校验，否则sequence<total
    }




    // 批量ACK最大支持帧数（避免payload过度膨胀）
    public static final int BATCH_ACK_MAX_FRAMES = 10;
    // 接收窗口固定占用字节数（即使为0也占用）
    private static final int WINDOW_FIXED_BYTES = 4;


    /**
     * 向【普通ACK帧/取消帧/更新窗口帧】写入接收窗口大小
     * @param windowSize 接收窗口大小（允许为0，仍占用4字节）
     */
    public void writeAckFrameWindowSize(int windowSize) {
        // 1. 校验帧类型：支持 ACK帧、取消帧、更新窗口帧
        if (!isWindowAckFrame()) {
            throw new IllegalArgumentException(
                    "仅支持 ACK帧、取消帧、更新窗口帧 写入窗口大小，当前帧类型: " + this.frameType);
        }
        // 2. 构造payload（固定4字节窗口）
        byte[] payload = new byte[WINDOW_FIXED_BYTES];
        writeIntToBytes(windowSize, payload, 0);
        // 3. 更新payload和总长度
        this.payload = payload;
        this.frameTotalLength = FIXED_HEADER_LENGTH + payload.length;
        // 4. 校验帧有效性
        if (!isValid()) {
            throw new IllegalStateException("写入窗口大小后帧无效: " + this);
        }
    }

    /**
     * 从【普通ACK帧/取消帧/更新窗口帧】读取接收窗口大小
     * @return 接收窗口大小（0表示窗口关闭）
     */
    public int readAckFrameWindowSize() {
        // 1. 校验帧类型：支持 ACK帧、取消帧、更新窗口帧
        if (!isWindowAckFrame()) {
            throw new IllegalArgumentException(
                    "仅支持 ACK帧、取消帧、更新窗口帧 读取窗口大小，当前帧类型: " + this.frameType);
        }
        // 2. 校验payload长度（固定4字节）
        if (payload == null || payload.length != WINDOW_FIXED_BYTES) {
            throw new IllegalStateException(
                    "窗口帧payload长度非法：预期" + WINDOW_FIXED_BYTES + "，实际" + (payload == null ? 0 : payload.length));
        }
        // 3. 读取4字节窗口大小
        return readIntFromBytes(payload, 0);
    }

    // ===================== 新增：通用帧类型校验方法 =====================
    /**
     * 判断是否为支持窗口读写的ACK类帧
     * 包含：ACK帧、取消帧(CANCEL_FRAME)、更新窗口帧(UPDATE_WINDOW_FRAME)
     */
    private boolean isWindowAckFrame() {
        byte type = this.frameType;
        return type == DATA_ACK_FRAME.getCode()
                || type == ALL_ACK_FRAME.getCode()
                || type == CANCEL_FRAME.getCode()
                || type == UPDATE_WINDOW_FRAME.getCode();
    }



    //向ALL ACK帧中写入窗口大小
    /**
     * 向全量ACK帧（FRAME_TYPE_ALL_ACK）写入接收窗口大小
     * @param windowSize 接收窗口大小（允许为0，仍占用4字节）
     */
    public void writeAllAckFrameWindowSize(int windowSize) {
        // 1. 校验帧类型
        if (this.frameType != ALL_ACK_FRAME.getCode()) {
            throw new IllegalArgumentException("Only FRAME_TYPE_ALL_ACK(" + ALL_ACK_FRAME + ") can write window size, current type: " + this.frameType);
        }
        // 2. 构造payload（仅4字节窗口）
        byte[] payload = new byte[WINDOW_FIXED_BYTES];
        writeIntToBytes(windowSize, payload, 0);
        // 3. 更新payload和总长度
        this.payload = payload;
        this.frameTotalLength = FIXED_HEADER_LENGTH + payload.length;
        // 4. 校验帧有效性
        if (!isValid()) {
            throw new IllegalStateException("ALL ACK frame is invalid after write window size: " + this);
        }
    }
    //获取ALL ACK帧中的窗口大小
    /**
     * 从全量ACK帧（FRAME_TYPE_ALL_ACK）读取接收窗口大小
     * @return 接收窗口大小（0表示窗口关闭）
     */
    public int readAllAckFrameWindowSize() {
        // 1. 校验帧类型
        if (this.frameType != ALL_ACK_FRAME.getCode()) {
            throw new IllegalArgumentException("Only FRAME_TYPE_ALL_ACK(" + ALL_ACK_FRAME + ") can read window size, current type: " + this.frameType);
        }
        // 2. 校验payload长度
        if (payload == null || payload.length != WINDOW_FIXED_BYTES) {
            throw new IllegalStateException("ALL ACK frame payload length invalid: expected " + WINDOW_FIXED_BYTES + ", actual " + (payload == null ? 0 : payload.length));
        }
        // 3. 读取4字节窗口大小
        return readIntFromBytes(payload, 0);
    }

    //向批量ACK帧中payload中写入   参数int[], int windowSize
    /**
     * 向批量ACK帧（FRAME_TYPE_BATCH_ACK）写入确认序号列表和接收窗口大小
     * payload结构：1字节数量 + N×4字节序号 + 4字节窗口（最多支持10帧）
     * @param ackSequences 确认的帧序号列表（非空、长度≤10、序号≥0）
     * @param windowSize 接收窗口大小（允许为0，仍占用4字节）
     */
    public void writeBatchAckFramePayload(int[] ackSequences, int windowSize) {
        // 1. 校验帧类型
        if (this.frameType != BATCH_ACK_FRAME.getCode()) {
            throw new IllegalArgumentException("Only FRAME_TYPE_BATCH_ACK(" + BATCH_ACK_FRAME + ") can write batch payload, current type: " + this.frameType);
        }
        // 2. 校验序号列表
        if (ackSequences == null || ackSequences.length == 0) {
            throw new IllegalArgumentException("Batch ACK sequences cannot be empty");
        }
        if (ackSequences.length > BATCH_ACK_MAX_FRAMES) {
            throw new IllegalArgumentException("Batch ACK sequences exceed max limit: " + BATCH_ACK_MAX_FRAMES + ", actual: " + ackSequences.length);
        }
        for (int seq : ackSequences) {
            if (seq < 0) {
                throw new IllegalArgumentException("Batch ACK sequence cannot be negative: " + seq);
            }
        }
        // 3. 计算payload长度：1字节数量 + 序号数×4 + 4字节窗口
        int sequenceCount = ackSequences.length;
        int payloadLen = 1 + sequenceCount * 4 + WINDOW_FIXED_BYTES;
        byte[] payload = new byte[payloadLen];
        int index = 0;

        // 4. 写入序号数量（1字节）
        payload[index++] = (byte) sequenceCount;

        // 5. 写入序号列表（每个4字节）
        for (int seq : ackSequences) {
            index = writeIntToBytes(seq, payload, index);
        }

        // 6. 写入窗口大小（最后4字节）
        index = writeIntToBytes(windowSize, payload, index);

        // 7. 更新payload和总长度
        this.payload = payload;
        this.frameTotalLength = FIXED_HEADER_LENGTH + payloadLen;

        // 8. 校验帧有效性
        if (!isValid()) {
            throw new IllegalStateException("Batch ACK frame is invalid after write payload: " + this);
        }
    }


    //从批量ACK中拿到ACK列表
    /**
     * 从批量ACK帧（FRAME_TYPE_BATCH_ACK）读取确认序号列表
     * @return 确认的帧序号数组（非空）
     */
    public int[] readBatchAckFrameSequences() {
        // 1. 校验帧类型
        if (this.frameType != BATCH_ACK_FRAME.getCode()) {
            throw new IllegalArgumentException("Only FRAME_TYPE_BATCH_ACK(" + BATCH_ACK_FRAME + ") can read sequences, current type: " + this.frameType);
        }
        // 2. 校验payload长度
        if (payload == null || payload.length < 1 + WINDOW_FIXED_BYTES) {
            throw new IllegalStateException("Batch ACK payload too short: " + (payload == null ? 0 : payload.length));
        }
        // 3. 读取序号数量（1字节）
        int sequenceCount = payload[0] & 0xFF; // 无符号读取
        if (sequenceCount <= 0 || sequenceCount > BATCH_ACK_MAX_FRAMES) {
            throw new IllegalStateException("Batch ACK sequence count invalid: " + sequenceCount);
        }
        // 4. 校验payload长度匹配
        int expectedPayloadLen = 1 + sequenceCount * 4 + WINDOW_FIXED_BYTES;
        if (payload.length != expectedPayloadLen) {
            throw new IllegalStateException("Batch ACK payload length mismatch: expected " + expectedPayloadLen + ", actual " + payload.length);
        }
        // 5. 读取序号列表
        int[] sequences = new int[sequenceCount];
        int index = 1; // 跳过1字节数量
        for (int i = 0; i < sequenceCount; i++) {
            sequences[i] = readIntFromBytes(payload, index);
            index += 4;
        }
        return sequences;
    }

    //从批量ACK中拿到windowSize
    /**
     * 从批量ACK帧（FRAME_TYPE_BATCH_ACK）读取接收窗口大小
     * @return 接收窗口大小（0表示窗口关闭）
     */
    public int readBatchAckFrameWindowSize() {
        // 1. 校验帧类型
        if (this.frameType != BATCH_ACK_FRAME.getCode()) {
            throw new IllegalArgumentException("Only FRAME_TYPE_BATCH_ACK(" + BATCH_ACK_FRAME + ") can read window size, current type: " + this.frameType);
        }
        // 2. 校验payload长度
        if (payload == null || payload.length < 1 + WINDOW_FIXED_BYTES) {
            throw new IllegalStateException("Batch ACK payload too short: " + (payload == null ? 0 : payload.length));
        }
        // 3. 读取最后4字节窗口大小
        int windowStartIndex = payload.length - WINDOW_FIXED_BYTES;
        return readIntFromBytes(payload, windowStartIndex);
    }



    //设置位图帧的位图 通过一个已经编辑好的位图来设置  参数byte[] int windowSize  转成Bit位图

    //获取位图帧的位图

    //获取位图帧的窗口大小


    /**
     * 向位图ACK帧（FRAME_TYPE_BITMAP_ACK）写入位图数据和接收窗口大小
     * payload结构：N字节位图数据 + 4字节接收窗口（大端序）
     * @param bitmapBytes 已编辑好的位图字节数组（纯位图数据，不包含窗口，可为空但不能为null）
     * @param windowSize 接收窗口大小（允许为0，仍占用4字节）
     */
    public void writeBitmapAckFramePayload(byte[] bitmapBytes, int windowSize) {
        // 1. 校验帧类型
        if (this.frameType != BITMAP_ACK_FRAME.getCode()) {
            throw new IllegalArgumentException("Only FRAME_TYPE_BITMAP_ACK(" + BITMAP_ACK_FRAME + ") can write bitmap payload, current type: " + this.frameType);
        }
        // 2. 校验位图数组（不能为null，空数组表示无位图数据）
        if (bitmapBytes == null) {
            throw new IllegalArgumentException("Bitmap bytes cannot be null");
        }
        // 3. 计算payload总长度：位图字节数 + 4字节窗口
        int bitmapLen = bitmapBytes.length;
        int payloadLen = bitmapLen + WINDOW_FIXED_BYTES;
        byte[] payload = new byte[payloadLen];
        int index = 0;

        // 4. 写入位图数据
        if (bitmapLen > 0) {
            System.arraycopy(bitmapBytes, 0, payload, index, bitmapLen);
            index += bitmapLen;
        }

        // 5. 写入窗口大小（最后4字节，大端序）
        index = writeIntToBytes(windowSize, payload, index);

        // 6. 更新payload和总长度
        this.payload = payload;
        this.frameTotalLength = FIXED_HEADER_LENGTH + payloadLen;

        // 7. 校验帧有效性
        if (!isValid()) {
            throw new IllegalStateException("Bitmap ACK frame is invalid after write payload: " + this);
        }
    }

    /**
     * 从位图ACK帧（FRAME_TYPE_BITMAP_ACK）读取位图数据
     * @return 纯位图字节数组（不包含窗口部分，空数组表示无位图数据）
     */
    public byte[] readBitmapAckFrameBitmap() {
        // 1. 校验帧类型
        if (this.frameType != BITMAP_ACK_FRAME.getCode()) {
            throw new IllegalArgumentException("Only FRAME_TYPE_BITMAP_ACK(" + BITMAP_ACK_FRAME + ") can read bitmap, current type: " + this.frameType);
        }
        // 2. 校验payload长度（至少包含4字节窗口）
        if (payload == null) {
            throw new IllegalStateException("Bitmap ACK payload is null");
        }
        if (payload.length < WINDOW_FIXED_BYTES) {
            throw new IllegalStateException("Bitmap ACK payload too short (need at least 4 bytes for window): " + payload.length);
        }
        // 3. 截取位图部分（除最后4字节外的所有数据）
        int bitmapLen = payload.length - WINDOW_FIXED_BYTES;
        byte[] bitmapBytes = new byte[bitmapLen];
        if (bitmapLen > 0) {
            System.arraycopy(payload, 0, bitmapBytes, 0, bitmapLen);
        }
        return bitmapBytes;
    }

    /**
     * 从位图ACK帧（FRAME_TYPE_BITMAP_ACK）读取所有已接收帧的序列号
     * 位图规则：字节数组为大端序，每个字节的最高位（第7位）对应该字节起始的第一个序列号
     * 例如：字节0的第7位 = 序列号0，字节0的第6位 = 序列号1 ... 字节0的第0位 = 序列号7，字节1的第7位 = 序列号8
     * @return 已接收帧的序列号数组（按从小到大排序，空数组表示无已接收帧）
     */
    public int[] readBitmapAckFrameReceivedSequences() {
        // 1. 校验帧类型
        if (this.frameType != BITMAP_ACK_FRAME.getCode()) {
            throw new IllegalArgumentException("Only FRAME_TYPE_BITMAP_ACK(" + BITMAP_ACK_FRAME + ") can read bitmap, current type: " + this.frameType);
        }
        // 2. 校验payload长度（至少包含4字节窗口）
        if (payload == null) {
            throw new IllegalStateException("Bitmap ACK payload is null");
        }
        if (payload.length < WINDOW_FIXED_BYTES) {
            throw new IllegalStateException("Bitmap ACK payload too short (need at least 4 bytes for window): " + payload.length);
        }

        // 3. 截取纯位图字节数组（剔除最后4字节窗口）
        int bitmapLen = payload.length - WINDOW_FIXED_BYTES;
        byte[] bitmapBytes = new byte[bitmapLen];
        if (bitmapLen > 0) {
            System.arraycopy(payload, 0, bitmapBytes, 0, bitmapLen);
        }

        // 4. 遍历位图字节数组，收集所有值为1的比特位对应的序列号
        List<Integer> receivedSeqList = new ArrayList<>();
        for (int byteIndex = 0; byteIndex < bitmapBytes.length; byteIndex++) {
            byte b = bitmapBytes[byteIndex];
            // 遍历当前字节的8个比特位（大端序：第7位对应序列号 byteIndex*8 + 0）
            for (int bitIndex = 0; bitIndex < 8; bitIndex++) {
                // 计算当前比特位对应的帧序列号
                int sequence = byteIndex * 8 + (7 - bitIndex);
                // 判断该比特位是否为1（通过位运算）
                if ((b & (1 << (7 - bitIndex))) != 0) {
                    receivedSeqList.add(sequence);
                }
            }
        }

        // 5. 转换为int数组返回
        return receivedSeqList.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * 从位图ACK帧（FRAME_TYPE_BITMAP_ACK）读取接收窗口大小
     * @return 接收窗口大小（0表示窗口关闭）
     */
    public int readBitmapAckFrameWindowSize() {
        // 1. 校验帧类型
        if (this.frameType != BITMAP_ACK_FRAME.getCode()) {
            throw new IllegalArgumentException("Only FRAME_TYPE_BITMAP_ACK(" + BITMAP_ACK_FRAME + ") can read window size, current type: " + this.frameType);
        }
        // 2. 校验payload长度（至少包含4字节窗口）
        if (payload == null || payload.length < WINDOW_FIXED_BYTES) {
            throw new IllegalStateException("Bitmap ACK payload too short (need at least 4 bytes for window): " + (payload == null ? 0 : payload.length));
        }
        // 3. 读取最后4字节窗口大小（大端序）
        int windowStartIndex = payload.length - WINDOW_FIXED_BYTES;
        return readIntFromBytes(payload, windowStartIndex);
    }




    public String getPayloadAsString() {
        return payload == null ? "" : new String(payload, StandardCharsets.UTF_8);
    }



    public int getSize() {
        return this.frameTotalLength;
    }

    public int getPayloadSize() {
        return this.frameTotalLength - FIXED_HEADER_LENGTH;
    }


    // 测试示例
    public static void main(String[] args) {
        // 1. 创建测试帧
        QuicFrame frame = new QuicFrame();
        frame.setConnectionId(123456789L);
        frame.setDataId(987654321L);
        frame.setTotal(3);
        frame.setFrameType((byte) 1);
        frame.setSequence(0);
        frame.setPayload("Hello Custom Serialization".getBytes());
        frame.setFrameTotalLength(FIXED_HEADER_LENGTH + frame.getPayload().length);

        // 2. 测试自定义序列化/反序列化
        byte[] serializedBytes = frame.serialize();
        QuicFrame deserializedFrame = QuicFrame.deserialize(serializedBytes);

        // 验证结果
        System.out.println("反序列化后ConnectionId: " + deserializedFrame.getConnectionId()); // 123456789
        System.out.println("反序列化后Payload: " + deserializedFrame.getPayloadAsString()); // Hello Custom Serialization
        System.out.println("反序列化后Total: " + deserializedFrame.getTotal()); // 3


        // 1. 构造普通ACK帧并写入窗口
        QuicFrame ackFrame = new QuicFrame();
        ackFrame.setConnectionId(123456789L);
        ackFrame.setDataId(987654321L);
        ackFrame.setFrameType(DATA_ACK_FRAME.getCode());
        ackFrame.writeAckFrameWindowSize(1024); // 窗口大小1024
        int window = ackFrame.readAckFrameWindowSize(); // 读取1024
        System.out.println("ACK帧窗口大小: " + window);

        //序列化 反序列化测试
        byte[] serialized = ackFrame.serialize();
        QuicFrame deserialized = QuicFrame.deserialize(serialized);
        System.out.println("序列化后长度: " + serialized.length);
        //窗口大小
        System.out.println("反序列化后窗口大小: " + deserialized.readAckFrameWindowSize());


    }
}