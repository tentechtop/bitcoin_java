package com.bit.coin.p2p.production;

import com.bit.coin.config.SystemConfig;
import com.bit.coin.p2p.peer.Peer;
import com.bit.coin.p2p.conn.QuicConnectionStats;
import org.bitcoinj.core.Base58;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionP2PServiceTest {
    @BeforeEach
    @AfterEach
    void clearSelfPeer() {
        SystemConfig.SelfPeer = null;
    }

    @Test
    void connectsAndDisconnectsThroughTransportGateway() {
        FakeGateway gateway = new FakeGateway();
        ProductionP2PService service = service(gateway, new P2PBlacklist(), new P2PTransferErrorTracker(20));

        P2PConnectResult connect = service.connect(address());

        assertTrue(connect.success());
        assertEquals(P2PNodeStatus.CONNECTED, connect.status());
        assertEquals(peerHex(), connect.peerId());
        assertEquals(1, gateway.connectAttempts);

        P2PNodeSnapshot connected = service.nodeSnapshot(peerHex()).orElseThrow();
        assertEquals(P2PNodeStatus.CONNECTED, connected.status());
        assertEquals("127.0.0.1:8334", connected.remoteAddress());
        assertTrue(connected.outbound());

        P2PDisconnectResult disconnect = service.disconnect(peerHex().toUpperCase());

        assertTrue(disconnect.success());
        assertEquals(P2PNodeStatus.DISCONNECTED, disconnect.status());
        assertEquals(List.of(peerHex()), gateway.disconnectedPeers);
        assertEquals(P2PNodeStatus.DISCONNECTED, service.nodeSnapshot(peerHex()).orElseThrow().status());
    }

    @Test
    void blocksBlacklistedAddressesBeforeTransportConnect() {
        FakeGateway gateway = new FakeGateway();
        P2PBlacklist blacklist = new P2PBlacklist();
        P2PTransferErrorTracker tracker = new P2PTransferErrorTracker(20);
        ProductionP2PService service = service(gateway, blacklist, tracker);
        blacklist.blockIp("127.0.0.1", "maintenance", Duration.ZERO);

        P2PConnectResult result = service.connect(address());

        assertFalse(result.success());
        assertEquals(P2PNodeStatus.BLACKLISTED, result.status());
        assertEquals(0, gateway.connectAttempts);
        assertEquals(P2PErrorCode.BLACKLISTED, tracker.recent(peerHex()).getFirst().errorCode());
        assertTrue(service.nodeSnapshot(peerHex()).orElseThrow().blacklisted());
    }

    @Test
    void rejectsTransportPeerIdMismatch() {
        FakeGateway gateway = new FakeGateway();
        gateway.peerIdOverride = "f".repeat(64);
        P2PTransferErrorTracker tracker = new P2PTransferErrorTracker(20);
        ProductionP2PService service = service(gateway, new P2PBlacklist(), tracker);

        P2PConnectResult result = service.connect(address());

        assertFalse(result.success());
        assertEquals(P2PNodeStatus.FAILED, result.status());
        assertEquals(List.of(gateway.peerIdOverride), gateway.disconnectedPeers);
        assertEquals(P2PErrorCode.CONNECT_FAILED, tracker.recent(peerHex()).getFirst().errorCode());
        assertEquals("transport peer id mismatch", tracker.recent(peerHex()).getFirst().message());
    }

    @Test
    void rejectsSelfAddressBeforeTransportConnect() {
        FakeGateway gateway = new FakeGateway();
        P2PTransferErrorTracker tracker = new P2PTransferErrorTracker(20);
        ProductionP2PService service = service(gateway, new P2PBlacklist(), tracker);
        SystemConfig.SelfPeer = selfPeer(8334);

        P2PConnectResult result = service.connect(address());

        assertFalse(result.success());
        assertEquals(P2PNodeStatus.FAILED, result.status());
        assertEquals(peerHex(), result.peerId());
        assertEquals(0, gateway.connectAttempts);
        assertTrue(service.nodeSnapshots().isEmpty());
        assertEquals(P2PErrorCode.INVALID_ADDRESS, tracker.recent(peerHex()).getFirst().errorCode());
    }

    @Test
    void hidesAndDisconnectsSelfTransportStats() {
        FakeGateway gateway = new FakeGateway();
        ProductionP2PService service = service(gateway, new P2PBlacklist(), new P2PTransferErrorTracker(20));
        SystemConfig.SelfPeer = selfPeer(8334);
        gateway.stats = List.of(new QuicConnectionStats(
                peerHex(),
                200,
                "/127.0.0.1:8334",
                false,
                false,
                123,
                10,
                2_048,
                4_096,
                512.5,
                1_024.5,
                23,
                16_000,
                8_000,
                14_000,
                64_000,
                0.0,
                0.5,
                700
        ));

        List<P2PNodeSnapshot> snapshots = service.nodeSnapshots();

        assertTrue(snapshots.isEmpty());
        assertEquals(List.of(peerHex()), gateway.disconnectedPeers);
    }

    @Test
    void clearsBlacklistedNodeStateWhenPeerIsUnblocked() {
        FakeGateway gateway = new FakeGateway();
        P2PBlacklist blacklist = new P2PBlacklist();
        ProductionP2PService service = service(gateway, blacklist, new P2PTransferErrorTracker(20));
        service.connect(address());

        service.blacklistPeer(peerHex(), "manual", Duration.ZERO);
        assertEquals(P2PNodeStatus.BLACKLISTED, service.nodeSnapshot(peerHex()).orElseThrow().status());

        assertTrue(service.unblockPeer(peerHex()));
        assertEquals(P2PNodeStatus.DISCONNECTED, service.nodeSnapshot(peerHex()).orElseThrow().status());
    }

    @Test
    void recordsConnectFailuresAndAutoBlacklistsRepeatedFailures() {
        FakeGateway gateway = new FakeGateway();
        gateway.failConnect = true;
        P2PBlacklist blacklist = new P2PBlacklist();
        P2PTransferErrorTracker tracker = new P2PTransferErrorTracker(20);
        ProductionP2PService service = service(gateway, blacklist, tracker);

        for (int i = 0; i < 5; i++) {
            P2PConnectResult result = service.connect(address());
            assertFalse(result.success());
        }

        assertEquals(5, gateway.connectAttempts);
        assertEquals(5, tracker.recent(peerHex()).size());
        assertTrue(blacklist.findPeerHex(peerHex()).isPresent());
        assertEquals(P2PNodeStatus.BLACKLISTED, service.nodeSnapshot(peerHex()).orElseThrow().status());

        P2PConnectResult blocked = service.connect(address());
        assertFalse(blocked.success());
        assertEquals(P2PNodeStatus.BLACKLISTED, blocked.status());
        assertEquals(5, gateway.connectAttempts);
    }

    @Test
    void refreshesTransportStatsAndTransferSpeeds() {
        FakeGateway gateway = new FakeGateway();
        ProductionP2PService service = service(gateway, new P2PBlacklist(), new P2PTransferErrorTracker(20));
        service.connect(address());
        gateway.stats = List.of(new QuicConnectionStats(
                peerHex(),
                200,
                "/127.0.0.1:8334",
                true,
                false,
                123,
                10,
                2_048,
                4_096,
                512.5,
                1_024.5,
                23,
                16_000,
                8_000,
                14_000,
                64_000,
                0.0,
                0.5,
                700
        ));

        P2PNodeSnapshot snapshot = service.nodeSnapshot(peerHex()).orElseThrow();

        assertEquals(P2PNodeStatus.CONNECTED, snapshot.status());
        assertEquals(2_048, snapshot.sentBytes());
        assertEquals(4_096, snapshot.receivedBytes());
        assertTrue(snapshot.sendBytesPerSecond() >= 512.5);
        assertTrue(snapshot.receiveBytesPerSecond() >= 1_024.5);
        assertEquals(16_000, snapshot.flowControl().remoteReceiveWindowBytes());
        assertEquals(8_000, snapshot.flowControl().localReceiveWindowBytes());
        assertEquals(700, snapshot.flowControl().congestion().inflightBytes());
    }

    @Test
    void recordsOutboundFlowControlRejections() {
        P2PTransferErrorTracker tracker = new P2PTransferErrorTracker(20);
        ProductionP2PService service = service(new FakeGateway(), new P2PBlacklist(), tracker);

        boolean reserved = service.reserveOutboundBytes(peerHex(), Long.MAX_VALUE);

        assertFalse(reserved);
        assertEquals(1, tracker.recent(peerHex()).size());
        assertEquals(P2PErrorCode.FLOW_CONTROL_REJECTED, tracker.recent(peerHex()).getFirst().errorCode());
    }

    private static ProductionP2PService service(FakeGateway gateway,
                                                P2PBlacklist blacklist,
                                                P2PTransferErrorTracker tracker) {
        return new ProductionP2PService(gateway, blacklist, tracker);
    }

    private static String address() {
        return "/ip4/127.0.0.1/quic/8334/p2p/" + Base58.encode(peerBytes());
    }

    private static String peerHex() {
        return HexFormat.of().formatHex(peerBytes());
    }

    private static byte[] peerBytes() {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i + 1);
        }
        return bytes;
    }

    private static Peer selfPeer(int port) {
        Peer peer = new Peer();
        peer.setId(peerBytes());
        peer.setAddress("127.0.0.1");
        peer.setPort(port);
        return peer;
    }

    private static final class FakeGateway implements P2PTransportGateway {
        private int connectAttempts;
        private boolean failConnect;
        private boolean disconnectSucceeds = true;
        private String peerIdOverride;
        private long nextConnectionId = 100;
        private List<String> disconnectedPeers = new ArrayList<>();
        private List<QuicConnectionStats> stats = List.of();

        @Override
        public Optional<P2PTransportConnection> connect(P2PAddress address) {
            connectAttempts++;
            if (failConnect) {
                return Optional.empty();
            }
            String peerId = peerIdOverride == null ? address.peerIdHex() : peerIdOverride;
            return Optional.of(new P2PTransportConnection(
                    peerId,
                    nextConnectionId++,
                    address.endpointKey(),
                    true
            ));
        }

        @Override
        public boolean disconnect(String peerIdHex) {
            disconnectedPeers.add(peerIdHex);
            return disconnectSucceeds;
        }

        @Override
        public List<QuicConnectionStats> connectionStats() {
            return stats;
        }
    }
}
