package com.bit.coin.p2p.conn;

import io.netty.buffer.Unpooled;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** 通过已认证的会话进行单跳转发，中继无法解密内层会话的数据。 */
public final class RelayTransport {

    public static final int PEER_BYTES = 32;
    // 按最小路径数据报扣除外层会话、帧头和节点 ID，得到内层包的字节上限。
    public static final int INNER_DATAGRAM_BYTES = PathMtuEstimator.MIN_DATAGRAM_BYTES
            - SessionCipher.OVERHEAD_BYTES - QuicFrame.FIXED_HEADER_LENGTH - PEER_BYTES;
    private static volatile boolean serverEnabled;
    private static volatile Set<String> trustedRelays = Set.of();
    private static volatile long peerRate = 2 * 1024 * 1024;
    private static volatile Budget global = new Budget(8 * 1024 * 1024);
    private static final com.github.benmanes.caffeine.cache.Cache<String, Budget> budgets = com.github.benmanes.caffeine.cache.Caffeine
            .newBuilder().maximumSize(200)
            .expireAfterAccess(2, TimeUnit.MINUTES).build();
    private static final AtomicLong forwardedPackets = new AtomicLong(), forwardedBytes = new AtomicLong(),
            rejectedPackets = new AtomicLong();

    private RelayTransport() {}

    public static synchronized void configure(boolean enabled, Set<String> trusted, long bytesPerSecond) {
        if (bytesPerSecond < 1200 || bytesPerSecond > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("relay bytes-per-second must be 1200..16777216");
        }
        serverEnabled = enabled;
        trustedRelays = Set.copyOf(trusted);
        peerRate = bytesPerSecond;
        global = new Budget(bytesPerSecond * 4);
        budgets.invalidateAll();
    }

    public static boolean isTrusted(String peerId) {
        return trustedRelays.contains(peerId);
    }

    public static boolean isRelayFrame(byte type) {
        return type == QuicFrameEnum.RELAY_FORWARD_FRAME.getCode()
                || type == QuicFrameEnum.RELAY_DELIVER_FRAME.getCode();
    }

    public static boolean send(RelayPath path, byte[] inner) {
        if (!path.isAvailable() || inner.length > INNER_DATAGRAM_BYTES) {
            return false;
        }
        QuicConnection hop = QuicConnectionManager.getConnection(path.relayConnectionId());
        return isTrusted(hop.getPeerId()) && sendEnvelope(hop, QuicFrameEnum.RELAY_FORWARD_FRAME, path.peerId(), inner);
    }

    /** 对转发请求检查目标和配额；对可信中继交付的包，交还内层会话处理。 */
    public static void receive(QuicConnection carrier, QuicFrame frame) {
        byte[] payload = frame.getPayload();
        if (carrier.isExpired() || carrier.getRelayPath() != null || carrier.getSessionCipher() == null
                || payload == null
                || payload.length < PEER_BYTES + SessionCipher.PREFIX_BYTES + QuicFrame.FIXED_HEADER_LENGTH
                || payload.length > PEER_BYTES + INNER_DATAGRAM_BYTES) {
            rejectedPackets.incrementAndGet();
            return;
        }
        String peer = HexFormat.of().formatHex(payload, 0, PEER_BYTES);
        byte[] inner = Arrays.copyOfRange(payload, PEER_BYTES, payload.length);
        if (frame.getFrameType() == QuicFrameEnum.RELAY_FORWARD_FRAME.getCode()) {
            QuicConnection destination = QuicConnectionManager.getConnectionByPeerId(peer);
            if (!serverEnabled || peer.equals(carrier.getPeerId()) || destination == null
                    || destination.getRelayPath() != null || destination.getSessionCipher() == null
                    || !allowForward(carrier.getPeerId(), peer, payload.length + 66)) {
                rejectedPackets.incrementAndGet();
                return;
            }
            if (sendEnvelope(destination, QuicFrameEnum.RELAY_DELIVER_FRAME, carrier.getPeerId(), inner)) {
                forwardedPackets.incrementAndGet();
                forwardedBytes.addAndGet(inner.length);
            } else {
                rejectedPackets.incrementAndGet();
            }
        } else if (frame.getFrameType() == QuicFrameEnum.RELAY_DELIVER_FRAME.getCode()
                && isTrusted(carrier.getPeerId())) {
            var buffer = Unpooled.wrappedBuffer(inner);
            try {
                QuicServiceHandler.receiveDatagram(buffer, carrier.getRemoteAddress(),
                        new RelayPath(carrier.getConnectionId(), peer));
            } finally {
                buffer.release();
            }
        } else {
            rejectedPackets.incrementAndGet();
        }
    }

    static synchronized boolean allowForward(String source, String target, int bytes) {
        // 每个包至少按最低字节数扣减配额，以同时限制带宽和每秒处理的包数。
        int cost = Math.max(256, bytes);
        Budget sender = budgets.get("send:" + source, k -> new Budget(peerRate));
        Budget receiver = budgets.get("receive:" + target, k -> new Budget(peerRate));
        if (!global.available(cost) || !sender.available(cost) || !receiver.available(cost)) {
            return false;
        }
        global.take(cost);
        sender.take(cost);
        receiver.take(cost);
        return true;
    }

    private static boolean sendEnvelope(QuicConnection hop, QuicFrameEnum type, String peer, byte[] inner) {
        byte[] id = HexFormat.of().parseHex(peer);
        if (id.length != PEER_BYTES) {
            return false;
        }
        byte[] payload = new byte[PEER_BYTES + inner.length];
        System.arraycopy(id, 0, payload, 0, PEER_BYTES);
        System.arraycopy(inner, 0, payload, PEER_BYTES, inner.length);
        QuicFrame frame = new QuicFrame();
        frame.setConnectionId(hop.getConnectionId());
        frame.setDataId(QuicConstants.ID_Generator.nextId());
        frame.setTotal(1);
        frame.setFrameType(type.getCode());
        frame.setPayload(payload);
        frame.setRemoteAddress(hop.getRemoteAddress());
        frame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH + payload.length);
        return QuicConnectionManager.sendFrameWithoutResponse(frame);
    }

    public static Map<String, Object> status() {
        List<Map<String, Object>> circuits = new ArrayList<>();
        for (long id : QuicConnectionManager.getAllConnectionIds()) {
            QuicConnection c = QuicConnectionManager.getConnection(id);
            if (c == null || c.isExpired() || c.getRelayPath() == null) {
                continue;
            }
            QuicConnection hop = QuicConnectionManager.getConnection(c.getRelayPath().relayConnectionId());
            circuits.add(Map.of("peerId", c.getPeerId(), "relayPeerId", hop == null ? "" : hop.getPeerId(),
                    "available", c.getRelayPath().isAvailable(), "connectionId", id));
        }
        return Map.of("serverEnabled", serverEnabled, "trustedRelays", trustedRelays, "circuits", circuits,
                "forwardedPackets", forwardedPackets.get(), "forwardedBytes", forwardedBytes.get(),
                "rejectedPackets", rejectedPackets.get(), "innerDatagramBytes", INNER_DATAGRAM_BYTES);
    }

    static final class Budget {

        private final long rate;
        private double tokens;
        private long updated = System.nanoTime();

        Budget(long rate) {
            this.rate = rate;
            tokens = rate;
        }

        synchronized boolean available(int bytes) {
            long now = System.nanoTime();
            tokens = Math.min(rate, tokens + Math.max(0, now - updated) / 1_000_000_000.0 * rate);
            updated = now;
            return bytes > 0 && bytes <= tokens;
        }

        synchronized boolean take(int bytes) {
            if (!available(bytes)) {
                return false;
            }
            tokens -= bytes;
            return true;
        }
    }
}
