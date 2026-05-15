package com.bit.coin.p2p.protocol.impl;

import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class P2PPeerHintsHandle implements ProtocolHandler.VoidProtocolHandler {
    @Autowired
    private RoutingTable routingTable;

    @Override
    public void handleVoid(P2PMessage requestParams) {
        try {
            routingTable.receivePeerHints(requestParams.getData(), requestParams.getSenderId());
            log.debug("received DHT peer hints");
        } catch (Exception e) {
            log.debug("failed to handle DHT peer hints: {}", e.getMessage());
        }
    }
}
