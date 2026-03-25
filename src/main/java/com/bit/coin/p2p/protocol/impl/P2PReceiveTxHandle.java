package com.bit.coin.p2p.protocol.impl;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolHandler;
import com.bit.coin.structure.block.Block;
import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.txpool.TxPool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;


import static com.bit.coin.p2p.conn.QuicConstants.EXIST_RESOURCE_CACHE;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;

@Slf4j
@Component
public class P2PReceiveTxHandle implements ProtocolHandler.VoidProtocolHandler{

    @Autowired
    private TxPool txPool;

    @Autowired
    private BlockChainServiceImpl blockChainService;

    @Override
    public void handleVoid(P2PMessage requestParams) throws IOException {
        byte[] data = requestParams.getData();
        byte[] senderId = requestParams.getSenderId();
        Transaction deserialize = Transaction.deserialize(data);
        if (deserialize!=null){
            log.info("收到 交易信息{}", deserialize.toString());
            //是否已经存在
            Integer ifPresent = EXIST_RESOURCE_CACHE.getIfPresent(bytesToHex(deserialize.getTxId()));
            if (ifPresent==null){
                Transaction txByHash = blockChainService.getTxByHash(deserialize.getTxId());
                if (txByHash==null){
                    txPool.addTx(deserialize);
                }
            }
        }
    }
}
