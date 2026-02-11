package com.bit.coin.p2p.protocol.impl;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolHandler;
import com.bit.coin.structure.block.Block;
import com.bit.coin.structure.block.BlockHeader;
import com.bit.coin.structure.tx.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

import static com.bit.coin.config.CommonConfig.EXIST_RESOURCE_CACHE;
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
        //是否已经存在
        if (deserialize!=null){
            log.info("从远程节点接收到区块信息{}",deserialize.toString());
            //是否已经存在
            Integer ifPresent = EXIST_RESOURCE_CACHE.getIfPresent(bytesToHex(deserialize.getHash()));
            if (ifPresent==null){
                BlockHeader blockHeaderByHash = blockChainService.getBlockHeaderByHash(deserialize.getHash());
                if (blockHeaderByHash==null){
                    blockChainService.verifyBlock(deserialize);
                }
            }
            //更新节点高度 和 hash
            routingTable.updateLatest(bytesToHex(senderId),bytesToHex(deserialize.getHash()),deserialize.getHeight(),deserialize.getChainWork());
        }
        //将这个资源加入到已经处理 下次再遇到直接忽略
    }
}
