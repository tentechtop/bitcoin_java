package com.bit.coin.p2p.quic;


import com.bit.coin.config.CommonConfig;
import com.bit.coin.config.SystemConfig;
import com.bit.coin.database.DataBase;
import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.p2p.peer.Peer;
import com.bit.coin.p2p.protocol.NetworkHandshake;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolEnum;

import com.bit.coin.utils.MultiAddress;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bit.coin.blockchain.BlockChainServiceImpl.mainTipBlock;
import static com.bit.coin.config.CommonConfig.*;
import static com.bit.coin.config.SystemConfig.SelfKey;
import static com.bit.coin.p2p.protocol.P2PMessage.newRequestMessage;
import static com.bit.coin.p2p.quic.QuicConstants.*;
import static com.bit.coin.utils.ECCWithAESGCM.generateCurve25519KeyPair;
import static com.bit.coin.utils.ECCWithAESGCM.generateSharedSecret;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;


/**
 * 连接管理器（全局连接池）
 */
@Slf4j
@Component
public class QuicConnectionManager {



    //节点ID - > 连接ID 公钥的hex是节点ID
    public static final Map<String, Long> PeerConnect = new HashMap<>();
    public static DatagramChannel Global_Channel = null;// UDP通道
    private static final Map<Long, QuicConnection> CONNECTION_MAP  = new HashMap<>();


    @Autowired
    private SystemConfig systemConfig;

    @Autowired
    private CommonConfig commonConfig;

    @Autowired
    private RoutingTable routingTable;

    @PostConstruct
    private void init(){
        DataBase dataBase = systemConfig.getDataBase();
    }




    //mian函数
    public static void main(String[] args) {
        byte[][] bytes = generateCurve25519KeyPair();
        System.out.println(bytes[0].length);
        System.out.println(bytes[1].length);
    }




    //获取节点的连接
    public static QuicConnection getPeerConnection(String peerId) {
        if (PeerConnect.containsKey(peerId)){
            Long connectionId = PeerConnect.get(peerId);
            if (connectionId!=null){
                return QuicConnectionManager.getConnection(connectionId);
            }else {
                return null;
            }
        }else {
            return null;
        }
    }


    //给节点添加一个连接
    public static void addPeerConnect(String peerId, long connectionId) {
        if (PeerConnect.containsKey(peerId)){
            //包含
            Long oldConnectId = PeerConnect.get(peerId);
            if (oldConnectId.equals(connectionId)){
                //相等
                log.info("节点连接已经存在");
            }else {
                //不相等 移除之前的连接替换为新的连接
                QuicConnection connection = getConnection(oldConnectId);
                if (connection!=null){
                    connection.release();
                }
                //添加为新的连接
                PeerConnect.put(peerId,connectionId);
            }
        }else {
            //不包含 判断连接是否存在
            QuicConnection connection = getConnection(connectionId);
            if (connection!=null){
                PeerConnect.put(peerId,connectionId);
            }else {
                log.info("连接是空的,无法为节点添加");
            }
        }
    }

    //删除节点的连接
    public static void removePeerConnect(String peerId, long connectionId) {
        if (peerId!=null){
            if (PeerConnect.containsKey(peerId)){
                if (PeerConnect.get(peerId).equals(connectionId)){
                    PeerConnect.remove(peerId);
                    //释放连接
                    QuicConnection connection = getConnection(connectionId);
                    if (connection!=null){
                        connection.release();
                    }
                }
            }
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
        for (Map.Entry<String, Long> entry : PeerConnect.entrySet()) {
            String peerId = entry.getKey();
            Long connectionIds = entry.getValue();
            if (connectionIds!=null){
                QuicConnection connection = getConnection(connectionIds);
                if (connection!=null){
                    onlinePeers.add(peerId);
                }else {
                    //移除
                    PeerConnect.remove(peerId);
                }
            }else {
                //移除
                PeerConnect.remove(peerId);
            }
        }

        log.info("[获取在线节点] 共找到{}个在线节点，节点列表：{}", onlinePeers.size(), onlinePeers);
        return onlinePeers;
    }

    private static boolean isConnectionValid(QuicConnection connection) {
        //连接不为空 不过期
        return connection != null && !connection.isExpired();
    }


    public static QuicConnection connectRemoteByAddr(String multiAddressString)
            throws ExecutionException, InterruptedException, TimeoutException, IOException {
        //不为空
        if (multiAddressString == null || multiAddressString.isEmpty()) {
            return null;
        }
        MultiAddress multiAddress = new MultiAddress(multiAddressString);
        //仅仅支持IPV4 和 QUIC协议
        if (!multiAddress.getIpType().equals("ip4") || !multiAddress.getProtocol().equals(MultiAddress.Protocol.QUIC)) {
            return null;
        }
        String peerId = multiAddress.getPeerId();
        QuicConnection peerConnection = getPeerConnection(peerId);
        if (peerConnection != null){
            log.info("当前节点已经存在连接");
            return peerConnection;
        }
        log.info("节点ID{}",peerId);
        String peerIdHex = multiAddress.getPeerIdHex();
        String ipAddress = multiAddress.getIpAddress();
        int port = multiAddress.getPort();
        InetSocketAddress remoteAddress = new InetSocketAddress(ipAddress, port);
        return connectRemote(remoteAddress);
    }


    public static boolean disConnectRemoteByPeerId(String peerId){
        //断开节点下所有的连接
        if (PeerConnect.containsKey(peerId)){
            //发送节点下线帧
            Long conId = PeerConnect.remove(peerId);
            if (conId!=null){
                QuicConnection connection = getConnection(conId);
                if (connection!=null){
                    //发送下线帧
                    QuicFrame quicFrame = QuicFrame.acquire();
                    try {
                        quicFrame.setConnectionId(conId);
                        quicFrame.setDataId(0);
                        quicFrame.setFrameType(QuicFrameEnum.OFF_FRAME.getCode());
                        quicFrame.setTotal(1);
                        quicFrame.setSequence(0);
                        quicFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH);
                        quicFrame.setRemoteAddress(connection.getRemoteAddress());
                        ByteBuf buf = QuicConstants.ALLOCATOR.buffer();
                        quicFrame.encode(buf);
                        DatagramPacket packet = new DatagramPacket(buf, quicFrame.getRemoteAddress());
                        Global_Channel.writeAndFlush(packet);
                        connection.release();
                    }finally {
                        quicFrame.release();
                    }
                }
            }
        }
        return true;
    }


    /**
     * 与指定的节点建立连接
     * 为该连接建立心跳任务
     */
    public static QuicConnection connectRemote(InetSocketAddress remoteAddress)
            throws ExecutionException, InterruptedException, TimeoutException, IOException {
        // 1. 参数校验
        if (remoteAddress == null) {
            log.error("远程地址不能为空");
            return null;
        }
        long conId = generator.nextId();
        long dataId = generator.nextId();
        QuicConnection existingConn = getConnection(conId);
        if (existingConn != null) {
            if (!existingConn.isExpired()) {
                // 情况1：连接存在且有效（未过期），直接返回现有连接，终止后续握手
                log.info("与远程地址{}的连接已存在且有效，连接ID：{}，无需重复建立", remoteAddress, conId);
                return existingConn;
            } else {
                // 情况2：连接存在但已过期，先清理旧连接（停止心跳、移除缓存），再重新建立
                log.warn("与远程地址{}的连接已过期，连接ID：{}，清理后重新建立", remoteAddress, conId);
                existingConn.stopHeartbeat(); // 停止旧心跳
                removeConnection(conId);      // 从连接缓存移除（需确保你有此移除方法，无则新增）
                removePeerConnect(existingConn.getPeerId(), conId); // 从节点-连接映射移除
            }
        }

        //给远程地址发送连接请求请求帧 等待回复 回复成功后建立连接
        QuicFrame reqQuicFrame = QuicFrame.acquire();//已经释放
        reqQuicFrame.setConnectionId(conId);//生成连接ID
        reqQuicFrame.setDataId(dataId);
        reqQuicFrame.setTotal(0);
        reqQuicFrame.setFrameType(QuicFrameEnum.CONNECT_REQUEST_FRAME.getCode());
        //连接包 包含节点ID 加密公钥

        NetworkHandshake networkHandshake = new NetworkHandshake();
        networkHandshake.setNodeId(self.getId());
        byte[][] AKeys = SelfKey;
        byte[] aPrivateKey = AKeys[0];
        byte[] aPublicKey = AKeys[1];
        //序列化耗时
        networkHandshake.setSharedSecret(aPublicKey);
        networkHandshake.setLatestHash(mainTipBlock.getHash());
        networkHandshake.setLatestHeight(mainTipBlock.getHeight());
        networkHandshake.setChainWork(mainTipBlock.getChainWork());
        log.info("测试工作总量{}",mainTipBlock.getChainWork().toString());


        byte[] serialize = networkHandshake.serialize();
        int length = serialize.length;
        log.info("握手长度！{}",length);
        reqQuicFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH+length);
        reqQuicFrame.setPayload(serialize);//携带节点ID 这样被连接的节点就能立马让P2P节点上线
        reqQuicFrame.setRemoteAddress(remoteAddress);
        QuicFrame resQuicFrame = sendFrame(reqQuicFrame);//这里会得到一个入站连接
        log.info("响应帧{}",resQuicFrame);
        reqQuicFrame.release();
        if (resQuicFrame != null){
            QuicConnection connection = getConnection(conId);
            byte[] payload = resQuicFrame.getPayload();
            //解析
            NetworkHandshake deserialize = NetworkHandshake.deserialize(payload);
            byte[] nodeId = deserialize.getNodeId();
            byte[] bPublicKey = deserialize.getSharedSecret();

            //协商
            byte[] sharedSecret = generateSharedSecret(aPrivateKey, bPublicKey);
            log.info("协商共享密钥是:{}", bytesToHex(sharedSecret));
            connection.setPeerId(bytesToHex(nodeId));
            connection.stopHeartbeat();
            connection.setUDP(true);
            connection.setOutbound(true);
            connection.setExpired(false);
            connection.setLastSeen(System.currentTimeMillis());
            connection.setSharedSecret(sharedSecret);
            connection.startHeartbeat();
            addConnection(conId, connection);
            addPeerConnect(bytesToHex(nodeId),conId);

            log.info("释放帧");

            //通知路由表添加节点
            Peer peer = new Peer();
            peer.setInetSocketAddress(resQuicFrame.getRemoteAddress());
            peer.setId(nodeId);
            peer.setHeight(deserialize.getLatestHeight());
            peer.setHashHex(bytesToHex(deserialize.getLatestHash()));
            peer.setChainWork(deserialize.getChainWork());
            log.info("握手工作总量{}",deserialize.getChainWork().toString());

            P2PMessage p2PMessage = newRequestMessage(CommonConfig.getSelf().getId(), ProtocolEnum.Handshake_Success_V1, peer.serialize());
            byte[] serialize1 = p2PMessage.serialize();
            QuicMsg quicMsg = new QuicMsg();
            quicMsg.setConnectionId(conId);
            quicMsg.setDataId(dataId);
            quicMsg.setData(serialize1);
            pushCompleteMsg(quicMsg);

            resQuicFrame.release();
            return connection;
        }
        return null;
    }

    /**
     * 与指定远程节点断开连接
     * UDP是无连接的，停止心跳并清理资源即可
     */
    public static void disconnectRemote(String peerId) {
        QuicConnection connection = QuicConnectionManager.getPeerConnection(peerId);
        if (connection != null) {
            connection.stopHeartbeat();
            connection.release();
        }
    }

    public static QuicConnection createOrGetConnection(DatagramChannel channel, InetSocketAddress local, InetSocketAddress remote, long connectionId) {
        //获取或者创建一个远程连接
        QuicConnection quicConnection = QuicConnectionManager.getConnection(connectionId);
        if (quicConnection == null){
            quicConnection = new QuicConnection();
            quicConnection.setConnectionId(connectionId);
            quicConnection.setRemoteAddress(remote);
            quicConnection.setUDP(true);
            quicConnection.setOutbound(false);//非主动连接
            quicConnection.startHeartbeat();


            addConnection(connectionId, quicConnection);
        }
        quicConnection.updateLastSeen();
        return quicConnection;
    }


    /**
     * 根据帧数据创建发送数据实例，并自动维护连接-数据ID映射关系
     * @param frameData  帧数据（ByteBuf类型，Quic帧原始数据）
     * @return 初始化完成的SendQuicData实例（已加入缓存和映射）
     * @throws IllegalArgumentException 参数异常时抛出
     */
    public static ReceiveQuicData createReceiveDataByFrame(QuicFrame frameData) {
        // 1. 严格参数校验
        if (frameData == null || !frameData.isValid()) {
            throw new IllegalArgumentException("frameData cannot be null or empty");
        }
        // 3. 初始化发送数据实例（结合Quic常量配置）
        ReceiveQuicData receiveQuicData = new ReceiveQuicData();
        receiveQuicData.setDataId(frameData.getDataId());
        receiveQuicData.setConnectionId(frameData.getConnectionId());
        receiveQuicData.setTotal(frameData.getTotal());
        receiveQuicData.setRemoteAddress(frameData.getRemoteAddress());

        // 4. 线程安全地维护缓存和映射关系
        receiveMap.put(frameData.getDataId(), receiveQuicData);
        connectReceiveMap.put(frameData.getConnectionId(), frameData.getDataId());
        return receiveQuicData;
    }


    /**
     * 获取连接
     */
    public static QuicConnection getConnection(long connectionId) {
        return CONNECTION_MAP.get(connectionId);
    }

    /**
     * 获取第一个连接
     */
    public static QuicConnection getFirstConnection() {
        return CONNECTION_MAP.values().stream().findFirst().orElse(null);
    }


    /**
     * 获取连接数
     */
    public static int getConnectionCount() {
        return CONNECTION_MAP.size();
    }

    /**
     * 添加连接
     */
    public static void addConnection(long connectionId, QuicConnection connection) {
        CONNECTION_MAP.put(connectionId, connection);
        log.info("[连接添加] 连接ID:{}", connectionId);
    }

    /**
     * 移除连接
     */
    public static void removeConnection(long connectionId) {
        QuicConnection removed = CONNECTION_MAP.remove(connectionId);
        if (removed != null) {
            log.info("[连接移除] 连接ID:{}", connectionId);
        }
    }

    /**
     * 检查连接是否存在
     */
    public static boolean hasConnection(long connectionId) {
        return CONNECTION_MAP.get(connectionId) != null;
    }

    /**
     * 获取所有连接ID
     */
    public static Set<Long> getAllConnectionIds() {
        return CONNECTION_MAP.keySet();
    }

    /**
     * 清空所有连接
     */
    public static void clearAllConnections() {
        int count = CONNECTION_MAP.size();
        CONNECTION_MAP.clear();
        log.info("[清空连接] 清除了{}个连接", count);
    }


    /**
     * 发送连接请求帧（核心实现）
     * 本地错误：20ms重试1次，最多3次；网络错误：100ms重试1次，最多3次；失败返回null
     */
    public static QuicFrame sendFrame(QuicFrame quicFrame) {
        // 1. 基础参数定义
        final int LOCAL_RETRY_MAX = 3;    // 本地错误最大重试次数
        final long LOCAL_RETRY_INTERVAL = 50; // 本地重试间隔（ms）
        final int NET_RETRY_MAX = 2;      // 网络错误最大重试次数
        final long NET_RETRY_INTERVAL = 200;  // 网络重试间隔（ms）
        final long RESPONSE_TIMEOUT = 500;   // 单次响应等待超时（ms）
        InetSocketAddress remoteAddress = quicFrame.getRemoteAddress();
        long connectionId = quicFrame.getConnectionId();

        long dataId = quicFrame.getDataId();
        CompletableFuture<Object> responseFuture = new CompletableFuture<>();
        QUIC_RESPONSE_FUTURECACHE.put(dataId, responseFuture);

        // 2. 网络错误重试循环（外层：处理端到端无响应）
        for (int netRetry = 0; netRetry < NET_RETRY_MAX; netRetry++) {
            // 每次网络重试生成新的dataId（避免旧响应干扰）

            // 3. 本地错误重试（内层：确保数据能发出去）
            AtomicInteger localRetryCount = new AtomicInteger(0);
            boolean localSendSuccess = sendWithLocalRetry(quicFrame, localRetryCount, LOCAL_RETRY_MAX, LOCAL_RETRY_INTERVAL);

            if (!localSendSuccess) {
                log.error("[请求] 连接ID:{} 远程地址:{} 本地发送重试{}次失败，终止网络重试",
                        connectionId, remoteAddress, LOCAL_RETRY_MAX);
                QUIC_RESPONSE_FUTURECACHE.asMap().remove(dataId);
                break;
            }


            // 4. 本地发送成功，等待响应（判定网络错误）
            try {
                // 等待响应，超时则触发下一次网络重试
                Object result = responseFuture.get(RESPONSE_TIMEOUT, TimeUnit.MILLISECONDS);
                if (result instanceof QuicFrame responseFrame) {
                    log.debug("[请求] 连接ID:{} 远程地址:{} 第{}次网络重试成功，收到响应",
                            connectionId, remoteAddress, netRetry );
                    // 清理缓存，返回响应帧
                    QUIC_RESPONSE_FUTURECACHE.asMap().remove(dataId);
                    return responseFrame;
                }
            } catch (Exception e) {
                // 响应超时/异常 → 网络错误，准备下一次重试
                log.warn("[请求] 连接ID:{} 远程地址:{} 第{}次网络重试超时（{}ms），原因：{}",
                        connectionId, remoteAddress, netRetry + 1, RESPONSE_TIMEOUT, e.getMessage());
                QUIC_RESPONSE_FUTURECACHE.asMap().remove(dataId);

                // 非最后一次网络重试 → 等待间隔后重试
                if (netRetry < NET_RETRY_MAX - 1) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(NET_RETRY_INTERVAL);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("[请求] 网络重试间隔被中断", ie);
                        break;
                    }
                }
            }
        }

        // 所有重试耗尽，返回null
        log.error("[请求] 连接ID:{} 远程地址:{} 本地/网络重试均失败，返回null",
                quicFrame.getConnectionId(), remoteAddress);
        return null;
    }

    /**
     * 本地发送重试工具方法（处理本地错误，20ms间隔重试）
     * @param frame 待发送帧
     * @param retryCount 已重试次数（原子类，避免并发问题）
     * @param maxRetry 最大重试次数
     * @param interval 重试间隔（ms）
     * @return 本地发送是否成功
     */
    private static boolean sendWithLocalRetry(QuicFrame frame, AtomicInteger retryCount, int maxRetry, long interval) {
        // 递归出口：重试次数耗尽
        if (retryCount.get() >= maxRetry) {
            return false;
        }

        // 构建发送数据包
        ByteBuf buf = null;
        try {
            buf = ALLOCATOR.buffer();
            frame.encode(buf);
            DatagramPacket packet = new DatagramPacket(buf, frame.getRemoteAddress());
            // 同步发送（阻塞直到发送完成）
            ChannelFuture future = Global_Channel.writeAndFlush(packet).sync();
            if (future.isSuccess()) {
                log.debug("[本地发送成功] 数据ID:{} 连接ID:{} 重试次数:{}",
                        frame.getDataId(), frame.getConnectionId(), retryCount.get());
                return true;
            } else {
                // 本地发送失败，重试
                retryCount.incrementAndGet();
                log.warn("[本地发送失败] 数据ID:{} 连接ID:{} 重试次数:{}，原因：{}",
                        frame.getDataId(), frame.getConnectionId(), retryCount.get(), future.cause().getMessage());

                // 等待重试间隔后递归重试
                TimeUnit.MILLISECONDS.sleep(interval);
                return sendWithLocalRetry(frame, retryCount, maxRetry, interval);
            }
        } catch (Exception e) {
            // 编码/发送异常 → 本地错误，重试
            retryCount.incrementAndGet();
            log.error("[本地发送异常] 数据ID:{} 连接ID:{} 重试次数:{}，原因：{}",
                    frame.getDataId(), frame.getConnectionId(), retryCount.get(), e.getMessage());

            // 等待重试间隔后递归重试
            try {
                TimeUnit.MILLISECONDS.sleep(interval);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.error("[本地重试间隔被中断]", ie);
                return false;
            }
            return sendWithLocalRetry(frame, retryCount, maxRetry, interval);
        }
    }


    /**
     * 根据远程地址(IP+端口)生成唯一的连接ID(long类型)
     * 拼接IP字节(IPv4=4字节/IPv6=16字节) + 端口int字节(4字节)，MD5哈希后取前8字节转long
     * 保证同一remoteAddress生成唯一conId，兼容IPv4/IPv6
     * @param remoteAddress 远程地址
     * @return 唯一的连接ID
     */
    private static long generateConIdByRemoteAddress(InetSocketAddress remoteAddress) {
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            throw new IllegalArgumentException("远程地址或IP不能为空");
        }
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            // 1. 写入IP字节数组（IPv4=4字节，IPv6=16字节，自动兼容）
            bos.write(remoteAddress.getAddress().getAddress());
            // 2. 写入端口字节（int=4字节，大端序，保证跨平台一致性）
            bos.write(ByteBuffer.allocate(4).putInt(remoteAddress.getPort()).array());
            byte[] ipPortBytes = bos.toByteArray();

            // 3. MD5哈希：生成16字节唯一摘要，避免IP+端口直接转long的长度不足问题
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] md5Bytes = md5.digest(ipPortBytes);

            // 4. 取MD5前8字节转long（刚好匹配conId的long类型，8字节）
            ByteBuffer buffer = ByteBuffer.wrap(md5Bytes, 0, 8);
            return buffer.getLong();
        } catch (IOException e) {
            throw new RuntimeException("拼接IP+端口字节失败", e);
        } catch (NoSuchAlgorithmException e) {
            // MD5是JDK自带算法，不会抛出此异常，兜底处理
            throw new RuntimeException("MD5哈希算法不存在", e);
        }
    }

}
