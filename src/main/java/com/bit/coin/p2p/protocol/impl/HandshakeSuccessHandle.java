package com.bit.coin.p2p.protocol.impl;

import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.p2p.peer.Peer;
import com.bit.coin.p2p.protocol.NetworkHandshake;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;

@Slf4j
@Component
public class HandshakeSuccessHandle implements ProtocolHandler.VoidProtocolHandler{

    @Autowired
    private RoutingTable routingTable;

    @Override
    public void handleVoid(P2PMessage requestParams) throws IOException {
        //握手成功后将节点加入到路由表
        log.debug("将节点加入到路由表");
        byte[] data = requestParams.getData();
        log.debug("数据长度{}",data.length);
        Peer deserialize = Peer.deserialize(data);
        log.debug("反序列化后{}",deserialize.toString());
        routingTable.addPeer(deserialize);
    }
}
