package com.bit.coin.p2p.conn;

import lombok.Data;

@Data
public class ReceiveResult {

    //本次接收处理 是否处理成功
    private boolean success;


    //是否全部全部确认 或者 是否发送完成
    private boolean completed;

    //ACK帧 如果需要回复ACK 就会返回一个ACK帧
    private QuicFrame ackFrame;

    //需要回补的窗口大小
    private int window = 0;


    //全参构造
    public ReceiveResult(boolean success,boolean completed, QuicFrame ackFrame) {
        this.success = success;
        this.completed = completed;
        this.ackFrame = ackFrame;
    }

}
