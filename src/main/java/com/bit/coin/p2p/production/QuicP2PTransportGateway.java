package com.bit.coin.p2p.production;

import com.bit.coin.p2p.conn.QuicConnection;
import com.bit.coin.p2p.conn.QuicConnectionManager;
import com.bit.coin.p2p.conn.QuicConnectionStats;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class QuicP2PTransportGateway implements P2PTransportGateway {
    @Override
    public Optional<P2PTransportConnection> connect(P2PAddress address) {
        QuicConnection connection = QuicConnectionManager.connectRemoteByAddr(address.rawAddress());
        if (connection == null || connection.isExpired()) {
            return Optional.empty();
        }
        return Optional.of(new P2PTransportConnection(
                normalizePeer(connection.getPeerId()),
                connection.getConnectionId(),
                connection.getRemoteAddress() == null ? "" : connection.getRemoteAddress().toString(),
                connection.isOutbound()
        ));
    }

    @Override
    public boolean disconnect(String peerIdHex) {
        return QuicConnectionManager.disConnectRemoteByPeerId(P2PAddressValidator.normalizePeerId(peerIdHex));
    }

    @Override
    public List<QuicConnectionStats> connectionStats() {
        return QuicConnectionManager.getConnectionStats();
    }

    private String normalizePeer(String peerId) {
        if (peerId == null || peerId.isBlank()) {
            return "";
        }
        return P2PAddressValidator.normalizePeerId(peerId);
    }
}
