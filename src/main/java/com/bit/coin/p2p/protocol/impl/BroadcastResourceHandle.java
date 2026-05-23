package com.bit.coin.p2p.protocol.impl;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.net.SmartSyncTrigger;
import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolEnum;
import com.bit.coin.p2p.protocol.ProtocolHandler;
import com.bit.coin.p2p.protocol.dto.BroadcastResource;
import com.bit.coin.structure.block.BlockHeader;
import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.txpool.TxPool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

import static com.bit.coin.p2p.conn.QuicConnectionManager.staticSendData;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;

@Slf4j
@Component
public class BroadcastResourceHandle implements ProtocolHandler.VoidProtocolHandler {

    private final BlockChainServiceImpl blockChainService;
    private final RoutingTable routingTable;
    private final TxPool txPool;
    private final SmartSyncTrigger smartSyncTrigger;

    public BroadcastResourceHandle(
            BlockChainServiceImpl blockChainService,
            RoutingTable routingTable,
            TxPool txPool,
            SmartSyncTrigger smartSyncTrigger
    ) {
        this.blockChainService = blockChainService;
        this.routingTable = routingTable;
        this.txPool = txPool;
        this.smartSyncTrigger = smartSyncTrigger;
    }

    @Override
    public void handleVoid(P2PMessage requestParams) throws IOException {
        byte[] data = requestParams.getData();
        byte[] senderId = requestParams.getSenderId();
        BroadcastResource resource = BroadcastResource.fromBytes(data);
        if (resource == null || resource.getHash() == null) {
            return;
        }

        boolean needResource = switch (resource.getType()) {
            case BroadcastResource.TYPE_BLOCK -> shouldRequestBlock(senderId, resource);
            case BroadcastResource.TYPE_TRANSACTION -> shouldRequestTransaction(resource);
            default -> {
                log.info("Received unknown resource announcement: type={}", resource.getType());
                yield false;
            }
        };

        if (needResource && senderId != null) {
            log.info("Request announced resource: type={}, hash={}", resource.getType(), bytesToHex(resource.getHash()));
            staticSendData(bytesToHex(senderId), ProtocolEnum.P2P_Get_Resource_Req, data, 5000);
        }
    }

    private boolean shouldRequestBlock(byte[] senderId, BroadcastResource resource) {
        byte[] hash = resource.getHash();
        log.info("Received block announcement: hash={}, height={}", bytesToHex(hash), resource.getHeight());

        if (resource.hasBlockState() && senderId != null) {
            routingTable.updateLatest(
                    bytesToHex(senderId),
                    bytesToHex(hash),
                    resource.getHeight(),
                    resource.getChainWork()
            );
        }

        BlockHeader header = blockChainService.getBlockHeaderByHash(hash);
        if (header != null && blockChainService.hasBlockBody(hash)) {
            log.debug("Skip block request because it already exists: {}", bytesToHex(hash));
            return false;
        }

        if (!resource.hasBlockState()) {
            return true;
        }

        int localHeight = smartSyncTrigger.getLocalHeight();
        if (resource.getHeight() > localHeight + 1) {
            smartSyncTrigger.triggerIfRemoteAhead(resource.getHeight(), "block-announcement");
            return false;
        }

        if (resource.getHeight() > localHeight) {
            smartSyncTrigger.triggerIfRemoteAhead(resource.getHeight(), "block-announcement");
        }
        return true;
    }

    private boolean shouldRequestTransaction(BroadcastResource resource) {
        byte[] hash = resource.getHash();
        log.info("Received transaction announcement: hash={}", bytesToHex(hash));

        Transaction poolTx = txPool.getTx(hash);
        if (poolTx != null) {
            return false;
        }

        Transaction chainTx = blockChainService.getTxByHash(hash);
        return chainTx == null;
    }
}
