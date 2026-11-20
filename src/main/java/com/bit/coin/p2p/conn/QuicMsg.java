package com.bit.coin.p2p.conn;

import com.bit.coin.p2p.protocol.P2PMessage;

import java.io.IOException;

/** 已解码的应用消息。入队时固定字节数，出队时按相同大小释放预算。 */
public record QuicMsg(long connectionId, long dataId, P2PMessage message, int wireLength, boolean trustedLocal) {

    public QuicMsg {
        if (wireLength != Math.addExact(P2PMessage.HEADER_LENGTH, message.getLength())) {
            throw new IllegalArgumentException("P2P envelope length mismatch");
        }
    }

    static QuicMsg fromWire(long connectionId, long dataId, byte[] plaintext) throws IOException {
        P2PMessage message = P2PMessage.deserialize(plaintext);
        if (message == null) {
            throw new IOException("Invalid P2P message envelope");
        }
        return new QuicMsg(connectionId, dataId, message, plaintext.length, false);
    }

    static QuicMsg local(long connectionId, long dataId, P2PMessage message) {
        return new QuicMsg(connectionId, dataId, message, P2PMessage.HEADER_LENGTH + message.getLength(), true);
    }
}
