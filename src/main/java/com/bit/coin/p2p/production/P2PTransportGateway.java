package com.bit.coin.p2p.production;

import com.bit.coin.p2p.conn.QuicConnectionStats;

import java.util.List;
import java.util.Optional;

public interface P2PTransportGateway {
    Optional<P2PTransportConnection> connect(P2PAddress address);

    boolean disconnect(String peerIdHex);

    List<QuicConnectionStats> connectionStats();
}
