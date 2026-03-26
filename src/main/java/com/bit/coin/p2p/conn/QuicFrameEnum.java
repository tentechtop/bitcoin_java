package com.bit.coin.p2p.conn;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Getter
@Slf4j
public enum QuicFrameEnum {
    DATA_FRAME((byte)1, "数据帧"),
    //[4字节窗口]
    DATA_ACK_FRAME((byte)2, "数据ACK帧"),
    //[4字节窗口]
    ALL_ACK_FRAME((byte)3, "ALL_ACK帧"),
    //[比特位图][4字节窗口]
    BITMAP_ACK_FRAME((byte)4, "位图ACK帧"),//BIT位图ACK

    //批量ACK帧 每一个批量ACK帧携带
    //批量ACK的负载携带 10个帧的ACK  [1,2,3,4,...][4字节窗口]
    /** 批量ACK帧：多帧确认（最多10帧），payload=1字节数量+N×4字节序号+4字节窗口 */
    BATCH_ACK_FRAME((byte)12, "批量ACK帧"),


    PING_FRAME((byte)5, "ping帧"),
    PONG_FRAME((byte)6, "pong帧"),

    CONNECT_REQUEST_FRAME((byte)7, "连接请求帧"),
    CONNECT_RESPONSE_FRAME((byte)8, "连接响应帧"),

    OFF_FRAME((byte)9, "下线帧"),//通知类帧 无回复


    //接收方告诉发送方 取消这个数据 意味着失败
    CANCEL_FRAME((byte)10, "取消帧"),

    //更新窗口帧
    UPDATE_WINDOW_FRAME((byte)11, "更新窗口帧"),






    ;

    private final byte code;
    private final String name;

    QuicFrameEnum(byte code, String name) {
        this.code = code;
        this.name = name;
    }


    public static QuicFrameEnum fromCode(int code) {
        //code转byte
        byte b = (byte)code;
        for (QuicFrameEnum e : values()) {
            if (e.getCode() == b) {
                return e;
            }
        }
        throw new IllegalArgumentException("无效的帧code：" + code);
    }

    public static QuicFrameEnum fromCode(byte code) {
        //code转byte
        for (QuicFrameEnum e : values()) {
            if (e.getCode() == code) {
                return e;
            }
        }
        throw new IllegalArgumentException("无效的帧code：" + code);
    }



}
