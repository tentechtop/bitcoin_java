package com.bit.coin.p2p.protocol.impl;

import com.bit.coin.config.CommonConfig;
import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.p2p.peer.Peer;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolEnum;
import com.bit.coin.p2p.protocol.ProtocolHandler;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.bit.coin.database.rocksDb.RocksDb.bytesToInt;
import static com.bit.coin.p2p.protocol.P2PMessage.newResponseMessage;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;

@Slf4j
@Component
public class P2PFindNodeReqHandle implements ProtocolHandler.ResultProtocolHandler{
    @Autowired
    private RoutingTable routingTable;

    @Override
    public byte[] handleResult(P2PMessage requestParams) throws Exception {
        byte[] data = requestParams.getData();
        //读取前32字节
        byte[] targetId = new byte[32];
        System.arraycopy(data, 0, targetId, 0, 32);
        //读取后4字节
        int count = bytesToInt(new byte[]{data[32], data[33], data[34], data[35]});
        List<Peer> closestPeers = routingTable.findClosestPeers(targetId, count);
        log.info("返回最近的节点信息{}",closestPeers.size());
        byte[] bytes = routingTable.serializePeers(closestPeers);
        P2PMessage p2PMessage = newResponseMessage(CommonConfig.getSelf().getId(), ProtocolEnum.P2P_Find_Node_Req,requestParams.getRequestId(), bytes);
        byte[] serialize = p2PMessage.serialize();

        log.info("序列化后{}",serialize.length);

        return serialize;
    }
}
