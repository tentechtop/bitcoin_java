package com.bit.coin.p2p.impl;


import com.bit.coin.p2p.quic.QuicConnection;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 随用随建是其最优使用方式
 */

@Data
@Slf4j
public class QuicNodeWrapper {
    private byte[] nodeId; // 节点ID 公钥的base58编码
    private QuicConnection quicConnection;

    public void close() {

    }
}
