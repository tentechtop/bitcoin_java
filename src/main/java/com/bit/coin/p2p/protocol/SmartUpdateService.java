package com.bit.coin.p2p.protocol;

import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.blockchain.ChainTipSnapshot;
import com.bit.coin.p2p.peer.Peer;
import com.bit.coin.p2p.protocol.message.NodeStatusPayload;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.bit.coin.p2p.conn.QuicConnectionManager.staticSendData;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;

@Slf4j
@Component
public class SmartUpdateService {

    private static final long INITIAL_DELAY_SECONDS = 5L;
    private static final long TIP_SCAN_INTERVAL_SECONDS = 2L;  // 两次检查本地链顶之间的间隔，单位为秒

    private final RoutingTable routingTable;
    @org.springframework.beans.factory.annotation.Autowired
    private BlockChainServiceImpl blockChainService;
    @org.springframework.beans.factory.annotation.Autowired
    private com.bit.coin.config.SystemConfig systemConfig;
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
        if (!systemConfig.isNetworkingEnabled()) {
            return;
        }
        executor.scheduleWithFixedDelay(
                this::announceTipChangeSafely,
                INITIAL_DELAY_SECONDS,
                TIP_SCAN_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    public void announceCurrentStateToPeer(String peerId, int reason) {
        ChainTipSnapshot tip = blockChainService.getMainTipSnapshot();
        if (tip == null || peerId == null || peerId.isBlank()) {
            return;
        }
        byte[] payload = NodeStatusPayload.fromTip(tip, reason).serialize();
        staticSendData(peerId, ProtocolEnum.P2P_Node_Status, payload, 3_000);
    }

    public void announceCurrentState(int reason) {
        ChainTipSnapshot tip = blockChainService.getMainTipSnapshot();
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
        log.debug("Announced node state: reason={}, height={}, peers={}", reason, tip.height(), peers.size());
    }

    /** 高度或哈希有变化才广播，因而同高度的主链切换也会触发通告。 */
    private void announceTipChangeSafely() {
        try {
            ChainTipSnapshot tip = blockChainService.getMainTipSnapshot();
            if (tip == null || tip.hash() == null) {
                return;
            }
            if (tip.height() == lastAnnouncedHeight && Arrays.equals(tip.hash(), lastAnnouncedTipHash)) {
                return;
            }

            lastAnnouncedHeight = tip.height();
            lastAnnouncedTipHash = tip.hash();
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
