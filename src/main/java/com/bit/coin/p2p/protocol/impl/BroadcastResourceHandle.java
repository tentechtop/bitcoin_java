package com.bit.coin.p2p.protocol.impl;


import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.config.CommonConfig;
import com.bit.coin.p2p.impl.PeerClient;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolEnum;
import com.bit.coin.p2p.protocol.ProtocolHandler;
import com.bit.coin.p2p.protocol.dto.BroadcastResource;
import com.bit.coin.structure.block.BlockHeader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;


import static com.bit.coin.utils.SerializeUtils.bytesToHex;

@Slf4j
@Component
public class BroadcastResourceHandle implements ProtocolHandler.VoidProtocolHandler{

    @Autowired
    private PeerClient peerClient;

    @Autowired
    private BlockChainServiceImpl blockChainService;


    @Override
    public void handleVoid(P2PMessage requestParams) throws IOException {
        byte[] data = requestParams.getData();
        byte[] senderId = requestParams.getSenderId();
        BroadcastResource broadcastResource = BroadcastResource.fromBytes(data);
        if (broadcastResource!=null){
            int type = broadcastResource.getType();
            //如果是0 则是区块 如果是1 则是交易 用switch
            //是否需要这个资源
            boolean need = false;
            switch (type){
                case 0:
                    log.info("收到区块广播");
                    log.info("区块hash:{}",bytesToHex(broadcastResource.getHash()));
                    //如果不存在就回复该节点
                    BlockHeader blockHeaderByHash = blockChainService.getBlockHeaderByHash(broadcastResource.getHash());
                    if (blockHeaderByHash==null){
                        need=true;
                    }
                    break;
                case 1:
                    log.info("收到交易广播");
                    log.info("交易hash:{}",bytesToHex(broadcastResource.getHash()));
                    need=true;
                    break;
                default:
                    log.info("收到未知资源广播");
            }
            //如果需要这个资源就发送 对该资源的请求
            if (need){
                log.info("需要这个资源 {} ",data.length);
                peerClient.sendData(bytesToHex(senderId),ProtocolEnum.P2P_Get_Resource_Req,data,5000);
            }else {
                log.info("不需要这个资源");
            }
        }
    }
}
