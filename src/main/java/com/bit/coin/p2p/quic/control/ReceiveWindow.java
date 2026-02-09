package com.bit.coin.p2p.quic.control;

import lombok.extern.slf4j.Slf4j;

/**
 * 流量控制器
 * 采用多层令牌桶 + BBR风格拥塞控制 + 自适应调整
 * 实时告诉发送方：“你最多还能给我发多少数据”，防止发送方发送过快导致接收方缓冲区溢出
 */
@Slf4j
public class ReceiveWindow {

    //默认最小



}