package com.bit.coin.p2p.conn;

/** 本地路由元数据，不能从未经认证的报文字段中获取。 */
public record RelayPath(long relayConnectionId, String peerId) {

    public boolean isAvailable() {
        QuicConnection hop = QuicConnectionManager.getConnection(relayConnectionId);
        return hop != null && !hop.isExpired() && hop.getRelayPath() == null && hop.getSessionCipher() != null;
    }
}
