package com.bit.coin.p2p.quic;

import lombok.Data;

@Data
public class SendFrameInfo {

    //总大小
    private int size;
    //总耗时
    private long totalTime;

}
