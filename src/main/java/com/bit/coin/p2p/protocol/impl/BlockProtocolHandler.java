package com.bit.coin.p2p.protocol.impl;

import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolHandler;

import java.io.IOException;

public class BlockProtocolHandler implements ProtocolHandler.ResultProtocolHandler {


    @Override
    public byte[] handleResult(P2PMessage requestParams) throws IOException {
        return new byte[0];
    }
}
