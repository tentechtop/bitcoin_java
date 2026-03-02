package com.bit.coin.p2p.protocol.impl;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.config.CommonConfig;
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
public class P2PQueryBlockByHashHandle implements ProtocolHandler.ResultProtocolHandler{

    @Autowired
    private BlockChainServiceImpl blockChainService;

    @Override
    public byte[] handleResult(P2PMessage requestParams) throws Exception {
        byte[] data = requestParams.getData();
        //发送者
        byte[] senderId = requestParams.getSenderId();
        Block blockByHash = blockChainService.getBlockByHash(data);
        P2PMessage p2PMessage = newResponseMessage(CommonConfig.getSelf().getId(), ProtocolEnum.P2P_Query_Block_By_Hash,requestParams.getRequestId(), blockByHash.serialize());
        return p2PMessage.serialize();
    }
}
