package com.bit.coin.p2p.protocol;

import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.p2p.peer.Peer;
import com.bit.coin.p2p.protocol.message.NodeStatusPayload;
import com.bit.coin.structure.block.Block;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.bit.coin.blockchain.BlockChainServiceImpl.mainTipBlock;
import static com.bit.coin.p2p.conn.QuicConnectionManager.staticSendData;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;

@Slf4j
@Component
public class SmartUpdateService {
    private static final long INITIAL_DELAY_SECONDS = 5L;
    private static final long TIP_SCAN_INTERVAL_SECONDS = 2L;

    private final RoutingTable routingTable;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "smart-update-announcer");
        thread.setDaemon(true);
        return thread;
    });

    private volatile byte[] lastAnnouncedTipHash = new byte[0];
    private volatile int lastAnnouncedHeight = -1;

    public SmartUpdateService(RoutingTable routingTable) {
        this.routingTable = routingTable;
    }

    @PostConstruct
    public void start() {
        executor.scheduleWithFixedDelay(
                this::announceTipChangeSafely,
                INITIAL_DELAY_SECONDS,
                TIP_SCAN_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    public void announceCurrentStateToPeer(String peerId, int reason) {
        Block tip = mainTipBlock;
        if (tip == null || peerId == null || peerId.isBlank()) {
            return;
        }
        byte[] payload = NodeStatusPayload.fromTip(tip, reason).serialize();
        staticSendData(peerId, ProtocolEnum.P2P_Node_Status, payload, 3_000);
    }

    public void announceCurrentState(int reason) {
        Block tip = mainTipBlock;
        if (tip == null) {
            return;
        }
        byte[] payload = NodeStatusPayload.fromTip(tip, reason).serialize();
        List<Peer> peers = routingTable.getOnlineNodes();
        for (Peer peer : peers) {
            if (peer == null || peer.getId() == null) {
                continue;
            }
            staticSendData(bytesToHex(peer.getId()), ProtocolEnum.P2P_Node_Status, payload, 3_000);
        }
        log.debug("Announced node state: reason={}, height={}, peers={}", reason, tip.getHeight(), peers.size());
    }

    private void announceTipChangeSafely() {
        try {
            Block tip = mainTipBlock;
            if (tip == null || tip.getHash() == null) {
                return;
            }
            if (tip.getHeight() == lastAnnouncedHeight && Arrays.equals(tip.getHash(), lastAnnouncedTipHash)) {
                return;
            }

            lastAnnouncedHeight = tip.getHeight();
            lastAnnouncedTipHash = tip.getHash().clone();
            announceCurrentState(NodeStatusPayload.REASON_TIP_CHANGED);
        } catch (Exception e) {
            log.debug("Smart update announcement failed: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
    }
}
