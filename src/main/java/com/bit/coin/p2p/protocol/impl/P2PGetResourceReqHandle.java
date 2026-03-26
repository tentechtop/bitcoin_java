package com.bit.coin.p2p.protocol.impl;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolEnum;
import com.bit.coin.p2p.protocol.ProtocolHandler;
import com.bit.coin.p2p.protocol.dto.BroadcastResource;
import com.bit.coin.structure.block.Block;
import com.bit.coin.structure.tx.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

import static com.bit.coin.p2p.conn.QuicConnectionManager.staticSendData;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;

@Slf4j
@Component
public class P2PGetResourceReqHandle implements ProtocolHandler.VoidProtocolHandler{



    @Autowired
    private BlockChainServiceImpl blockChainService;

    @Override
    public void handleVoid(P2PMessage requestParams) throws IOException {
        log.info("收到资源请求");
        byte[] data = requestParams.getData();
        byte[] senderId = requestParams.getSenderId();
        BroadcastResource broadcastResource = BroadcastResource.fromBytes(data);

        if (broadcastResource!=null){
            int type = broadcastResource.getType();
            //如果是0 则是区块 如果是1 则是交易 用switch
            //是否需要这个资源
            switch (type){
                case 0:
                    log.info("将区块发送 hash:{}",bytesToHex(broadcastResource.getHash()));
                    //如果不存在就回复该节点
                    //将区块发送给目标节点
                    Block blockByHash = blockChainService.getBlockByHash(broadcastResource.getHash());
                    byte[] serialize = blockByHash.serialize();
                    staticSendData(bytesToHex(senderId),ProtocolEnum.P2P_Receive_Block,serialize,5000);
                    break;
                case 1:
                    log.info("将交易发送 hash:{}",broadcastResource.getHash());
                    Transaction txByHash = blockChainService.getTxByHash(broadcastResource.getHash());
                    byte[] txSerialize = txByHash.serialize();
                    staticSendData(bytesToHex(senderId),ProtocolEnum.P2P_Receive_Transaction,txSerialize,5000);
                    break;
                default:
                    log.info("收到未知资源广播");
            }
        }
    }
}
