package com.bit.coin.p2p.quic;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Getter
@Slf4j
public enum QuicFrameEnum {
    DATA_FRAME((byte)1, "数据帧"),
    DATA_ACK_FRAME((byte)2, "数据ACK帧"),
    ALL_ACK_FRAME((byte)3, "ALL_ACK帧"),
    BATCH_ACK_FRAME((byte)4, "BATCH_ACK帧"),

    PING_FRAME((byte)5, "ping帧"),
    PONG_FRAME((byte)6, "pong帧"),

    CONNECT_REQUEST_FRAME((byte)7, "连接请求帧"),
    CONNECT_RESPONSE_FRAME((byte)8, "连接响应帧"),

    OFF_FRAME((byte)9, "下线帧"),//通知类帧 无回复







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
