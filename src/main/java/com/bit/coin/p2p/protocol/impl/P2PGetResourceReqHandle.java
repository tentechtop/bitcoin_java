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
public class P2PGetResourceReqHandle implements ProtocolHandler.VoidProtocolHandler {

    @Autowired
    private BlockChainServiceImpl blockChainService;

    @Override
    public void handleVoid(P2PMessage requestParams) throws IOException {
        log.info("Received resource request");
        byte[] data = requestParams.getData();
        byte[] senderId = requestParams.getSenderId();
        BroadcastResource resource = BroadcastResource.fromBytes(data);
        if (resource == null || resource.getHash() == null || senderId == null) {
            return;
        }

        switch (resource.getType()) {
            case BroadcastResource.TYPE_BLOCK -> sendBlock(senderId, resource);
            case BroadcastResource.TYPE_TRANSACTION -> sendTransaction(senderId, resource);
            default -> log.info("Received unknown resource request: type={}", resource.getType());
        }
    }

    private void sendBlock(byte[] senderId, BroadcastResource resource) throws IOException {
        log.info("Send block by hash: {}", bytesToHex(resource.getHash()));
        Block block = blockChainService.getBlockByHash(resource.getHash());
        if (block == null) {
            log.info("Requested block does not exist: {}", bytesToHex(resource.getHash()));
            return;
        }
        staticSendData(bytesToHex(senderId), ProtocolEnum.P2P_Receive_Block, block.serialize(), 5000);
    }

    private void sendTransaction(byte[] senderId, BroadcastResource resource) throws IOException {
        log.info("Send transaction by hash: {}", bytesToHex(resource.getHash()));
        Transaction tx = blockChainService.getTxByHash(resource.getHash());
        if (tx == null) {
            log.info("Requested transaction does not exist: {}", bytesToHex(resource.getHash()));
            return;
        }
        staticSendData(bytesToHex(senderId), ProtocolEnum.P2P_Receive_Transaction, tx.serialize(), 5000);
    }
}
