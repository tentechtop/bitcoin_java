package com.bit.coin.p2p.conn;

import lombok.Data;

@Data
public class QuicMsg {
    private long connectionId;//连接ID
    private long dataId;//数据ID
    private byte[] data;
}
