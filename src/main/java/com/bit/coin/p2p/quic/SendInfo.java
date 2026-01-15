package com.bit.coin.p2p.quic;

import lombok.Data;

@Data
public class SendInfo {

    //数据大小
    private int size;
    //帧总数
    private int total;
    //总耗时
    private long totalTime;
    //总发送帧 total+补发的帧
    private int sendTotalFrame;

}
