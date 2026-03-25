package com.bit.coin.p2p.conn;

import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.p2p.peer.Peer;
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
import org.bitcoinj.core.Base58;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bit.coin.blockchain.BlockChainServiceImpl.mainTipBlock;
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

    @Autowired
    private RoutingTable routingTable;

    //节点ID到连接ID
    public static final Map<String, Long> PEER_CONNECTION = new ConcurrentHashMap<>();
    //连接ID到连接实例 连接中有节点ID
    private static final Map<Long, QuicConnection> CONNECTION_MAP  = new ConcurrentHashMap<>();


    //全局UDP通道
    public static DatagramChannel Global_Channel = null;// UDP通道


    // 连接过期时间（ms）：2秒无活跃则清理
    private static final long CONNECTION_EXPIRE_TIME = 2000;

    // ===================== 定时任务线程池 =====================
    // 单线程定时任务池：用于每秒执行连接检查，设置为守护线程避免阻塞应用关闭
    private final ScheduledExecutorService connectionHealthExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "quic-connection-health-thread");
        thread.setDaemon(true);
        return thread;
    });

    // 高频任务池（发送检查）：10ms/次
    private final ScheduledExecutorService connectionSendExecutor = new ScheduledThreadPoolExecutor(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2), // 核心线程数=CPU核心数/2（至少2）
            runnable -> {
                Thread thread = new Thread(runnable, "quic-connection-send-thread");
                thread.setDaemon(true);
                return thread;
            }
    );


    // 全局最大连接数限制（根据服务器性能调整）
    private static final int MAX_GLOBAL_CONNECTIONS = 100;
    // 单IP每秒最大连接请求数（防止短时间高频请求）
    private static final int MAX_REQUEST_PER_IP_PER_SECOND = 10;
    // 单PeerId每分钟最大重复连接请求数
    private static final int MAX_REQUEST_PER_PEER_PER_MINUTE = 5;
    // IP黑名单过期时间（临时拉黑超限IP，单位：分钟）
    private static final int IP_BLACKLIST_EXPIRE_MINUTES = 10;

    // IP请求计数器（原有，优化过期时间为1秒）
    private static final LoadingCache<String, AtomicInteger> IP_REQUEST_LIMIT = CacheBuilder.newBuilder()
            .expireAfterWrite(1, TimeUnit.SECONDS) // 改为1秒过期，精准控制每秒请求数
            .concurrencyLevel(Runtime.getRuntime().availableProcessors())
            .build(new CacheLoader<>() {
                @Override
                public AtomicInteger load(String ip) {
                    return new AtomicInteger(0);
                }
            });

    // PeerId请求计数器（限制单PeerId重复请求）
    private static final LoadingCache<String, AtomicInteger> PEER_REQUEST_LIMIT = CacheBuilder.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .concurrencyLevel(Runtime.getRuntime().availableProcessors())
            .build(new CacheLoader<>() {
                @Override
                public AtomicInteger load(String peerId) {
                    return new AtomicInteger(0);
                }
            });

    // IP黑名单（临时拉黑超限IP）
    private static final LoadingCache<String, Boolean> IP_BLACKLIST = CacheBuilder.newBuilder()
            .expireAfterWrite(IP_BLACKLIST_EXPIRE_MINUTES, TimeUnit.MINUTES)
            .concurrencyLevel(Runtime.getRuntime().availableProcessors())
            .build(new CacheLoader<>() {
                @Override
                public Boolean load(String ip) {
                    return false; // 默认未拉黑
                }
            });


    /**
     * 启动连接检查定时任务：每秒执行一次checkConnection
     */
    @PostConstruct
    public void initConnectionCheckTask() {
        // 低频：1秒/次 心跳+清理
        connectionHealthExecutor.scheduleAtFixedRate(
                this::checkConnection,
                1,
                1,
                TimeUnit.SECONDS
        );

        // 高频：10ms/次 发送检查
        connectionSendExecutor.scheduleAtFixedRate(
                this::checkConnectionSendData,
                0,
                20,
                TimeUnit.MILLISECONDS
        );
    }


    // 销毁方法改造
    @PreDestroy
    public void destroyConnectionCheckExecutor() {
        // 关闭健康检查线程池
        shutdownExecutor(connectionHealthExecutor, "健康检查");
        // 关闭发送检查线程池
        shutdownExecutor(connectionSendExecutor, "发送检查");
    }

    // 抽取通用关闭逻辑
    private void shutdownExecutor(ScheduledExecutorService executor, String name) {
        log.info("开始关闭Quic{}定时任务线程池", name);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                log.warn("强制关闭Quic{}线程池", name);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            log.error("关闭{}线程池时被中断", name, e);
        }
        log.info("Quic{}定时任务线程池已关闭", name);
    }



    /**
     * 通过连接ID获取连接
     */
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

    //获取所有连接
    public static Set<QuicConnection> getAllConnections() {
        return new HashSet<>(CONNECTION_MAP.values());
    }

    //获取所有节点
    public static Set<String> getAllPeers() {
        return PEER_CONNECTION.keySet();
    }


    /**
     * 获取所有连接ID
     */
    public static Set<Long> getAllConnectionIds() {
        return CONNECTION_MAP.keySet();
    }

    /**
     * 清空所有节点和连接
     */
    public static void clearAllConnections() {
        //清空节点到连接
        PEER_CONNECTION.clear();
        //调用所有连接的release方法
        CONNECTION_MAP.values().forEach(QuicConnection::release);
        //清空连接
        CONNECTION_MAP.clear();
    }

    /**
     * 删除连接 同时要删除节点到连接的索引，并停止SendManager的所有发送
     */
    public static void removeConnection(long connectionId) {
        QuicConnection connection = CONNECTION_MAP.remove(connectionId);
        if (connection != null) {
            // 释放连接资源
            connection.release();
            // 删除节点到连接的索引
            PEER_CONNECTION.remove(connection.getPeerId());
        }
    }


    /**
     * 通过连接ID获取节点
     */
    public static String getPeerId(long connectionId) {
        return CONNECTION_MAP.get(connectionId).getPeerId();
    }


    /**
     * 通过自解释型地址来连接节点 /ip4/127.0.0.1/quic/8334/p2p/CrACARmu8BpDDBz9XknpqgZzMZRgratLMdEk7C76avaU
     * @param multiAddressString
     * @return
     */
    public static QuicConnection connectRemoteByAddr(String multiAddressString){
        //不为空
        if (multiAddressString == null || multiAddressString.isEmpty()) {
            return null;
        }
        MultiAddress multiAddress = new MultiAddress(multiAddressString);
        //仅仅支持IPV4 和 QUIC协议
        if (!multiAddress.getIpType().equals("ip4") || !multiAddress.getProtocol().equals(MultiAddress.Protocol.QUIC)) {
            return null;
        }
        String peerIdHex = multiAddress.getPeerIdHex();//节点ID
        if (PEER_CONNECTION.containsKey(peerIdHex)) {
            return getConnection(PEER_CONNECTION.get(peerIdHex));
        }
        String ipAddress = multiAddress.getIpAddress();
        int port = multiAddress.getPort();
        return connectRemote(ipAddress,port);
    }

    //根据节点ID获取连接 hexID
    public static QuicConnection getConnectionByPeerId(String peerId) {
        Long l = PEER_CONNECTION.get(peerId);
        if (l!=null){
            return getConnection(l);
        }
        return null;
    }

    //Base58Id
    public static QuicConnection connectRemoteByBase58Id(String base58Id) {
        byte[] decode = Base58.decode(base58Id);
        String s = bytesToHex(decode);
        return getConnection(PEER_CONNECTION.get(s));
    }


    private static QuicConnection connectRemote(String ipAddress, int port) {
        try {
            InetSocketAddress remoteAddress = new InetSocketAddress(ipAddress, port);
            long conId = ID_Generator.nextId();
            long dataId = ID_Generator.nextId();
            //连接请求帧
            QuicFrame reqQuicFrame = new QuicFrame();
            reqQuicFrame.setConnectionId(conId);//生成连接ID
            reqQuicFrame.setDataId(dataId);
            reqQuicFrame.setTotal(0);
            reqQuicFrame.setFrameType(QuicFrameEnum.CONNECT_REQUEST_FRAME.getCode());
            RequestConnect requestConnect = new RequestConnect();
            requestConnect.setPeerId(SelfPeer.getId());
            requestConnect.setLatestHash(mainTipBlock.getHash());
            requestConnect.setLatestHeight(mainTipBlock.getHeight());
            requestConnect.setChainWork(mainTipBlock.getChainWork());
            byte[][] AKeys = generateCurve25519KeyPair();
            byte[] aPrivateKey = AKeys[0];
            byte[] aPublicKey = AKeys[1];
            requestConnect.setVersion(1);
            requestConnect.setSharedSecretPubKey(aPublicKey);
            byte[] signatureData = requestConnect.getSignatureData();
            //生成签名
            byte[] signature = Ed25519Signer.fastSign(SelfPeer.getPrivateKey(),signatureData);//签名
            requestConnect.setSignature(signature);
            byte[] serialize = requestConnect.serialize();
            reqQuicFrame.setPayload(serialize);
            reqQuicFrame.setRemoteAddress(remoteAddress);
            reqQuicFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH+serialize.length);
            //发送连接确认帧
            sendFrameWithoutResponse(reqQuicFrame);
            //存OBJECT_RESPONSE_FUTURECACHE
            CompletableFuture<QuicFrame> responseFuture = new CompletableFuture<>();
            QUICFRAME_RESPONSE_FUTURECACHE.put(dataId,responseFuture);
            //从未来缓存中拿到响应帧 5秒超时
            QuicFrame quicFrame = responseFuture.get(5, TimeUnit.SECONDS);
            if (quicFrame!=null){
                return handleConnectResponseFrame(quicFrame,aPrivateKey,aPublicKey);
            }
            return null;
        }catch (Exception e){
            log.error("连接远程节点失败",e);
            return null;
        }
    }


    //处理连接响应帧 直接根据帧携带的内容创建一个连接
    public static QuicConnection handleConnectResponseFrame(QuicFrame quicFrame,byte[] aPrivateKey,byte[] aPublicKey) {
        // 步骤1：基础校验 - 帧类型判断
        QuicFrameEnum quicFrameEnum = QuicFrameEnum.fromCode(quicFrame.getFrameType());
        if (quicFrameEnum != QuicFrameEnum.CONNECT_RESPONSE_FRAME) {
            log.warn("无效的连接响应帧类型：{}", quicFrame.getFrameType());
            return null;
        }
        long connectionId = quicFrame.getConnectionId();
        InetSocketAddress remoteAddress = quicFrame.getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            log.warn("无效的远程地址，拒绝连接请求");
            return null;
        }
        String remoteIp = remoteAddress.getAddress().getHostAddress();
        if (hasConnection(connectionId)) {
            log.warn("连接ID {} 已存在，拒绝重复连接请求（IP：{}）", connectionId, remoteIp);
            return null;
        }

        // 步骤7：解析请求体（先解析，再做PeerId校验）
        byte[] payload = quicFrame.getPayload();
        RequestConnect deserialize = RequestConnect.deserialize(payload);
        //验证签名
        boolean b = deserialize.verifySignature();
        if (!b){
            log.warn("签名验证失败，拒绝IP {} 的连接请求", remoteIp);
            return null;
        }

        // 版本/网络校验（原有逻辑，保留）
        int version = deserialize.getVersion();
        if (version != 1) { // 假设当前版本为1，根据实际业务调整
            log.warn("版本不匹配（请求版本：{}），拒绝IP {} 的连接请求", version, remoteIp);
            return null;
        }

        // 步骤8：PeerId校验 - 重复PeerId请求限流
        byte[] peerIdBytes = deserialize.getPeerId();
        if (peerIdBytes == null || peerIdBytes.length == 0) {
            log.warn("无效的PeerId，拒绝IP {} 的连接请求", remoteIp);
            return null;
        }
        String peerIdHexString = bytesToHex(peerIdBytes);
        byte[] sharedSecretPubKey = deserialize.getSharedSecretPubKey();
        byte[] sharedSecret = generateSharedSecret(aPrivateKey, sharedSecretPubKey);

        // 步骤10：创建连接实例并缓存
        QuicConnection quicConnection = new QuicConnection();
        quicConnection.setPeerId(peerIdHexString);
        quicConnection.setConnectionId(connectionId);
        quicConnection.setRemoteAddress(remoteAddress);
        quicConnection.setLastSeen(System.currentTimeMillis());//设置为当前时间 两秒后过期
        quicConnection.setOutbound(true);//主动连接
        quicConnection.setSharedSecret(sharedSecret);

        PEER_CONNECTION.put(peerIdHexString, connectionId);
        CONNECTION_MAP.put(connectionId, quicConnection);

        //使用线程
        new Thread(() -> {
            Peer peer = new Peer();
            peer.setInetSocketAddress(remoteAddress);
            peer.setId(hexToBytes(peerIdHexString));
            peer.setHeight(deserialize.getLatestHeight());
            peer.setHashHex(bytesToHex(deserialize.getLatestHash()));
            peer.setChainWork(deserialize.getChainWork());

            P2PMessage p2PMessage = newRequestMessage(SelfPeer.getId(), ProtocolEnum.Handshake_Success_V1, peer.serialize());
            byte[] serialize1 = null;
            try {
                serialize1 = p2PMessage.serialize();
                QuicMsg quicMsg = new QuicMsg();
                quicMsg.setConnectionId(connectionId);
                quicMsg.setDataId(ID_Generator.nextId());
                quicMsg.setData(serialize1);
                pushCompleteMsg(quicMsg);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).start();
        return quicConnection;
    }



    /**
     * 收到连接请求帧后调用该方法
     * 通过请求连接帧为节点创建一个连接
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
            log.warn("无效的远程地址，拒绝连接请求");
            return null;
        }
        String remoteIp = remoteAddress.getAddress().getHostAddress();

        //发送连接确认帧
        QuicFrame responseFrame = new QuicFrame();
        try {
            // 步骤3：黑名单校验 - 拉黑IP直接拒绝
            if (IP_BLACKLIST.get(remoteIp)) {
                log.warn("IP {} 处于黑名单，拒绝连接请求", remoteIp);
                return null;
            }

            // 步骤4：全局连接数校验 - 达到上限拒绝
            if (CONNECTION_MAP.size() >= MAX_GLOBAL_CONNECTIONS) {
                log.warn("全局连接数已达上限({})，拒绝IP {} 的连接请求", MAX_GLOBAL_CONNECTIONS, remoteIp);
                return null;
            }

            // 步骤5：IP请求频率限流 - 每秒请求数超限则拉黑
            AtomicInteger ipRequestCount = IP_REQUEST_LIMIT.get(remoteIp);
            int currentIpCount = ipRequestCount.incrementAndGet();
            if (currentIpCount > MAX_REQUEST_PER_IP_PER_SECOND) {
                log.warn("IP {} 每秒请求数超限({}/{})，拉黑{}分钟",
                        remoteIp, currentIpCount, MAX_REQUEST_PER_IP_PER_SECOND, IP_BLACKLIST_EXPIRE_MINUTES);
                IP_BLACKLIST.put(remoteIp, true); // 加入黑名单
                return null;
            }

            // 步骤6：连接ID唯一性校验 - 防止重复连接ID攻击
            if (hasConnection(connectionId)) {
                log.warn("连接ID {} 已存在，拒绝重复连接请求（IP：{}）", connectionId, remoteIp);
                return null;
            }

            // 步骤7：解析请求体（先解析，再做PeerId校验）
            byte[] payload = quicFrame.getPayload();
            RequestConnect deserialize = RequestConnect.deserialize(payload);
            //验证签名
            boolean b = deserialize.verifySignature();
            if (!b){
                log.warn("签名验证失败，拒绝IP {} 的连接请求", remoteIp);
                return null;
            }

            // 版本/网络校验（原有逻辑，保留）
            int version = deserialize.getVersion();
            if (version != 1) { // 假设当前版本为1，根据实际业务调整
                log.warn("版本不匹配（请求版本：{}），拒绝IP {} 的连接请求", version, remoteIp);
                return null;
            }

            // 步骤8：PeerId校验 - 重复PeerId请求限流
            byte[] peerIdBytes = deserialize.getPeerId();
            if (peerIdBytes == null || peerIdBytes.length == 0) {
                log.warn("无效的PeerId，拒绝IP {} 的连接请求", remoteIp);
                return null;
            }
            String peerIdHexString = bytesToHex(peerIdBytes);
            AtomicInteger peerRequestCount = PEER_REQUEST_LIMIT.get(peerIdHexString);
            int currentPeerCount = peerRequestCount.incrementAndGet();
            if (currentPeerCount > MAX_REQUEST_PER_PEER_PER_MINUTE) {
                log.warn("PeerId {} 每分钟请求数超限({}/{})，拒绝连接请求",
                        peerIdHexString, currentPeerCount, MAX_REQUEST_PER_PEER_PER_MINUTE);
                return null;
            }

            // 步骤9：高消耗操作（密钥生成）- 所有校验通过后再执行，避免浪费资源
            byte[] aPublicKey = deserialize.getSharedSecretPubKey();
            byte[][] keyPair = generateCurve25519KeyPair();
            byte[] bPrivateKey = keyPair[0];
            byte[] bPublicKey = keyPair[1];
            byte[] sharedSecret = generateSharedSecret(bPrivateKey, aPublicKey);

            // 步骤10：创建连接实例并缓存
            QuicConnection quicConnection = new QuicConnection();
            quicConnection.setPeerId(peerIdHexString);
            quicConnection.setConnectionId(connectionId);
            quicConnection.setRemoteAddress(remoteAddress);
            quicConnection.setLastSeen(System.currentTimeMillis());//设置为当前时间 两秒后过期
            quicConnection.setOutbound(false);//非主动连接
            quicConnection.setSharedSecret(sharedSecret);

            // 缓存连接（原有逻辑，保留）
            PEER_CONNECTION.put(peerIdHexString, connectionId);
            CONNECTION_MAP.put(connectionId, quicConnection);

            //使用线程
            new Thread(() -> {
                RequestConnect responseConnect = new RequestConnect();
                responseConnect.setPeerId(SelfPeer.getId());
                responseConnect.setVersion(1);
                responseConnect.setLatestHash(mainTipBlock.getHash());
                responseConnect.setLatestHeight(mainTipBlock.getHeight());
                responseConnect.setChainWork(mainTipBlock.getChainWork());
                responseConnect.setSharedSecretPubKey(bPublicKey);

                byte[] signatureData = responseConnect.getSignatureData();
                //生成签名
                byte[] signature = Ed25519Signer.fastSign(SelfPeer.getPrivateKey(),signatureData);//签名
                responseConnect.setSignature(signature);

                byte[] serialize = responseConnect.serialize();
                responseFrame.setConnectionId(connectionId);
                responseFrame.setDataId(quicFrame.getDataId());
                responseFrame.setTotal(1);
                responseFrame.setFrameType(QuicFrameEnum.CONNECT_RESPONSE_FRAME.getCode());
                responseFrame.setPayload(serialize);
                responseFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH+serialize.length);
                responseFrame.setRemoteAddress(remoteAddress);
                //发送连接确认帧
                sendFrameWithoutResponse(responseFrame);
                log.info("成功为PeerId {}（IP：{}）创建连接，连接ID：{}", peerIdHexString, remoteIp, connectionId);


                Peer peer = new Peer();
                peer.setInetSocketAddress(remoteAddress);
                peer.setId(hexToBytes(peerIdHexString));
                peer.setHeight(deserialize.getLatestHeight());
                peer.setHashHex(bytesToHex(deserialize.getLatestHash()));
                peer.setChainWork(deserialize.getChainWork());

                P2PMessage p2PMessage = newRequestMessage(SelfPeer.getId(), ProtocolEnum.Handshake_Success_V1, peer.serialize());
                byte[] serialize1 = null;
                try {
                    serialize1 = p2PMessage.serialize();
                    QuicMsg quicMsg = new QuicMsg();
                    quicMsg.setConnectionId(connectionId);
                    quicMsg.setDataId(ID_Generator.nextId());
                    quicMsg.setData(serialize1);
                    pushCompleteMsg(quicMsg);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).start();

            return quicConnection;
        } catch (Exception e) {
            log.error("处理连接请求失败（IP：{}）", remoteIp, e);
            return null;
        }
    }





    /**
     * 每秒执行的连接检查逻辑：
     * 1. 先检查连接是否过期（2秒无活跃），过期则直接清理（释放资源+删除索引）
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
                log.warn("连接ID:{} 对应的连接实例为null，直接清理", connectionId);
                iterator.remove(); // 删除CONNECTION_MAP中的空连接
                continue;
            }
            //连接是否被标记为过期
            if (connection.isExpired()) {
                //双删除
                PEER_CONNECTION.remove(connection.getPeerId());
                CONNECTION_MAP.remove(connectionId);
                continue;
            }

            try {
                String peerId = connection.getPeerId();
                long lastSeen = connection.getLastSeen();

                // 步骤1：优先检查连接是否过期（2秒无活跃）
                if (currentTime - lastSeen > CONNECTION_EXPIRE_TIME) {
                    log.info("连接ID:{}（PeerId:{}）已过期（最后活跃时间：{}ms前），开始清理",
                            connectionId, peerId, currentTime - lastSeen);
                    // 步骤1.2：释放连接资源（核心，必须执行）
                    connection.release();

                    log.debug("连接ID:{} 资源已释放", connectionId);

                    // 步骤1.3：删除节点ID到连接ID的索引（处理peerId为空的边界）
                    if (peerId != null && !peerId.isEmpty()) {
                        PEER_CONNECTION.remove(peerId);
                        log.debug("PeerId:{} 对应的连接ID:{} 索引已删除", peerId, connectionId);
                    } else {
                        log.warn("连接ID:{} 的PeerId为空，跳过节点索引删除", connectionId);
                    }

                    // 步骤1.4：删除CONNECTION_MAP中的连接（迭代器安全删除）
                    iterator.remove();
                    log.info("连接ID:{}（PeerId:{}）已完成全量清理", connectionId, peerId);
                    continue; // 过期连接无需发送ping，直接处理下一个
                }

                // 步骤2：未过期的连接，发送ping心跳
                //如果是出站连接才ping
                if (connection.isOutbound()) {
                    connection.ping();
                    log.debug("已向连接ID:{}（PeerId:{}）发送ping心跳（最后活跃：{}ms前）",
                            connectionId, peerId, currentTime - lastSeen);
                }
                //清理过期数据
                connection.cleanExpiredData();


            } catch (Exception e) {
                // 单个连接异常不影响整体检查流程，强制清理异常连接
                log.error("处理连接ID:{}的ping/过期检查时发生异常，强制清理", connectionId, e);
                try {
                    // 强制释放连接资源
                    connection.release();

                    // 强制删除节点索引（处理peerId为空）
                    String peerId = connection.getPeerId();
                    if (peerId != null && !peerId.isEmpty()) {
                        PEER_CONNECTION.remove(peerId);
                    }

                    // 强制删除CONNECTION_MAP中的连接
                    iterator.remove();
                    log.warn("异常连接ID:{}（PeerId:{}）已强制清理", connectionId, peerId);
                } catch (Exception ex) {
                    log.error("强制清理异常连接ID:{}失败", connectionId, ex);
                }
            }
        }
    }

    private void checkConnectionSendData() {
        if (CONNECTION_MAP.isEmpty()) {
            return;
        }
        for (QuicConnection conn : CONNECTION_MAP.values()) {
            // 跳过过期连接，避免无效计算
            if (conn.isExpired()) {
                continue;
            }
            conn.pickUnAckedFrames();
        }
    }





    //控制发送速度 没秒最多2M数据
    public static void sendFrameWithoutResponse(QuicFrame quicFrame) {
        if (quicFrame == null) {
            log.error("[无响应发送] 待发送帧为null，直接返回");
            return;
        }
        long dataId = quicFrame.getDataId();
        long connectionId = quicFrame.getConnectionId();
        ByteBuf buf = null;
        try {
            // 2. 分配内存并编码帧数据
            buf = ALLOCATOR.buffer();
            quicFrame.encode(buf);
            // 3. 构建UDP数据包
            DatagramPacket packet = new DatagramPacket(buf, quicFrame.getRemoteAddress());
            // 4. 异步发送
            Global_Channel.writeAndFlush(packet);
            // 注意：此处返回true仅表示「发送请求已提交到Netty发送队列」，不代表发送成功
            log.debug("[本地发送请求已提交] 数据ID:{} 连接ID:{}", dataId, connectionId);
        } catch (Exception e) {
            // 5. 编码异常处理（释放内存，避免泄漏）
            log.error("[本地发送异常] 数据ID:{} 连接ID:{}，原因：{}",
                    dataId, connectionId, e.getMessage());
            //打印异常原因
            log.error("异常原因：{}",e.getMessage());
            if (buf != null) {
                buf.release(); // 手动释放ByteBuf，防止内存泄漏
            }
        }
    }



    public static boolean disConnectRemoteByPeerId(String peerId){
        //断开节点下所有的连接
        if (PEER_CONNECTION.containsKey(peerId)){
            Long conId = PEER_CONNECTION.remove(peerId);
            if (conId!=null) {
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
                }
            }
        }
        return true;
    }





    //32字节hex
    public static byte[] staticSendData(String peerId, ProtocolEnum protocol, byte[] request, int time)  {
        try {
            QuicConnection peerConnection = getConnectionByPeerId(peerId);
            if (peerConnection!=null){
                P2PMessage p2PMessage = newRequestMessage(SelfPeer.getId(), protocol, request);
                byte[] serialize = p2PMessage.serialize();
                CompletableFuture<QuicMsg> responseFuture = new CompletableFuture<>();
                MSG_RESPONSE_FUTURECACHE.put(bytesToHex(p2PMessage.getRequestId()), responseFuture);
                //且协议有返回值
                peerConnection.sendData(serialize);
                if (protocol.isHasResponse()){
                    return responseFuture.get(time, TimeUnit.MILLISECONDS).getData();
                }else {
                    return null;
                }
            }else {
                return null;
            }
        }catch (Exception e){
            log.error("发送数据失败",e);
            return null;
        }
    }

    /**
     * 获取在线的节点列表
     * 在线节点定义：至少有一个有效的连接（未过期、非失效、有心跳）
     *
     * @return 在线节点ID集合
     */
    public static Set<String> getOnlinePeerIds() {
        Set<String> onlinePeers = new HashSet<>();

        // 遍历所有节点的连接映射
        for (Map.Entry<String, Long> entry : PEER_CONNECTION.entrySet()) {
            String peerId = entry.getKey();
            Long connectionIds = entry.getValue();
            if (connectionIds!=null){
                QuicConnection connection = getConnection(connectionIds);
                if (connection!=null){
                    onlinePeers.add(peerId);
                }else {
                    //移除
                    PEER_CONNECTION.remove(peerId);
                }
            }else {
                //移除
                PEER_CONNECTION.remove(peerId);
            }
        }

        log.info("[获取在线节点] 共找到{}个在线节点，节点列表：{}", onlinePeers.size(), onlinePeers);
        return onlinePeers;
    }

}
