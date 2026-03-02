package com.bit.coin.p2p.protocol.impl;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.config.CommonConfig;
import com.bit.coin.database.rocksDb.RocksDb;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolEnum;
import com.bit.coin.p2p.protocol.ProtocolHandler;
import com.bit.coin.structure.block.Block;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static com.bit.coin.p2p.protocol.P2PMessage.newResponseMessage;

@Slf4j
@Component
public class P2PQueryBlockByHeightHandle implements ProtocolHandler.ResultProtocolHandler{

    @Autowired
    private BlockChainServiceImpl blockChainService;

    @Override
    public byte[] handleResult(P2PMessage requestParams) throws Exception {
        byte[] data = requestParams.getData();//4字节height
        //发送者
        byte[] senderId = requestParams.getSenderId();
        Block blockByHash = blockChainService.getBlockByHeight(RocksDb.bytesToInt(data));
        log.info("根据高度查询区块请求 {}",blockByHash.toString());
        byte[] serialize = blockByHash.serialize();
        P2PMessage p2PMessage = newResponseMessage(CommonConfig.getSelf().getId(), ProtocolEnum.P2P_Query_Block_By_Height,requestParams.getRequestId(), serialize);
        return p2PMessage.serialize();
    }
}
