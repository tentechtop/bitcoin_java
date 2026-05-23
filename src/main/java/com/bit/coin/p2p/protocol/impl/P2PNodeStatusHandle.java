package com.bit.coin.p2p.protocol.impl;

import com.bit.coin.net.SmartSyncTrigger;
import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolHandler;
import com.bit.coin.p2p.protocol.message.NodeStatusPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;

@Slf4j
@Component
public class P2PNodeStatusHandle implements ProtocolHandler.VoidProtocolHandler {
    private final RoutingTable routingTable;
    private final SmartSyncTrigger smartSyncTrigger;

    public P2PNodeStatusHandle(RoutingTable routingTable, SmartSyncTrigger smartSyncTrigger) {
        this.routingTable = routingTable;
        this.smartSyncTrigger = smartSyncTrigger;
    }

    @Override
    public void handleVoid(P2PMessage requestParams) {
        try {
            NodeStatusPayload status = NodeStatusPayload.deserialize(requestParams.getData());
            if (!status.hasUsableTip() || requestParams.getSenderId() == null) {
                return;
            }

            String peerId = bytesToHex(requestParams.getSenderId());
            routingTable.updateLatest(peerId, bytesToHex(status.tipHash()), status.height(), status.chainWork());
            smartSyncTrigger.triggerIfRemoteAhead(status.height(), "node-status");

            log.debug("Received node status: peer={}, height={}, reason={}",
                    peerId, status.height(), status.reason());
        } catch (Exception e) {
            log.debug("Failed to handle node status: {}", e.getMessage());
        }
    }
}
