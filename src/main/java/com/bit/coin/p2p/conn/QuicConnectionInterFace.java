package com.bit.coin.p2p.conn;

import com.bit.coin.p2p.protocol.ProtocolEnum;

public interface QuicConnectionInterFace {

    byte[] sendData(String peerId, ProtocolEnum protocol, byte[] sendMsg, int time);


    void sendData(byte[] data);
}
