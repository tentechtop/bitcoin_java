package com.bit.coin.p2p.protocol.impl;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.blockchain.Landmark;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolEnum;
import com.bit.coin.p2p.protocol.ProtocolHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.bit.coin.config.SystemConfig.SelfPeer;
import static com.bit.coin.p2p.protocol.P2PMessage.newResponseMessage;

@Slf4j
@Component
public class P2PQueryCommonAncestorHandle implements ProtocolHandler.ResultProtocolHandler{

    @Autowired
    private BlockChainServiceImpl blockChainService;

    @Override
    public byte[] handleResult(P2PMessage requestParams) throws Exception {
        byte[] data = requestParams.getData();
        //解析为List<Landmark>
        List<Landmark> landmarks = Landmark.deserializeList(data);//远程路标
        Landmark commonAncestorByLandmark = blockChainService.findCommonAncestorByLandmark(landmarks);
        P2PMessage p2PMessage = newResponseMessage(SelfPeer.getId(), ProtocolEnum.P2P_Query_Block_By_Height,requestParams.getRequestId(), commonAncestorByLandmark.serialize());
        return p2PMessage.serialize();
    }
}
