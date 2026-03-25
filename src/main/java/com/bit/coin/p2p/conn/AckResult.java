package com.bit.coin.p2p.conn;

import lombok.Data;

@Data
public class AckResult {

    //本次批量确认是否成功
    private boolean success;

    //是否全部全部确认 或者 是否发送完成
    private boolean completed;

    //接收窗口大小
    private int receiveWindow;

    //确认了多少帧
    private int ackedFrameCounts;



    //全参数构造
    public AckResult(boolean success, boolean completed, int receiveWindow, int ackedFrameCounts) {
        this.success = success;
        this.completed = completed;
        this.receiveWindow = receiveWindow;
        this.ackedFrameCounts = ackedFrameCounts;
    }
}
