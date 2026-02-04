package com.bit.coin.p2p.protocol.impl;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolHandler;
import com.bit.coin.structure.block.Block;
import com.bit.coin.structure.tx.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;

@Slf4j
@Component
public class P2PReceiveBlockHandle implements ProtocolHandler.VoidProtocolHandler{

    @Autowired
    private BlockChainServiceImpl blockChainService;

    @Autowired
    private RoutingTable routingTable;

    @Override
    public void handleVoid(P2PMessage requestParams) throws IOException {
        byte[] data = requestParams.getData();
        byte[] senderId = requestParams.getSenderId();
        Block deserialize = Block.deserialize(data);
        if (deserialize!=null){
            log.info("区块信息{}",deserialize.toString());
            blockChainService.verifyBlock(deserialize);
            //更新节点高度 和 hash
            routingTable.updateLatest(bytesToHex(senderId),bytesToHex(deserialize.getHash()),deserialize.getHeight(),deserialize.getChainWork());
        }
    }
}
