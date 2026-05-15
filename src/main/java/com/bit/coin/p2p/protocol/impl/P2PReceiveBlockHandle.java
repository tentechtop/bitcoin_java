package com.bit.coin.p2p.protocol.impl;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.net.NetService;
import com.bit.coin.net.SyncProgress;
import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolHandler;
import com.bit.coin.structure.block.Block;
import com.bit.coin.structure.block.BlockHeader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;


import static com.bit.coin.p2p.conn.QuicConstants.EXIST_RESOURCE_CACHE;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;

@Slf4j
@Component
public class P2PReceiveBlockHandle implements ProtocolHandler.VoidProtocolHandler{

    private static final long AUTO_SYNC_DEBOUNCE_MILLIS = 5_000L;

    @Autowired
    private BlockChainServiceImpl blockChainService;

    @Autowired
    private RoutingTable routingTable;

    @Autowired
    private NetService netService;

    private final AtomicLong lastAutoSyncAtMillis = new AtomicLong(0L);

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
            boolean missingParent = blockChainService.isMissingParentBlock(deserialize);
            if (ifPresent==null){
                BlockHeader blockHeaderByHash = blockChainService.getBlockHeaderByHash(deserialize.getHash());
                if (blockHeaderByHash==null){
                    blockChainService.verifyBlock(deserialize);
                }
            }
            //更新节点高度 和 hash
            routingTable.updateLatest(bytesToHex(senderId),bytesToHex(deserialize.getHash()),deserialize.getHeight(),deserialize.getChainWork());
            triggerHeadersFirstSyncIfNeeded(deserialize, missingParent);
        }
        //将这个资源加入到已经处理 下次再遇到直接忽略
    }

    private void triggerHeadersFirstSyncIfNeeded(Block block, boolean missingParent) {
        if (block == null) {
            return;
        }

        int localHeight = netService.getCurrentLocalHeight();
        boolean remoteAhead = block.getHeight() > localHeight;
        if (!missingParent && !remoteAhead) {
            return;
        }

        long now = System.currentTimeMillis();
        long last = lastAutoSyncAtMillis.get();
        if (now - last < AUTO_SYNC_DEBOUNCE_MILLIS) {
            return;
        }
        if (!lastAutoSyncAtMillis.compareAndSet(last, now)) {
            return;
        }

        SyncProgress progress = netService.startSyncBlockHeadersFirst();
        log.info("触发headers-first区块同步: missingParent={}, remoteHeight={}, localHeight={}, status={}, message={}",
                missingParent, block.getHeight(), localHeight, progress.status(), progress.message());
    }
}
