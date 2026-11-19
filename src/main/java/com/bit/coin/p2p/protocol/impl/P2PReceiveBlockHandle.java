package com.bit.coin.p2p.protocol.impl;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.net.SmartSyncTrigger;
import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolHandler;
import com.bit.coin.structure.block.Block;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;

@Slf4j
@Component
public class P2PReceiveBlockHandle implements ProtocolHandler.VoidProtocolHandler{

    @Autowired
    private BlockChainServiceImpl blockChainService;

    @Autowired
    private RoutingTable routingTable;

    @Autowired
    private SmartSyncTrigger smartSyncTrigger;

    @Override
    public void handleVoid(P2PMessage requestParams) throws IOException {
        Block block = Block.deserialize(requestParams.getData());
        byte[] senderId = requestParams.getSenderId();
        log.info("从远程节点接收到区块信息{}", block);

        byte[] hash = block.getHash();
        boolean missingParent = blockChainService.isMissingParentBlock(block);
        boolean verified = blockChainService.hasBlockBody(hash) || blockChainService.verifyBlock(block);
        if (senderId == null) return;
        if (verified) {
            Block stored = blockChainService.getHeaderContext(hash);
            routingTable.updateLatest(bytesToHex(senderId), bytesToHex(hash), stored.getHeight(), stored.getChainWork());
        } else if (missingParent && block.getHeight() > 0 && block.getBlockHeader().verifyProofOfWork(false)) {
            routingTable.updateLatest(bytesToHex(senderId), bytesToHex(hash), block.getHeight(), block.getChainWork());
            smartSyncTrigger.triggerIfRemoteAhead(block.getChainWork(), "block-with-missing-parent");
        }
    }
}
