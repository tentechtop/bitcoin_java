package com.bit.coin.p2p.production;

import com.bit.coin.p2p.conn.QuicConnectionStats;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ProductionP2PService {
    private static final int AUTO_BLACKLIST_THRESHOLD = 5;
    private static final Duration AUTO_BLACKLIST_TTL = Duration.ofMinutes(10);
    private static final Duration THROUGHPUT_WINDOW = Duration.ofSeconds(10);

    private final P2PTransportGateway transportGateway;
    private final P2PBlacklist blacklist;
    private final P2PTransferErrorTracker errorTracker;
    private final ConcurrentHashMap<String, NodeState> nodes = new ConcurrentHashMap<>();

    public ProductionP2PService(P2PTransportGateway transportGateway,
                                P2PBlacklist blacklist,
                                P2PTransferErrorTracker errorTracker) {
        this.transportGateway = transportGateway;
        this.blacklist = blacklist;
        this.errorTracker = errorTracker;
    }

    public P2PConnectResult connect(String rawAddress) {
        P2PAddress address;
        try {
            address = P2PAddressValidator.parseQuicAddress(rawAddress);
        } catch (RuntimeException e) {
            errorTracker.record(P2PTransferErrorRecord.now(
                    "",
                    "",
                    P2PErrorCode.INVALID_ADDRESS,
                    e.getMessage(),
                    0,
                    0,
                    e
            ));
            return P2PConnectResult.failure(P2PNodeStatus.FAILED, "", e.getMessage());
        }

        if (P2PSelfNode.isSelfAddress(address)) {
            String message = "cannot connect to self node: " + address.endpointKey();
            nodes.remove(address.peerIdHex());
            errorTracker.record(P2PTransferErrorRecord.now(
                    address.peerIdHex(),
                    address.endpointKey(),
                    P2PErrorCode.INVALID_ADDRESS,
                    message,
                    0,
                    0,
                    null
            ));
            return P2PConnectResult.failure(P2PNodeStatus.FAILED, address.peerIdHex(), message);
        }

        NodeState state = nodes.computeIfAbsent(address.peerIdHex(), ignored -> new NodeState(address));
        state.updateAddress(address);

        Optional<P2PBlacklistEntry> blacklistEntry = blacklist.find(address);
        if (blacklistEntry.isPresent()) {
            state.status = P2PNodeStatus.BLACKLISTED;
            state.lastErrorAtMillis = System.currentTimeMillis();
            state.lastErrorMessage = "blacklisted: " + blacklistEntry.get().reason();
            errorTracker.record(P2PTransferErrorRecord.now(
                    address.peerIdHex(),
                    address.endpointKey(),
                    P2PErrorCode.BLACKLISTED,
                    state.lastErrorMessage,
                    0,
                    0,
                    null
            ));
            return P2PConnectResult.failure(P2PNodeStatus.BLACKLISTED, address.peerIdHex(), state.lastErrorMessage);
        }

        state.status = P2PNodeStatus.CONNECTING;
        try {
            Optional<P2PTransportConnection> connection = transportGateway.connect(address);
            if (connection.isEmpty()) {
                recordFailure(state, P2PErrorCode.CONNECT_FAILED, "connect failed", null);
                return P2PConnectResult.failure(state.status, address.peerIdHex(), state.lastErrorMessage);
            }
            P2PTransportConnection transportConnection = connection.get();
            String connectedPeerId = P2PAddressValidator.normalizePeerId(transportConnection.peerId());
            if (!address.peerIdHex().equals(connectedPeerId)) {
                recordFailure(state, P2PErrorCode.CONNECT_FAILED, "transport peer id mismatch", null);
                try {
                    transportGateway.disconnect(connectedPeerId);
                } catch (RuntimeException e) {
                    errorTracker.record(P2PTransferErrorRecord.now(
                            connectedPeerId,
                            transportConnection.remoteAddress(),
                            P2PErrorCode.DISCONNECT_FAILED,
                            e.getMessage(),
                            0,
                            transportConnection.connectionId(),
                            e
                    ));
                }
                return P2PConnectResult.failure(state.status, address.peerIdHex(), state.lastErrorMessage);
            }
            state.onConnected(transportConnection);
            return P2PConnectResult.success(address.peerIdHex(), transportConnection.connectionId());
        } catch (RuntimeException e) {
            recordFailure(state, P2PErrorCode.CONNECT_FAILED, e.getMessage(), e);
            return P2PConnectResult.failure(state.status, address.peerIdHex(), state.lastErrorMessage);
        }
    }

    public P2PDisconnectResult disconnect(String peerId) {
        String peerIdHex;
        try {
            peerIdHex = P2PAddressValidator.normalizePeerId(peerId);
        } catch (RuntimeException e) {
            return new P2PDisconnectResult(false, P2PNodeStatus.FAILED, "", e.getMessage());
        }

        NodeState state = nodes.computeIfAbsent(peerIdHex, ignored -> new NodeState(peerIdHex));
        try {
            boolean success = transportGateway.disconnect(peerIdHex);
            if (success) {
                state.onDisconnected();
                return new P2PDisconnectResult(true, P2PNodeStatus.DISCONNECTED, peerIdHex, "disconnected");
            }
            recordFailure(state, P2PErrorCode.DISCONNECT_FAILED, "disconnect failed", null);
            return new P2PDisconnectResult(false, state.status, peerIdHex, state.lastErrorMessage);
        } catch (RuntimeException e) {
            recordFailure(state, P2PErrorCode.DISCONNECT_FAILED, e.getMessage(), e);
            return new P2PDisconnectResult(false, state.status, peerIdHex, state.lastErrorMessage);
        }
    }

    public P2PBlacklistEntry blacklistPeer(String peerId, String reason, Duration ttl) {
        String peerIdHex = P2PAddressValidator.normalizePeerId(peerId);
        P2PBlacklistEntry entry = blacklist.blockPeerHex(peerIdHex, reason, ttl);
        nodes.computeIfAbsent(peerIdHex, ignored -> new NodeState(peerIdHex)).status = P2PNodeStatus.BLACKLISTED;
        try {
            transportGateway.disconnect(peerIdHex);
        } catch (RuntimeException e) {
            errorTracker.record(P2PTransferErrorRecord.now(
                    peerIdHex,
                    "",
                    P2PErrorCode.DISCONNECT_FAILED,
                    e.getMessage(),
                    0,
                    0,
                    e
            ));
        }
        return entry;
    }

    public P2PBlacklistEntry blacklistIp(String ipAddress, String reason, Duration ttl) {
        return blacklist.blockIp(ipAddress, reason, ttl);
    }

    public boolean unblockPeer(String peerId) {
        String peerIdHex = P2PAddressValidator.normalizePeerId(peerId);
        boolean removed = blacklist.unblockPeer(peerIdHex);
        if (removed) {
            NodeState state = nodes.get(peerIdHex);
            if (state != null && state.status == P2PNodeStatus.BLACKLISTED) {
                state.status = P2PNodeStatus.DISCONNECTED;
            }
        }
        return removed;
    }

    public boolean unblockIp(String ipAddress) {
        boolean removed = blacklist.unblockIp(ipAddress);
        if (removed) {
            String normalizedIp = ipAddress.trim().toLowerCase(Locale.ROOT);
            for (NodeState state : nodes.values()) {
                if (normalizedIp.equals(state.ipAddress.toLowerCase(Locale.ROOT))
                        && state.status == P2PNodeStatus.BLACKLISTED
                        && blacklist.findPeerHex(state.peerId).isEmpty()) {
                    state.status = P2PNodeStatus.DISCONNECTED;
                }
            }
        }
        return removed;
    }

    public List<P2PBlacklistEntry> blacklistEntries() {
        return blacklist.entries();
    }

    public List<P2PTransferErrorRecord> transferErrors(String peerId) {
        if (peerId == null || peerId.isBlank()) {
            return errorTracker.recentAll();
        }
        return errorTracker.recent(peerId);
    }

    public List<P2PNodeSnapshot> nodeSnapshots() {
        Map<String, QuicConnectionStats> latestStats = refreshTransportStats();
        List<P2PNodeSnapshot> snapshots = new ArrayList<>();
        for (NodeState state : nodes.values()) {
            if (P2PSelfNode.isSelfPeerId(state.peerId) || P2PSelfNode.isSelfEndpoint(state.ipAddress, state.port)) {
                nodes.remove(state.peerId);
                continue;
            }
            snapshots.add(state.snapshot(latestStats.get(state.peerId)));
        }
        snapshots.sort(Comparator.comparing(P2PNodeSnapshot::peerId, Comparator.nullsLast(String::compareTo)));
        return snapshots;
    }

    public Optional<P2PNodeSnapshot> nodeSnapshot(String peerId) {
        String peerIdHex = P2PAddressValidator.normalizePeerId(peerId);
        if (P2PSelfNode.isSelfPeerId(peerIdHex)) {
            nodes.remove(peerIdHex);
            return Optional.empty();
        }
        Map<String, QuicConnectionStats> latestStats = refreshTransportStats();
        NodeState state = nodes.get(peerIdHex);
        if (state == null) {
            return Optional.empty();
        }
        if (P2PSelfNode.isSelfEndpoint(state.ipAddress, state.port)) {
            nodes.remove(peerIdHex);
            return Optional.empty();
        }
        return Optional.of(state.snapshot(latestStats.get(peerIdHex)));
    }

    public boolean reserveOutboundBytes(String peerId, long bytes) {
        NodeState state = nodes.computeIfAbsent(P2PAddressValidator.normalizePeerId(peerId), NodeState::new);
        boolean reserved = state.flowController.tryReserveOutbound(bytes);
        if (!reserved) {
            errorTracker.record(P2PTransferErrorRecord.now(
                    state.peerId,
                    state.remoteAddress,
                    P2PErrorCode.FLOW_CONTROL_REJECTED,
                    "outbound window is full",
                    0,
                    state.connectionId,
                    null
            ));
        }
        return reserved;
    }

    public FlowControlSnapshot flowControl(String peerId) {
        NodeState state = nodes.computeIfAbsent(P2PAddressValidator.normalizePeerId(peerId), NodeState::new);
        return state.flowController.snapshot();
    }

    private Map<String, QuicConnectionStats> refreshTransportStats() {
        Map<String, QuicConnectionStats> latest = new HashMap<>();
        for (QuicConnectionStats stats : transportGateway.connectionStats()) {
            if (stats.peerId() == null || stats.peerId().isBlank()) {
                continue;
            }
            String peerId = normalizeStatsPeerId(stats.peerId());
            if (P2PSelfNode.isSelfPeerId(peerId)) {
                nodes.remove(peerId);
                try {
                    transportGateway.disconnect(peerId);
                } catch (RuntimeException e) {
                    errorTracker.record(P2PTransferErrorRecord.now(
                            peerId,
                            stats.remoteAddress(),
                            P2PErrorCode.DISCONNECT_FAILED,
                            e.getMessage(),
                            0,
                            stats.connectionId(),
                            e
                    ));
                }
                continue;
            }
            latest.put(peerId, stats);
            NodeState state = nodes.computeIfAbsent(peerId, NodeState::new);
            state.applyStats(stats);
        }
        return latest;
    }

    private String normalizeStatsPeerId(String peerId) {
        try {
            return P2PAddressValidator.normalizePeerId(peerId);
        } catch (RuntimeException e) {
            return peerId.toLowerCase(Locale.ROOT);
        }
    }

    private void recordFailure(NodeState state, P2PErrorCode code, String message, Throwable cause) {
        state.status = P2PNodeStatus.FAILED;
        state.consecutiveErrors++;
        state.lastErrorAtMillis = System.currentTimeMillis();
        state.lastErrorMessage = message == null || message.isBlank() ? code.name() : message;
        errorTracker.record(P2PTransferErrorRecord.now(
                state.peerId,
                state.remoteAddress,
                code,
                state.lastErrorMessage,
                0,
                state.connectionId,
                cause
        ));
        if (state.consecutiveErrors >= AUTO_BLACKLIST_THRESHOLD) {
            blacklist.blockPeerHex(state.peerId, "auto blacklist after repeated transfer failures", AUTO_BLACKLIST_TTL);
            state.status = P2PNodeStatus.BLACKLISTED;
        }
    }

    private final class NodeState {
        private final String peerId;
        private final SlidingThroughputMeter sendMeter = new SlidingThroughputMeter(THROUGHPUT_WINDOW);
        private final SlidingThroughputMeter receiveMeter = new SlidingThroughputMeter(THROUGHPUT_WINDOW);
        private final P2PFlowController flowController = new P2PFlowController();
        private volatile String multiAddress = "";
        private volatile String remoteAddress = "";
        private volatile String ipAddress = "";
        private volatile int port;
        private volatile long connectionId;
        private volatile boolean outbound;
        private volatile P2PNodeStatus status = P2PNodeStatus.DISCONNECTED;
        private volatile long lastConnectedAtMillis;
        private volatile long lastDisconnectedAtMillis;
        private volatile long lastErrorAtMillis;
        private volatile String lastErrorMessage = "";
        private volatile long sentBytes;
        private volatile long receivedBytes;
        private volatile long lastRttMillis = -1;
        private volatile int consecutiveErrors;

        private NodeState(P2PAddress address) {
            this.peerId = address.peerIdHex();
            updateAddress(address);
        }

        private NodeState(String peerId) {
            this.peerId = peerId.toLowerCase(Locale.ROOT);
        }

        private void updateAddress(P2PAddress address) {
            this.multiAddress = address.rawAddress();
            this.ipAddress = address.ipAddress();
            this.port = address.port();
            if (remoteAddress.isBlank()) {
                this.remoteAddress = address.endpointKey();
            }
        }

        private void onConnected(P2PTransportConnection connection) {
            this.connectionId = connection.connectionId();
            this.remoteAddress = connection.remoteAddress();
            this.outbound = connection.outbound();
            this.status = P2PNodeStatus.CONNECTED;
            this.lastConnectedAtMillis = System.currentTimeMillis();
            this.lastErrorMessage = "";
            this.consecutiveErrors = 0;
        }

        private void onDisconnected() {
            this.status = P2PNodeStatus.DISCONNECTED;
            this.lastDisconnectedAtMillis = System.currentTimeMillis();
            this.connectionId = 0;
        }

        private void applyStats(QuicConnectionStats stats) {
            long sentDelta = stats.sentBytes() >= sentBytes ? stats.sentBytes() - sentBytes : stats.sentBytes();
            long receivedDelta = stats.receivedBytes() >= receivedBytes ? stats.receivedBytes() - receivedBytes : stats.receivedBytes();
            sendMeter.recordBytes(sentDelta);
            receiveMeter.recordBytes(receivedDelta);
            sentBytes = stats.sentBytes();
            receivedBytes = stats.receivedBytes();
            lastRttMillis = stats.lastRttMillis();
            connectionId = stats.connectionId();
            remoteAddress = stats.remoteAddress();
            outbound = stats.outbound();
            status = stats.expired() ? P2PNodeStatus.DISCONNECTED : P2PNodeStatus.CONNECTED;
            flowController.updateRemoteReceiveWindow(stats.remoteReceiveWindow());
        }

        private P2PNodeSnapshot snapshot(QuicConnectionStats stats) {
            Optional<P2PBlacklistEntry> blacklistEntry = blacklist.findPeerHex(peerId)
                    .or(() -> ipAddress.isBlank() ? Optional.empty() : blacklist.findIp(ipAddress));
            boolean isBlacklisted = blacklistEntry.isPresent();
            P2PNodeStatus snapshotStatus = isBlacklisted ? P2PNodeStatus.BLACKLISTED : status;
            long snapshotSent = stats == null ? sentBytes : stats.sentBytes();
            long snapshotReceived = stats == null ? receivedBytes : stats.receivedBytes();
            double sendSpeed = Math.max(sendMeter.bytesPerSecond(), stats == null ? 0 : stats.sendBytesPerSecond());
            double receiveSpeed = Math.max(receiveMeter.bytesPerSecond(), stats == null ? 0 : stats.receiveBytesPerSecond());
            FlowControlSnapshot flow = stats == null
                    ? flowController.snapshot()
                    : new FlowControlSnapshot(
                    stats.localReceiveWindow(),
                    P2PFlowController.DEFAULT_RECEIVE_WINDOW,
                    stats.remoteReceiveWindow(),
                    new CongestionControlSnapshot(
                            stats.congestionWindow(),
                            stats.slowStartThreshold(),
                            stats.sentUnAckedBytes(),
                            Math.max(0, Math.min(stats.remoteReceiveWindow(), stats.congestionWindow()) - stats.sentUnAckedBytes()),
                            AimdCongestionController.DEFAULT_MSS
                    )
            );
            return new P2PNodeSnapshot(
                    peerId,
                    multiAddress,
                    remoteAddress,
                    ipAddress,
                    port,
                    connectionId,
                    outbound,
                    snapshotStatus,
                    isBlacklisted,
                    blacklistEntry.map(P2PBlacklistEntry::reason).orElse(""),
                    lastConnectedAtMillis,
                    lastDisconnectedAtMillis,
                    lastErrorAtMillis,
                    lastErrorMessage,
                    snapshotSent,
                    snapshotReceived,
                    sendSpeed,
                    receiveSpeed,
                    lastRttMillis,
                    flow,
                    errorTracker.count(peerId)
            );
        }
    }
}
