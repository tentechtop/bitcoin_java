package com.bit.coin.p2p.conn;

import com.bit.coin.config.SystemConfig;
import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.blockchain.ChainTipSnapshot;
import com.bit.coin.p2p.peer.Peer;
import com.bit.coin.p2p.production.P2PAddressValidator;
import com.bit.coin.p2p.production.P2PErrorCode;
import com.bit.coin.p2p.production.P2PSelfNode;
import com.bit.coin.p2p.production.P2PTransferErrorEvents;
import com.bit.coin.p2p.production.P2PTransferErrorRecord;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolEnum;
import com.bit.coin.p2p.protocol.RequestConnect;
import com.bit.coin.utils.Ed25519Signer;
import com.bit.coin.utils.MultiAddress;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import io.netty.buffer.ByteBuf;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.base.Base58;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bit.coin.config.SystemConfig.SelfPeer;
import static com.bit.coin.p2p.conn.QuicConstants.*;
import static com.bit.coin.p2p.conn.QuicFrame.ALLOCATOR;
import static com.bit.coin.p2p.protocol.P2PMessage.newRequestMessage;
import static com.bit.coin.utils.ECCWithAESGCM.generateCurve25519KeyPair;
import static com.bit.coin.utils.ECCWithAESGCM.generateSharedSecret;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static com.bit.coin.utils.SerializeUtils.hexToBytes;

@Slf4j
@Component
public class QuicConnectionManager {

    private static long handshakeSecond;

    private static int handshakeCount;

    @Autowired
    private RoutingTable routingTable;

    @Autowired
    SystemConfig systemConfig;

    @Autowired
    private BlockChainServiceImpl blockChainService;

    private static volatile BlockChainServiceImpl CHAIN_REF;

    private static volatile int NETWORK;

    public static void configureNetwork(int network) {
        NETWORK = network;
    }

    private record PendingHandshake(RequestConnect request, InetSocketAddress remote, String expectedPeer,
            RelayPath route,
            CompletableFuture<VerifiedHandshake> reply) {}

    private record VerifiedHandshake(QuicFrame frame, RequestConnect response) {}

    private record HandshakeReply(byte[] request, InetSocketAddress remote, RelayPath route, QuicFrame response) {}

    private static final com.github.benmanes.caffeine.cache.Cache<Long, HandshakeReply> HANDSHAKE_REPLIES = com.github.benmanes.caffeine.cache.Caffeine
            .newBuilder().maximumSize(100)
            .expireAfterWrite(10, TimeUnit.SECONDS).build();

    private static synchronized boolean allowInboundHandshake() {
        long second = System.nanoTime() / 1_000_000_000L;
        if (second != handshakeSecond) {
            handshakeSecond = second;
            handshakeCount = 0;
        }
        return ++handshakeCount <= 50;
    }

    private static final Map<Long, PendingHandshake> PENDING_HANDSHAKES = new ConcurrentHashMap<>();
    private static final com.github.benmanes.caffeine.cache.Cache<String, Boolean> HANDSHAKE_NONCES = com.github.benmanes.caffeine.cache.Caffeine
            .newBuilder().maximumSize(10_000)
            .expireAfterWrite(125, TimeUnit.SECONDS).build();

    private static ChainTipSnapshot currentTip() {
        BlockChainServiceImpl chain = CHAIN_REF;
        return chain == null ? null : chain.getMainTipSnapshot();
    }

    /** 应答的来源、路径、身份和签名均匹配时，唤醒对应的握手请求。 */
    public static void completeHandshakeResponse(QuicFrame frame) {
        PendingHandshake pending = PENDING_HANDSHAKES.get(frame.getDataId());
        if (pending == null || !pending.remote().equals(frame.getRemoteAddress())
                || !java.util.Objects.equals(pending.route(), frame.getRelayPath())) {
            return;
        }
        if (pending.reply().isDone() || frame.getConnectionId() != pending.request().getConnectionId()) {
            return;
        }
        VerifiedHandshake verified = verifyHandshakeResponse(frame, pending.request());
        if (verified != null && pending.expectedPeer().equals(bytesToHex(verified.response().getPeerId()))) {
            pending.reply().complete(verified);
        }
    }

    private static VerifiedHandshake verifyHandshakeResponse(QuicFrame frame, RequestConnect request) {
        if (frame.getFrameType() != QuicFrameEnum.CONNECT_RESPONSE_FRAME.getCode()) {
            return null;
        }
        RequestConnect response = RequestConnect.deserialize(frame.getPayload());
        if (!response.verifyContext(NETWORK, frame.getConnectionId(), frame.getDataId(),
                RequestConnect.RESPONSE, request.getNonce(), System.currentTimeMillis() / 1000)) {
            return null;
        }
        return new VerifiedHandshake(frame, response);
    }

    private static volatile RoutingTable ROUTING_TABLE_REF;

    //节点ID到连接ID
    public static final Map<String, Long> PEER_CONNECTION = new ConcurrentHashMap<>();
    // 按连接 ID 查找会话，节点 ID 则通过 PEER_CONNECTION 反查。
    private static final Map<Long, QuicConnection> CONNECTION_MAP = new ConcurrentHashMap<>();
    private static final int X25519_KEY_LENGTH = 32;

    //全局UDP通道
    /**
     * The Netty event loop publishes the bound channel while request and
     * maintenance threads read it.  Volatile is required here: without a
     * happens-before edge those threads can observe a stale/null channel and
     * incorrectly report the transport as unavailable.
     */
    public static volatile DatagramChannel Global_Channel = null;// UDP通道

    // 所有连接共享同一出站流量预算。
    private static final P2PGlobalTrafficController GLOBAL_OUTBOUND_TRAFFIC = P2PGlobalTrafficController
            .fromSystemProperties();

    private static final long CONNECTION_EXPIRE_TIME = 60_000;  // 距最后活跃时间超过此毫秒数时清理连接

    // ===================== 定时任务线程池 =====================
    // 单线程定时任务池：用于每秒执行连接检查，设置为守护线程避免阻塞应用关闭
    private final ScheduledExecutorService connectionHealthExecutor = Executors
            .newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "quic-connection-health-thread");
                thread.setDaemon(true);
                return thread;
            });

    // 高频任务池（发送检查）：20ms/次
    private final ScheduledExecutorService connectionSendExecutor = new ScheduledThreadPoolExecutor(
            2,  // 发送任务池固定使用两个线程
            runnable -> {
                Thread thread = new Thread(runnable, "quic-connection-send-thread");
                thread.setDaemon(true);
                return thread;
            });

    // 握手回调独立排队，避免阻塞收包线程。
    private static volatile ThreadPoolExecutor HANDSHAKE_CALLBACK_EXECUTOR = newHandshakeCallbackExecutor();

    private static ThreadPoolExecutor newHandshakeCallbackExecutor() {
        return new ThreadPoolExecutor(
                2,
                4,
                60,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1024),
                runnable -> {
                    Thread thread = new Thread(runnable, "quic-handshake-callback-thread");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static synchronized ThreadPoolExecutor handshakeCallbackExecutor() {
        if (HANDSHAKE_CALLBACK_EXECUTOR == null || HANDSHAKE_CALLBACK_EXECUTOR.isShutdown()) {
            HANDSHAKE_CALLBACK_EXECUTOR = newHandshakeCallbackExecutor();
        }
        return HANDSHAKE_CALLBACK_EXECUTOR;
    }

    private static final int MAX_GLOBAL_CONNECTIONS = 100;  // 连接总数上限，同时用于限制等待完成的握手数量
    // 同一来源 IP 在一秒计数窗口内允许的握手请求数。
    private static final int MAX_REQUEST_PER_IP_PER_SECOND = 10;
    // 单PeerId每分钟最大重复连接请求数
    private static final int MAX_REQUEST_PER_PEER_PER_MINUTE = 5;
    // IP黑名单过期时间（临时拉黑超限IP，单位：分钟）
    private static final int IP_BLACKLIST_EXPIRE_MINUTES = 10;

    // IP请求计数器（原有，优化过期时间为1秒）
    private static final LoadingCache<String, AtomicInteger> IP_REQUEST_LIMIT = CacheBuilder.newBuilder()
            .expireAfterWrite(1, TimeUnit.SECONDS) // 改为1秒过期，精准控制每秒请求数
            .maximumSize(10_000)
            .concurrencyLevel(4)
            .build(new CacheLoader<>() {

                @Override
                public AtomicInteger load(String ip) {
                    return new AtomicInteger(0);
                }
            });

    // PeerId请求计数器（限制单PeerId重复请求??
    private static final LoadingCache<String, AtomicInteger> PEER_REQUEST_LIMIT = CacheBuilder.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .concurrencyLevel(4)
            .build(new CacheLoader<>() {

                @Override
                public AtomicInteger load(String peerId) {
                    return new AtomicInteger(0);
                }
            });

    // IP黑名单（临时拉黑超限IP??
    private static final LoadingCache<String, Boolean> IP_BLACKLIST = CacheBuilder.newBuilder()
            .expireAfterWrite(IP_BLACKLIST_EXPIRE_MINUTES, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .concurrencyLevel(4)
            .build(new CacheLoader<>() {

                @Override
                public Boolean load(String ip) {
                    return false; // 默认未拉??
                }
            });

    /**
     * 启动连接检查定时任务：每秒执行一次checkConnection
     */
    @PostConstruct
    public void initConnectionCheckTask() {
        ROUTING_TABLE_REF = routingTable;
        CHAIN_REF = blockChainService;
        NETWORK = systemConfig.getParams().getNet();
        if (!systemConfig.isNetworkingEnabled()) {
            return;
        }
        // 低频心跳清理
        connectionHealthExecutor.scheduleAtFixedRate(
                this::checkConnection,
                1,
                1,
                TimeUnit.SECONDS);

        // 高频：20ms/次发送检查
        connectionSendExecutor.scheduleAtFixedRate(
                this::checkConnectionSendData,
                0,
                20,
                TimeUnit.MILLISECONDS);
    }

    // 销毁方法
    @PreDestroy
    public void destroyConnectionCheckExecutor() {
        // 关闭健康检查线程池
        shutdownExecutor(connectionHealthExecutor, "connection-health");
        // 关闭发送检查线程池
        shutdownExecutor(connectionSendExecutor, "connection-send");
        ThreadPoolExecutor callbacks = HANDSHAKE_CALLBACK_EXECUTOR;
        if (callbacks != null) {
            shutdownExecutor(callbacks, "handshake-callback");
            HANDSHAKE_CALLBACK_EXECUTOR = null;
        }
        // All connection maps are static for the transport API.  Clear them on
        // context shutdown so an embedded restart cannot reuse released Netty
        // channels or pending handshake state from the previous node instance.
        PENDING_HANDSHAKES.values().forEach(pending ->
                pending.reply().completeExceptionally(new CancellationException("node shutting down")));
        PENDING_HANDSHAKES.clear();
        HANDSHAKE_NONCES.invalidateAll();
        IP_REQUEST_LIMIT.invalidateAll();
        PEER_REQUEST_LIMIT.invalidateAll();
        IP_BLACKLIST.invalidateAll();
        clearAllConnections();
        ROUTING_TABLE_REF = null;
        CHAIN_REF = null;
    }

    // 抽取通用关闭逻辑
    private void shutdownExecutor(ExecutorService executor, String name) {
        log.info("开始关闭{}线程池", name);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                log.warn("{}线程池未能在5秒内关闭，已执行强制关闭", name);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            log.error("关闭{}线程池时被中断", name, e);
        }
        log.info("{}线程池已关闭", name);
    }

    /**
     * 通过连接ID获取连接
     */
    private static void submitHandshakeCallback(Runnable task) {
        try {
            handshakeCallbackExecutor().execute(task);
        } catch (RejectedExecutionException e) {
            log.warn("[握手回调提交失败] 队列已满或线程池已关闭", e);
        }
    }

    public static QuicConnection getConnection(long connectionId) {
        return CONNECTION_MAP.get(connectionId);
    }

    /**
     * 获取连接总数
     */
    public static int getConnectionCount() {
        return CONNECTION_MAP.size();
    }

    /**
     * 获取第一个连接
     */
    public static QuicConnection getFirstConnection() {
        return CONNECTION_MAP.values().stream().findFirst().orElse(null);
    }

    /**
     * 检查连接是否存在
     */
    public static boolean hasConnection(long connectionId) {
        return CONNECTION_MAP.get(connectionId) != null;
    }

    //获取所有连??
    public static Set<QuicConnection> getAllConnections() {
        return new HashSet<>(CONNECTION_MAP.values());
    }

    //获取所有节??
    public static Set<String> getAllPeers() {
        return Set.copyOf(PEER_CONNECTION.keySet());
    }

    static boolean isValidSharedSecretMaterial(byte[] keyMaterial) {
        if (keyMaterial == null || keyMaterial.length != X25519_KEY_LENGTH) {
            return false;
        }
        for (byte b : keyMaterial) {
            if (b != 0) {
                return true;
            }
        }
        return false;
    }

    private static byte[] negotiateSharedSecret(byte[] localPrivateKey,
            byte[] remotePublicKey,
            long connectionId,
            InetSocketAddress remoteAddress) {
        if (!isValidSharedSecretMaterial(remotePublicKey)) {
            log.warn("[reject connection] invalid sharedSecret public key, connectionId={}, remote={}",
                    connectionId, remoteAddress);
            return null;
        }

        byte[] sharedSecret;
        try {
            sharedSecret = generateSharedSecret(localPrivateKey, remotePublicKey);
        } catch (RuntimeException invalidPeerKey) {
            // Malformed/low-order X25519 keys are expected from untrusted peers;
            // reject the handshake without propagating into the transport loop.
            log.warn("[reject connection] invalid X25519 key, connectionId={}, remote={}",
                    connectionId, remoteAddress);
            return null;
        }
        if (!isValidSharedSecretMaterial(sharedSecret)) {
            log.warn("[reject connection] sharedSecret negotiation failed, connectionId={}, remote={}",
                    connectionId, remoteAddress);
            if (sharedSecret != null) {
                Arrays.fill(sharedSecret, (byte) 0);
            }
            return null;
        }
        return sharedSecret;
    }

    private static synchronized void registerConnection(String peerId, long connectionId, QuicConnection connection) {
        if (connection == null || !isValidSharedSecretMaterial(connection.getSharedSecret())) {
            if (connection != null) {
                connection.release();
            }
            PEER_CONNECTION.remove(peerId);
            CONNECTION_MAP.remove(connectionId);
            log.warn("[reject connection] sharedSecret negotiation not completed, peerId={} connectionId={}",
                    peerId, connectionId);
            return;
        }
        if (P2PSelfNode.isSelfPeerId(peerId)) {
            PEER_CONNECTION.remove(peerId);
            CONNECTION_MAP.remove(connectionId);
            if (connection != null) {
                connection.release();
            }
            log.warn("[reject self connection] peerId={} connectionId={}", peerId, connectionId);
            return;
        }
        if (CONNECTION_MAP.size() >= MAX_GLOBAL_CONNECTIONS && !PEER_CONNECTION.containsKey(peerId)) {
            connection.release();
            throw new IllegalArgumentException("Connection limit reached");
        }
        Long oldConnectionId = PEER_CONNECTION.put(peerId, connectionId);
        if (oldConnectionId != null && oldConnectionId != connectionId) {
            QuicConnection oldConnection = CONNECTION_MAP.remove(oldConnectionId);
            if (oldConnection != null && oldConnection != connection) {
                oldConnection.release();
                log.info("[replace duplicate peer connection] peerId={} oldConnectionId={} newConnectionId={}",
                        peerId, oldConnectionId, connectionId);
            }
        }
        CONNECTION_MAP.put(connectionId, connection);
        pruneStalePeerConnections();
    }

    private static synchronized void pruneStalePeerConnections() {
        for (Map.Entry<Long, QuicConnection> entry : CONNECTION_MAP.entrySet()) {
            Long connectionId = entry.getKey();
            QuicConnection connection = entry.getValue();
            if (connection == null) {
                CONNECTION_MAP.remove(connectionId);
                continue;
            }
            String peerId = connection.getPeerId();
            if (peerId == null || peerId.isEmpty()) {
                continue;
            }
            Long activeConnectionId = PEER_CONNECTION.get(peerId);
            if (P2PSelfNode.isSelfPeerId(peerId)) {
                PEER_CONNECTION.remove(peerId);
                CONNECTION_MAP.remove(connectionId, connection);
                connection.release();
                log.warn("[prune self connection] peerId={} connectionId={}", peerId, connectionId);
                continue;
            }
            if (connection.isExpired() || (activeConnectionId != null && !activeConnectionId.equals(connectionId))) {
                if (activeConnectionId != null && activeConnectionId.equals(connectionId)) {
                    PEER_CONNECTION.remove(peerId, connectionId);
                }
                CONNECTION_MAP.remove(connectionId, connection);
                connection.release();
                log.info("[prune stale peer connection] peerId={} connectionId={} activeConnectionId={}",
                        peerId, connectionId, activeConnectionId);
            }
        }
    }

    /**
     * 获取所有连接ID
     */
    public static Set<Long> getAllConnectionIds() {
        // Do not expose the live ConcurrentHashMap view to callers.  A caller
        // iterating this set while a connection is removed would otherwise see
        // weakly-consistent, mutable state and could accidentally remove a
        // connection through Set.remove().
        return Set.copyOf(CONNECTION_MAP.keySet());
    }

    public static List<QuicConnectionStats> getConnectionStats() {
        pruneStalePeerConnections();
        long now = System.currentTimeMillis();
        List<QuicConnectionStats> stats = new ArrayList<>();
        for (QuicConnection connection : CONNECTION_MAP.values()) {
            if (connection != null) {
                stats.add(connection.snapshotStats(now));
            }
        }
        stats.sort(Comparator.comparing(QuicConnectionStats::peerId,
                Comparator.nullsLast(String::compareTo)));
        return stats;
    }

    /**
     * 清空所有节点和连接
     */
    public static void clearAllConnections() {
        HANDSHAKE_REPLIES.invalidateAll();
        //清空节点到连接
        PEER_CONNECTION.clear();
        //调用所有连接的release方法
        CONNECTION_MAP.values().forEach(QuicConnection::release);
        //清空连接
        CONNECTION_MAP.clear();
    }

    /**
     * 删除连接 同时要删除节点到连接的索引，并停止SendManager的所有发??
     */
    public static void removeConnection(long connectionId) {
        QuicConnection connection = CONNECTION_MAP.remove(connectionId);
        if (connection != null) {
            // 释放连接资源
            connection.release();
            // 删除节点到连接的索引
            PEER_CONNECTION.remove(connection.getPeerId(), connectionId);
        }
    }

    /**
     * 通过连接ID获取节点
     */
    public static String getPeerId(long connectionId) {
        QuicConnection connection = CONNECTION_MAP.get(connectionId);
        return connection == null ? null : connection.getPeerId();
    }

    /**
     * 通过自解释型地址来连接节??/ip4/127.0.0.1/quic/8334/p2p/CrACARmu8BpDDBz9XknpqgZzMZRgratLMdEk7C76avaU
     * @param multiAddressString
     * @return
     */
    public static QuicConnection connectRemoteByAddr(String multiAddressString) {
        //不为空
        if (multiAddressString == null || multiAddressString.isEmpty()) {
            return null;
        }
        var multiAddress = P2PAddressValidator.parseQuicAddress(multiAddressString);
        String peerIdHex = multiAddress.peerIdHex();
        String ipAddress = multiAddress.ipAddress();
        int port = multiAddress.port();
        if (P2PSelfNode.isSelfPeerId(peerIdHex) || P2PSelfNode.isSelfEndpoint(ipAddress, port)) {
            PEER_CONNECTION.remove(peerIdHex);
            log.warn("拒绝连接本机节点，地址：{}，PeerId：{}", multiAddressString, peerIdHex);
            return null;
        }
        Long existingId = PEER_CONNECTION.get(peerIdHex);
        if (existingId != null) {
            QuicConnection existing = getConnection(existingId);
            if (existing != null && !existing.isExpired()
                    && (existing.getRelayPath() == null || existing.getRelayPath().isAvailable())) {
                return existing;
            }
            PEER_CONNECTION.remove(peerIdHex, existingId);
        }
        RelayPath route = null;
        if (multiAddress.isRelayed()) {
            var relay = P2PAddressValidator.parseQuicAddress(multiAddress.relayAddress());
            if (!RelayTransport.isTrusted(relay.peerIdHex())) {
                return null;
            }
            QuicConnection hop = connectRemoteByAddr(multiAddress.relayAddress());
            if (hop == null || hop.getRelayPath() != null) {
                return null;
            }
            route = new RelayPath(hop.getConnectionId(), peerIdHex);
        }
        QuicConnection connection = connectRemote(ipAddress, port, peerIdHex, route);
        return connection != null ? connection : getConnectionByPeerId(peerIdHex);
    }

    //根据节点ID获取连接 hexID
    public static QuicConnection getConnectionByPeerId(String peerId) {
        if (P2PSelfNode.isSelfPeerId(peerId)) {
            PEER_CONNECTION.remove(peerId);
            return null;
        }
        Long l = PEER_CONNECTION.get(peerId);
        if (l != null) {
            QuicConnection connection = getConnection(l);
            if (connection != null && !connection.isExpired()
                    && (connection.getRelayPath() == null || connection.getRelayPath().isAvailable())) {
                return connection;
            }
            PEER_CONNECTION.remove(peerId, l);
        }
        return null;
    }

    //Base58Id
    public static QuicConnection connectRemoteByBase58Id(String base58Id) {
        byte[] decode = Base58.decode(base58Id);
        String s = bytesToHex(decode);
        return getConnectionByPeerId(s);
    }

    private static QuicConnection connectRemote(String ipAddress, int port, String expectedPeer) {
        return connectRemote(ipAddress, port, expectedPeer, null);
    }

    private static QuicConnection connectRemote(String ipAddress, int port, String expectedPeer, RelayPath route) {
        long conId = -1L;
        long dataId = -1L;
        try {
            if (P2PSelfNode.isSelfEndpoint(ipAddress, port)) {
                log.warn("拒绝连接本机端点：{}:{}", ipAddress, port);
                return null;
            }
            InetSocketAddress remoteAddress = new InetSocketAddress(ipAddress, port);
            conId = ID_Generator.nextId();
            dataId = ID_Generator.nextId();
            //连接请求??
            QuicFrame reqQuicFrame = new QuicFrame();
            reqQuicFrame.setConnectionId(conId);//生成连接ID
            reqQuicFrame.setDataId(dataId);
            reqQuicFrame.setTotal(0);
            reqQuicFrame.setFrameType(QuicFrameEnum.CONNECT_REQUEST_FRAME.getCode());
            RequestConnect requestConnect = new RequestConnect();
            requestConnect.setPeerId(SelfPeer.getId());
            ChainTipSnapshot tip = currentTip();
            if (tip == null) {
                return null;
            }
            requestConnect.setLatestHash(tip.hash());
            requestConnect.setLatestHeight(tip.height());
            requestConnect.setChainWork(tip.chainWork());
            byte[][] AKeys = generateCurve25519KeyPair();
            byte[] aPrivateKey = AKeys[0];
            byte[] aPublicKey = AKeys[1];
            requestConnect.setVersion(RequestConnect.VERSION);
            requestConnect.bind(NETWORK, conId, dataId, RequestConnect.REQUEST, null);
            requestConnect.setSharedSecretPubKey(aPublicKey);
            byte[] signatureData = requestConnect.getSignatureData();
            //生成签名
            byte[] signature = Ed25519Signer.fastSign(SelfPeer.getPrivateKey(), signatureData);//签名
            requestConnect.setSignature(signature);
            byte[] serialize = requestConnect.serialize();
            reqQuicFrame.setPayload(serialize);
            reqQuicFrame.setRemoteAddress(remoteAddress);
            reqQuicFrame.setRelayPath(route);
            reqQuicFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH + serialize.length);
            //发送连接确认帧
            CompletableFuture<VerifiedHandshake> responseFuture = new CompletableFuture<>();
            synchronized (PENDING_HANDSHAKES) {
                if (PENDING_HANDSHAKES.size() >= MAX_GLOBAL_CONNECTIONS) {
                    return null;
                }
                PENDING_HANDSHAKES.put(dataId,
                        new PendingHandshake(requestConnect, remoteAddress, expectedPeer, route, responseFuture));
            }
            try {
                for (int attempt = 0; attempt < 5; attempt++) {
                    if (route != null && !route.isAvailable()) {
                        return null;
                    }
                    sendFrameWithoutResponse(reqQuicFrame);
                    try {
                        return establishConnection(responseFuture.get(1, TimeUnit.SECONDS), aPrivateKey,
                                requestConnect);
                    } catch (TimeoutException lostPacket) {
                        if (attempt == 4) {
                            throw lostPacket;
                        }
                    }
                }
                return null;
            } finally {
                PENDING_HANDSHAKES.remove(dataId);
                Arrays.fill(aPrivateKey, (byte) 0);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            P2PTransferErrorEvents.publish(P2PTransferErrorRecord.now(
                    null,
                    ipAddress + ":" + port,
                    P2PErrorCode.CONNECT_FAILED,
                    e.getMessage(),
                    dataId,
                    conId,
                    e));
            log.error("连接远程节点失败", e);
            return null;
        }
    }

    //处理连接响应：直接根据帧携带的内容创建一个连接
    public static QuicConnection handleConnectResponseFrame(QuicFrame quicFrame, byte[] aPrivateKey,
            RequestConnect request) {
        VerifiedHandshake verified = verifyHandshakeResponse(quicFrame, request);
        return verified == null ? null : establishConnection(verified, aPrivateKey, request);
    }

    private static QuicConnection establishConnection(VerifiedHandshake verified, byte[] aPrivateKey,
            RequestConnect request) {
        QuicFrame quicFrame = verified.frame();
        RequestConnect response = verified.response();
        long connectionId = quicFrame.getConnectionId();
        InetSocketAddress remoteAddress = quicFrame.getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            log.warn("连接响应缺少远程地址，连接ID：{}", connectionId);
            return null;
        }
        if (!allowInboundHandshake()) {
            return null;
        }
        String remoteIp = remoteAddress.getAddress().getHostAddress();
        if (hasConnection(connectionId)) {
            log.warn("连接响应中的连接ID已存在，连接ID：{}", connectionId);
            return null;
        }

        // 版本网络校验（原有逻辑，保留）
        int version = response.getVersion();
        if (version != RequestConnect.VERSION) { // 假设当前版本为1，根据实际业务调整
            log.warn("连接响应版本不支持，连接ID：{}，版本：{}", connectionId, version);
            return null;
        }

        // 步骤3：PeerId校验 - 重复PeerId请求限流
        byte[] peerIdBytes = response.getPeerId();
        if (peerIdBytes == null || peerIdBytes.length == 0) {
            log.warn("连接响应缺少PeerId，连接ID：{}，远程地址：{}", connectionId, remoteAddress);
            return null;
        }
        String peerIdHexString = bytesToHex(peerIdBytes);
        if (P2PSelfNode.isSelfPeerId(peerIdHexString)) {
            log.warn("拒绝连接响应中的本机PeerId，连接ID：{}，远程地址：{}", connectionId, remoteAddress);
            return null;
        }
        byte[] sharedSecretPubKey = response.getSharedSecretPubKey();
        byte[] sharedSecret = negotiateSharedSecret(aPrivateKey, sharedSecretPubKey, connectionId, remoteAddress);
        if (sharedSecret == null) {
            return null;
        }

        // 步骤10：创建连接实例并缓存
        QuicConnection quicConnection = new QuicConnection();
        quicConnection.setPeerId(peerIdHexString);
        quicConnection.setRelayPath(quicFrame.getRelayPath());
        quicConnection.setConnectionId(connectionId);
        quicConnection.setRemoteAddress(remoteAddress);
        quicConnection.setLastSeen(System.currentTimeMillis());//设置为当前时间，两秒后过期
        quicConnection.setOutbound(true);//主动连接
        quicConnection.setSharedSecret(sharedSecret);
        quicConnection.setSessionCipher(new SessionCipher(sharedSecret, request.getSignatureData(),
                response.getSignatureData(), connectionId, true));

        registerConnection(peerIdHexString, connectionId, quicConnection);

        //使用线程
        submitHandshakeCallback(() -> {
            Peer peer = new Peer();
            peer.setInetSocketAddress(remoteAddress);
            peer.setId(hexToBytes(peerIdHexString));
            peer.setHeight(response.getLatestHeight());
            peer.setHashHex(bytesToHex(response.getLatestHash()));
            peer.setChainWork(response.getChainWork());

            P2PMessage p2PMessage = newRequestMessage(SelfPeer.getId(), ProtocolEnum.Handshake_Success_V1,
                    peer.serialize());
            pushCompleteMsg(QuicMsg.local(connectionId, ID_Generator.nextId(), p2PMessage));
        });
        return quicConnection;
    }

    /**
     * 收到连接请求帧后调用该方??
     * 通过请求连接帧为节点创建一个连??
     * 被动连接
     * 通过请求连接帧为节点创建一个连接（被动连接）
     * 新增：全维度防护机制，防止恶意请求攻击
     */
    public static QuicConnection handleConnectRequestFrame(QuicFrame quicFrame) {
        // 步骤1：基础校验 - 帧类型判断
        QuicFrameEnum quicFrameEnum = QuicFrameEnum.fromCode(quicFrame.getFrameType());
        if (quicFrameEnum != QuicFrameEnum.CONNECT_REQUEST_FRAME) {
            log.warn("无效的连接请求帧类型：{}", quicFrame.getFrameType());
            return null;
        }

        // 步骤2：获取核心参数
        long connectionId = quicFrame.getConnectionId();
        InetSocketAddress remoteAddress = quicFrame.getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            log.warn("连接请求缺少远程地址，连接ID：{}", connectionId);
            return null;
        }
        if (!allowInboundHandshake()) {
            return null;
        }
        String remoteIp = remoteAddress.getAddress().getHostAddress();

        //发送连接确认帧
        QuicFrame responseFrame = new QuicFrame();
        String rateKey = quicFrame.getRelayPath() == null ? remoteIp
                : "relay:" + quicFrame.getRelayPath().relayConnectionId() + ":" + quicFrame.getRelayPath().peerId();
        try {
            HandshakeReply cached = HANDSHAKE_REPLIES.getIfPresent(connectionId);
            if (cached != null && cached.remote().equals(remoteAddress)
                    && java.util.Objects.equals(cached.route(), quicFrame.getRelayPath())
                    && Arrays.equals(cached.request(), quicFrame.getPayload())) {
                QuicConnection existing = getConnection(connectionId);
                if (existing != null && !existing.isExpired()) {
                    sendFrameWithoutResponse(cached.response());
                }
                return existing;
            }
            // 步骤3：黑名单校验 - 拉黑IP直接拒绝
            if (IP_BLACKLIST.get(rateKey)) {
                log.warn("IP {} 处于黑名单，拒绝连接请求", remoteIp);
                return null;
            }

            // 步骤4：全局连接数校验 - 达到上限拒绝
            if (CONNECTION_MAP.size() >= MAX_GLOBAL_CONNECTIONS) {
                log.warn("全局连接数已达上限，拒绝连接请求，连接ID：{}，远程IP：{}，当前连接数：{}",
                        connectionId, remoteIp, CONNECTION_MAP.size());
                return null;
            }

            // 步骤5：IP请求频率限制 - 每秒请求数超限则拉黑
            AtomicInteger ipRequestCount = IP_REQUEST_LIMIT.get(rateKey);
            int currentIpCount = ipRequestCount.incrementAndGet();
            if (currentIpCount > MAX_REQUEST_PER_IP_PER_SECOND) {
                log.warn("IP请求频率超限，加入黑名单，IP：{}，当前请求数：{}，限制：{}",
                        remoteIp, currentIpCount, MAX_REQUEST_PER_IP_PER_SECOND);
                IP_BLACKLIST.put(rateKey, true); // 封禁经中继转发的发送方时，不能连带封禁共用该中继的其他节点。
                return null;
            }

            // 步骤6：连接ID唯一性校验 - 防止重复连接ID攻击
            if (hasConnection(connectionId)) {
                log.warn("连接请求中的连接ID已存在，连接ID：{}，远程IP：{}", connectionId, remoteIp);
                return null;
            }

            // 步骤7：解析请求体（先解析，再做PeerId校验）
            byte[] payload = quicFrame.getPayload();
            RequestConnect deserialize = RequestConnect.deserialize(payload);
            //验证签名
            boolean b = deserialize.verifyContext(NETWORK, connectionId, quicFrame.getDataId(),
                    RequestConnect.REQUEST, null, System.currentTimeMillis() / 1000);
            if (!b) {
                log.warn("连接请求签名验证失败，连接ID：{}，远程IP：{}", connectionId, remoteIp);
                return null;
            }

            // 版本网络校验（原有逻辑，保留）
            int version = deserialize.getVersion();
            if (version != RequestConnect.VERSION) { // 假设当前版本为1，根据实际业务调整
                log.warn("连接请求版本不支持，连接ID：{}，远程IP：{}，版本：{}",
                        connectionId, remoteIp, version);
                return null;
            }

            // 步骤8：PeerId校验 - 重复PeerId请求限流
            byte[] peerIdBytes = deserialize.getPeerId();
            if (peerIdBytes == null || peerIdBytes.length == 0) {
                log.warn("连接请求缺少PeerId，连接ID：{}，远程IP：{}", connectionId, remoteIp);
                return null;
            }
            String peerIdHexString = bytesToHex(peerIdBytes);
            if (quicFrame.getRelayPath() != null && !peerIdHexString.equals(quicFrame.getRelayPath().peerId())) {
                return null;
            }
            if (P2PSelfNode.isSelfPeerId(peerIdHexString)) {
                log.warn("拒绝本机PeerId发起的连接请求，连接ID：{}，远程IP：{}", connectionId, remoteIp);
                return null;
            }
            AtomicInteger peerRequestCount = PEER_REQUEST_LIMIT.get(peerIdHexString);
            int currentPeerCount = peerRequestCount.incrementAndGet();
            if (currentPeerCount > MAX_REQUEST_PER_PEER_PER_MINUTE) {
                log.warn("PeerId重复连接请求超限，PeerId：{}，远程IP：{}，当前请求数：{}，限制：{}",
                        peerIdHexString, remoteIp, currentPeerCount, MAX_REQUEST_PER_PEER_PER_MINUTE);
                return null;
            }

            synchronized (HANDSHAKE_NONCES) {
                HANDSHAKE_NONCES.cleanUp();
                if (HANDSHAKE_NONCES.estimatedSize() >= 10_000
                        || HANDSHAKE_NONCES.asMap().putIfAbsent(peerIdHexString + bytesToHex(deserialize.getNonce()),
                                true) != null) {
                    return null;
                }
            }

            // 步骤9：高消耗操作（密钥生成），所有校验通过后再执行，避免浪费资源
            byte[] aPublicKey = deserialize.getSharedSecretPubKey();
            byte[][] keyPair = generateCurve25519KeyPair();
            byte[] bPrivateKey = keyPair[0];
            byte[] bPublicKey = keyPair[1];
            byte[] sharedSecret = negotiateSharedSecret(bPrivateKey, aPublicKey, connectionId, remoteAddress);
            if (sharedSecret == null) {
                return null;
            }

            RequestConnect responseConnect = new RequestConnect();
            responseConnect.setPeerId(SelfPeer.getId());
            responseConnect.setVersion(RequestConnect.VERSION);
            responseConnect.bind(NETWORK, connectionId, quicFrame.getDataId(), RequestConnect.RESPONSE,
                    deserialize.getNonce());
            ChainTipSnapshot tip = currentTip();
            if (tip == null) {
                return null;
            }
            responseConnect.setLatestHash(tip.hash());
            responseConnect.setLatestHeight(tip.height());
            responseConnect.setChainWork(tip.chainWork());
            responseConnect.setSharedSecretPubKey(bPublicKey);

            byte[] signatureData = responseConnect.getSignatureData();
            //生成签名
            byte[] signature = Ed25519Signer.fastSign(SelfPeer.getPrivateKey(), signatureData);//签名
            responseConnect.setSignature(signature);

            Arrays.fill(bPrivateKey, (byte) 0);
            // 步骤10：创建连接实例并缓存
            QuicConnection quicConnection = new QuicConnection();
            quicConnection.setPeerId(peerIdHexString);
            quicConnection.setRelayPath(quicFrame.getRelayPath());
            quicConnection.setConnectionId(connectionId);
            quicConnection.setRemoteAddress(remoteAddress);
            quicConnection.setLastSeen(System.currentTimeMillis());//设置为当前时间，两秒后过期
            quicConnection.setOutbound(false);//非主动连??
            quicConnection.setSharedSecret(sharedSecret);
            quicConnection.setSessionCipher(new SessionCipher(sharedSecret, deserialize.getSignatureData(),
                    responseConnect.getSignatureData(), connectionId, false));

            // 缓存连接（原有逻辑，保留）
            registerConnection(peerIdHexString, connectionId, quicConnection);

            //使用线程
            submitHandshakeCallback(() -> {
                byte[] serialize = responseConnect.serialize();
                responseFrame.setConnectionId(connectionId);
                responseFrame.setDataId(quicFrame.getDataId());
                responseFrame.setTotal(1);
                responseFrame.setFrameType(QuicFrameEnum.CONNECT_RESPONSE_FRAME.getCode());
                responseFrame.setPayload(serialize);
                responseFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH + serialize.length);
                responseFrame.setRemoteAddress(remoteAddress);
                responseFrame.setRelayPath(quicFrame.getRelayPath());
                HANDSHAKE_REPLIES.put(connectionId, new HandshakeReply(quicFrame.getPayload().clone(),
                        remoteAddress, quicFrame.getRelayPath(), responseFrame));
                //发送连接确认帧
                sendFrameWithoutResponse(responseFrame);
                log.info("成功为PeerId {}（IP：{}）创建连接，连接ID：{}", peerIdHexString, remoteIp, connectionId);

                Peer peer = new Peer();
                peer.setInetSocketAddress(remoteAddress);
                peer.setId(hexToBytes(peerIdHexString));
                peer.setHeight(deserialize.getLatestHeight());
                peer.setHashHex(bytesToHex(deserialize.getLatestHash()));
                peer.setChainWork(deserialize.getChainWork());

                P2PMessage p2PMessage = newRequestMessage(SelfPeer.getId(), ProtocolEnum.Handshake_Success_V1,
                        peer.serialize());
                pushCompleteMsg(QuicMsg.local(connectionId, ID_Generator.nextId(), p2PMessage));
            });

            return quicConnection;
        } catch (Exception e) {
            log.error("处理连接请求失败，连接ID：{}，远程地址：{}", quicFrame.getConnectionId(), quicFrame.getRemoteAddress(), e);
            return null;
        }
    }

    /**
     * 每秒执行的连接检查逻辑??
     * 1. 先检查连接是否过期（2秒无活跃），过期则直接清理（释放资源、删除索引）
     * 2. 未过期的连接，调用ping方法发送心跳
     * 核心优化：过期连接不发送ping，清理逻辑更严谨，处理空指针边界
     */
    public void checkConnection() {
        long currentTime = System.currentTimeMillis();
        // 使用迭代器遍历，避免ConcurrentModificationException
        Iterator<Map.Entry<Long, QuicConnection>> iterator = CONNECTION_MAP.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<Long, QuicConnection> entry = iterator.next();
            long connectionId = entry.getKey();
            QuicConnection connection = entry.getValue();
            // 空连接直接清理（防御性编程）
            if (connection == null) {
                log.warn("发现空连接记录，已清理，连接ID：{}", connectionId);
                iterator.remove(); // 删除CONNECTION_MAP中的空连??
                continue;
            }
            //连接是否被标记为过期
            if (connection.isExpired()
                    || (connection.getRelayPath() != null && !connection.getRelayPath().isAvailable())) {
                //双删
                connection.release();
                PEER_CONNECTION.remove(connection.getPeerId(), connectionId);
                CONNECTION_MAP.remove(connectionId);
                continue;
            }

            try {
                String peerId = connection.getPeerId();
                long lastSeen = connection.getLastSeen();

                // 步骤1：优先检查连接是否过期（2秒无活跃）
                if (currentTime - lastSeen > CONNECTION_EXPIRE_TIME) {
                    log.info("连接已过期，开始清理，连接ID：{}，PeerId：{}，空闲时间：{}ms",
                            connectionId, peerId, currentTime - lastSeen);
                    // 步骤1.2：释放连接资源（核心，必须执行）
                    connection.release();

                    log.debug("连接资源已释放，连接ID：{}", connectionId);

                    // 步骤1.3：删除节点ID到连接ID的索引（处理peerId为空的边界）
                    if (peerId != null && !peerId.isEmpty()) {
                        PEER_CONNECTION.remove(peerId, connectionId);
                        log.debug("已删除PeerId到连接ID的索引，PeerId：{}，连接ID：{}", peerId, connectionId);
                    } else {
                        log.warn("过期连接缺少PeerId，仅清理连接ID索引，连接ID：{}", connectionId);
                    }

                    // 步骤1.4：删除CONNECTION_MAP中的连接（迭代器安全删除）
                    iterator.remove();
                    log.info("过期连接清理完成，连接ID：{}", connectionId);
                    continue; // 过期连接无需发送ping，直接处理下一条
                }

                // 步骤2：未过期的连接，发送ping心跳
                //如果是出站连接才ping
                connection.ping();
                log.debug("[connection ping] connectionId={} peerId={} outbound={}",
                        connectionId, peerId, connection.isOutbound());
                //清理过期数据
                connection.cleanExpiredData();

            } catch (Exception e) {
                // 单个连接异常不影响整体检查流程，强制清理异常连接
                log.error("检查连接时发生异常，准备强制清理，连接ID：{}", connectionId, e);
                try {
                    // 强制释放连接资源
                    connection.release();

                    // 强制删除节点索引（处理peerId为空）
                    String peerId = connection.getPeerId();
                    if (peerId != null && !peerId.isEmpty()) {
                        PEER_CONNECTION.remove(peerId, connectionId);
                    }

                    // 强制删除CONNECTION_MAP中的连接
                    iterator.remove();
                    log.warn("异常连接已强制清理，连接ID：{}", connectionId);
                } catch (Exception ex) {
                    log.error("强制清理异常连接失败，连接ID：{}", connectionId, ex);
                }
            }
        }
    }

    private void checkConnectionSendData() {
        if (CONNECTION_MAP.isEmpty()) {
            return;
        }
        for (QuicConnection conn : CONNECTION_MAP.values()) {
            // 跳过过期连接，避免无效计??
            if (conn.isExpired()) {
                continue;
            }
            conn.flushDelayedAcks();
            conn.pickUnAckedFrames();
        }
    }

    // 全局出站流量预算
    public static int reserveGlobalOutboundBytes(int requestedBytes) {
        return GLOBAL_OUTBOUND_TRAFFIC.reserve(requestedBytes);
    }

    public static void refundGlobalOutboundBytes(int bytes) {
        GLOBAL_OUTBOUND_TRAFFIC.refund(bytes);
    }

    public static long getGlobalOutboundAvailableBytes() {
        return GLOBAL_OUTBOUND_TRAFFIC.availableBytes();
    }

    public static boolean sendFrameWithoutResponse(QuicFrame quicFrame) {
        if (quicFrame == null) {
            log.error("发送帧失败：QuicFrame为空");
            return false;
        }
        long dataId = quicFrame.getDataId();
        long connectionId = quicFrame.getConnectionId();
        ByteBuf buf = null;
        try {
            if (Global_Channel == null || !Global_Channel.isActive()) {
                throw new IllegalStateException("全局UDP通道不可用");
            }
            // 分配内存并编码帧数据
            byte[] serializedFrame = quicFrame.serialize();
            boolean handshake = quicFrame.getFrameType() == QuicFrameEnum.CONNECT_REQUEST_FRAME.getCode()
                    || quicFrame.getFrameType() == QuicFrameEnum.CONNECT_RESPONSE_FRAME.getCode();
            QuicConnection activeConnection = getConnection(connectionId);
            byte[] datagram = handshake ? SessionCipher.handshake(connectionId, serializedFrame)
                    : activeConnection.getSessionCipher().encrypt(serializedFrame);
            RelayPath route = quicFrame.getRelayPath() != null ? quicFrame.getRelayPath()
                    : activeConnection == null ? null : activeConnection.getRelayPath();
            if (route != null) {
                if (RelayTransport.isRelayFrame(quicFrame.getFrameType())) {
                    return false;
                }
                boolean sent = RelayTransport.send(route, datagram);
                if (sent && activeConnection != null) {
                    activeConnection.recordFrameSent(datagram.length);
                }
                return sent;
            }
            if (datagram.length > PathMtuEstimator.MAX_DATAGRAM_BYTES || !Global_Channel.isWritable()) {
                return false;
            }
            buf = ALLOCATOR.buffer(datagram.length);
            buf.writeBytes(datagram);
            // 构建UDP数据包
            DatagramPacket packet = new DatagramPacket(buf, quicFrame.getRemoteAddress());
            // 异步提交到Netty发送队列
            Global_Channel.writeAndFlush(packet);
            QuicConnection connection = getConnection(connectionId);
            if (connection != null) {
                connection.recordFrameSent(quicFrame.getFrameTotalLength());
            }
            // 返回成功只表示已提交到Netty发送队列，不代表对端一定收到。
            log.debug("[本地发送请求已提交] 数据ID:{} 连接ID:{}", dataId, connectionId);
            return true;
        } catch (Exception e) {
            // 编码或提交异常时释放内存，避免泄漏。
            log.error("[本地发送异常] 数据ID:{} 连接ID:{}，原因：{}",
                    dataId, connectionId, e.getMessage());
            QuicConnection connection = getConnection(connectionId);
            P2PTransferErrorEvents.publish(P2PTransferErrorRecord.now(
                    connection == null ? null : connection.getPeerId(),
                    quicFrame.getRemoteAddress() == null ? "" : quicFrame.getRemoteAddress().toString(),
                    P2PErrorCode.SEND_FRAME_FAILED,
                    e.getMessage(),
                    dataId,
                    connectionId,
                    e));
            if (buf != null) {
                buf.release(); // 手动释放ByteBuf，防止内存泄漏
            }
            return false;
        }
    }

    public static boolean disConnectRemoteByPeerId(String peerId) {
        //断开节点下所有的连接
        if (PEER_CONNECTION.containsKey(peerId)) {
            Long conId = PEER_CONNECTION.remove(peerId);
            if (conId != null) {
                QuicConnection connection = getConnection(conId);
                if (connection != null) {
                    //发送下线帧
                    QuicFrame quicFrame = new QuicFrame();
                    quicFrame.setConnectionId(conId);
                    quicFrame.setDataId(0);
                    quicFrame.setFrameType(QuicFrameEnum.OFF_FRAME.getCode());
                    quicFrame.setTotal(1);
                    quicFrame.setSequence(0);
                    quicFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH);
                    quicFrame.setRemoteAddress(connection.getRemoteAddress());
                    sendFrameWithoutResponse(quicFrame);
                    connection.release();
                    CONNECTION_MAP.remove(conId);
                }
            }
        }
        return true;
    }

    //32字节hex
    public static byte[] staticSendData(String peerId, ProtocolEnum protocol, byte[] request, int time) {
        P2PMessage response = sendRequest(peerId, protocol, request, time);
        try {
            return response == null ? null : response.serialize();
        } catch (IOException e) {
            log.error("Could not encode response for {}", peerId, e);
            return null;
        }
    }

    public static P2PMessage sendRequest(String peerId, ProtocolEnum protocol, byte[] request, int time) {
        String normalizedPeerId = normalizePeerIdForCleanup(peerId);
        QuicConnection peerConnection = null;
        String requestId = null;
        try {
            peerConnection = getConnectionByPeerId(normalizedPeerId);
            if (peerConnection == null || peerConnection.isExpired()) {
                cleanupFailedPeer(normalizedPeerId, protocol, P2PErrorCode.PEER_NOT_CONNECTED,
                        "peer not connected", null, peerConnection);
                return null;
            }

            P2PMessage p2PMessage = newRequestMessage(SelfPeer.getId(), protocol, request);
            byte[] serialize = p2PMessage.serialize();
            CompletableFuture<P2PMessage> responseFuture = null;
            requestId = bytesToHex(p2PMessage.getRequestId());
            if (protocol.isHasResponse()) {
                responseFuture = new CompletableFuture<>();
                MSG_EXPECTED_CONNECTIONS.put(requestId, peerConnection.getConnectionId());
                MSG_RESPONSE_FUTURECACHE.put(requestId, responseFuture);
            }

            peerConnection.sendData(serialize);
            if (!protocol.isHasResponse()) {
                return null;
            }
            P2PMessage response = responseFuture.get(time, TimeUnit.MILLISECONDS);
            if (response == null || response.getData() == null) {
                cleanupFailedPeer(normalizedPeerId, protocol, P2PErrorCode.SEND_MESSAGE_FAILED,
                        "empty response", null, peerConnection);
                return null;
            }
            return response;
        } catch (Exception e) {
            P2PErrorCode errorCode = e instanceof TimeoutException
                    ? P2PErrorCode.CONGESTION_TIMEOUT
                    : P2PErrorCode.SEND_MESSAGE_FAILED;
            cleanupFailedPeer(normalizedPeerId, protocol, errorCode, e.getMessage(), e, peerConnection);
            log.error("静态发送数据失败，PeerId：{}，协议：{}", normalizedPeerId, protocol, e);
            return null;
        } finally {
            if (requestId != null) {
                MSG_RESPONSE_FUTURECACHE.invalidate(requestId);
                MSG_EXPECTED_CONNECTIONS.invalidate(requestId);
            }
        }
    }

    private static String normalizePeerIdForCleanup(String peerId) {
        try {
            return P2PAddressValidator.normalizePeerId(peerId);
        } catch (RuntimeException e) {
            return peerId == null ? "" : peerId.trim().toLowerCase();
        }
    }

    private static void cleanupFailedPeer(String peerId,
            ProtocolEnum protocol,
            P2PErrorCode errorCode,
            String message,
            Throwable cause,
            QuicConnection connection) {
        if (peerId == null || peerId.isBlank()) {
            return;
        }
        QuicConnection activeConnection = connection == null ? getConnectionByPeerId(peerId) : connection;
        long connectionId = activeConnection == null ? 0 : activeConnection.getConnectionId();
        String remoteAddress = activeConnection == null || activeConnection.getRemoteAddress() == null
                ? ""
                : activeConnection.getRemoteAddress().toString();
        String errorMessage = message == null || message.isBlank()
                ? "static send failed: " + protocol
                : message;

        P2PTransferErrorEvents.publish(P2PTransferErrorRecord.now(
                peerId,
                remoteAddress,
                errorCode,
                errorMessage,
                0,
                connectionId,
                cause));
        disConnectRemoteByPeerId(peerId);

        RoutingTable routingTable = ROUTING_TABLE_REF;
        if (routingTable != null) {
            routingTable.removeFailedPeer(peerId, errorMessage);
        }
    }

    /**
     * 获取在线的节点列??
     * 在线节点定义：至少有一个有效的连接（未过期、非失效、有心跳??
     *
     * @return 在线节点ID集合
     */
    public static Set<String> getOnlinePeerIds() {
        Set<String> onlinePeers = new HashSet<>();

        // 遍历所有节点的连接映射
        for (Map.Entry<String, Long> entry : PEER_CONNECTION.entrySet()) {
            String peerId = entry.getKey();
            Long connectionIds = entry.getValue();
            if (connectionIds != null) {
                QuicConnection connection = getConnection(connectionIds);
                if (connection != null && !connection.isExpired()) {
                    onlinePeers.add(peerId);
                } else {
                    //移除
                    PEER_CONNECTION.remove(peerId);
                }
            } else {
                //移除
                PEER_CONNECTION.remove(peerId);
            }
        }

        log.debug("[获取在线节点] 共找到{}个在线节点", onlinePeers.size());
        return onlinePeers;
    }

}
