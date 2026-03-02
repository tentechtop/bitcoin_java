package com.bit.coin.p2p.quic;


import io.netty.buffer.ByteBuf;
import io.netty.util.Recycler;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;


/**
 * 数据帧 连接下的数据
 */
@Slf4j
@Data
public class QuicFrame {
    private long connectionId; // 雪花算法
    private long dataId; //  雪花算法
    private int total;//分片数量 4字节
    private byte frameType; // 帧类型 1字节
    private int sequence; // 序列号分片顺序 0-total 最大序列号是total-1 4字节  FIN帧是total-1  序列号是0就是第一帧 序列号是total-1就是最后一帧
    private int frameTotalLength;//载荷总长 4字节
    private byte[] payload; // 有效载荷





    //扩展字段 接收时写入
    private InetSocketAddress remoteAddress;
    //发送时间 不参与序列化反序列化
    private long time;


    // 固定头部长度 = connectionId(8) + dataId(8) + total(4) + frameType(1) + sequence(4) + frameTotalLength(4)
    public static final int FIXED_HEADER_LENGTH = 8 + 8 + 4 + 1 + 4 + 4;



    // 对象池 定义 Recycler 池，指定“新对象创建规则”
    private static final int RECYCLER_MAX_CAPACITY = 1024 * 1024 * 1024;
    private static final Recycler<QuicFrame> RECYCLER = new Recycler<QuicFrame>(RECYCLER_MAX_CAPACITY) {
        @Override
        protected QuicFrame newObject(Handle<QuicFrame> handle) {
            // 创建新对象时绑定 Netty 的 Handle
            return new QuicFrame(handle);
        }
    };
    private final Recycler.Handle<QuicFrame> handle;
    private QuicFrame(Recycler.Handle<QuicFrame> handle) {
        this.handle = handle;
    }
    // 从对象池获取实例
    public static QuicFrame acquire() {
        return RECYCLER.get();
    }



    public void release() {
        try {
            // 重置字段
            connectionId = 0;
            frameType = 0;
            sequence = 0;
            total = 0;
            dataId = 0;
            frameTotalLength = 0; // 新增：重置帧总长度
            remoteAddress = null; // 新增：重置远端地址
            payload = null;
            // 归还对象（增加日志，便于排查对象池问题）
            handle.recycle(this);
            log.debug("[释放完成] 对象已归还到对象池，connectionId:{} dataId:{}", connectionId, dataId);
        } catch (Exception e) {
            log.error("[释放异常] connectionId:{} dataId:{}", connectionId, dataId, e);
        }
    }


    //将帧序列化为字节缓冲
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

    public static QuicFrame decode(ByteBuf buf, InetSocketAddress remoteAddress) {
        // 标记当前读取位置，异常时重置
        buf.markReaderIndex();

        QuicFrame frame = QuicFrame.acquire();
        try {
            // 前置校验：缓冲区至少包含固定头部
            if (buf.readableBytes() < FIXED_HEADER_LENGTH) {
                throw new IllegalArgumentException(
                        "Insufficient bytes for QuicFrame header: need " + FIXED_HEADER_LENGTH
                                + ", actual " + buf.readableBytes()
                );
            }

            // 读取固定头部字段（大端序）
            frame.connectionId = buf.readLong();
            frame.dataId = buf.readLong();
            frame.total = buf.readInt();
            frame.frameType = buf.readByte();
            frame.sequence = buf.readInt();
            frame.frameTotalLength = buf.readInt();

            // 校验frameTotalLength合法性（必须包含完整头部+有效载荷）
            if (frame.frameTotalLength < FIXED_HEADER_LENGTH) {
                throw new IllegalArgumentException(
                        "frameTotalLength invalid: must be ≥ " + FIXED_HEADER_LENGTH
                                + ", actual=" + frame.frameTotalLength
                );
            }

            // 计算预期有效载荷长度并校验缓冲区剩余字节
            int expectedPayloadLength = frame.frameTotalLength - FIXED_HEADER_LENGTH;
            int actualRemainingBytes = buf.readableBytes();
            if (actualRemainingBytes != expectedPayloadLength) {
                throw new IllegalArgumentException(
                        "Payload length mismatch: expected " + expectedPayloadLength
                                + ", actual " + actualRemainingBytes
                                + ", frameTotalLength=" + frame.frameTotalLength
                );
            }

            // 读取有效载荷（拷贝数据到byte[]，不持有ByteBuf引用）
            if (expectedPayloadLength > 0) {
                frame.payload = new byte[expectedPayloadLength];
                buf.readBytes(frame.payload); // 仅拷贝数据，不影响ByteBuf引用计数
            } else {
                frame.payload = new byte[0]; // 空载荷用空数组表示，避免null
            }

            // 设置远端地址
            frame.remoteAddress = remoteAddress;

            // 校验解析后的帧有效性
            if (!frame.isValid()) {
                throw new IllegalStateException("Deserialized QuicFrame is invalid: " + frame);
            }

            return frame;
        } catch (Exception e) {
            // 异常时重置读取位置，不影响后续对该ByteBuf的操作
            buf.resetReaderIndex();
            throw new RuntimeException("Failed to decode QuicFrame from ByteBuf", e);
        }
    }





    public boolean isValid() {

        return true;
    }

}
