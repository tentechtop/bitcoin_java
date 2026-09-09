package com.bit.coin.p2p.conn;

/** Local routing metadata, never accepted from an unauthenticated wire field. */
public record RelayPath(long relayConnectionId, String peerId) {
    public boolean isAvailable() {
        QuicConnection hop = QuicConnectionManager.getConnection(relayConnectionId);
        return hop != null && !hop.isExpired() && hop.getRelayPath() == null && hop.getSessionCipher() != null;
    }
}
