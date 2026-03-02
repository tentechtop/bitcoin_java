package com.bit.coin.p2p.quic;


import com.bit.coin.config.CommonConfig;
import com.bit.coin.p2p.peer.Peer;
import com.bit.coin.p2p.protocol.NetworkHandshake;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolEnum;

import com.bit.coin.p2p.quic.control.GlobalDataTransmissionController;
import com.bit.coin.utils.ECCWithAESGCM;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;


import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.bit.coin.blockchain.BlockChainServiceImpl.mainTipBlock;
import static com.bit.coin.config.CommonConfig.self;
import static com.bit.coin.config.SystemConfig.SelfKey;
import static com.bit.coin.p2p.protocol.P2PMessage.newRequestMessage;
import static com.bit.coin.p2p.quic.QuicConnectionManager.*;
import static com.bit.coin.p2p.quic.QuicConstants.*;
import static com.bit.coin.p2p.quic.SendQuicData.buildFromFullData;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static com.bit.coin.utils.SerializeUtils.hexToBytes;


@Slf4j
@Data
public class QuicConnection {

    private String peerId;// 节点ID
    private long connectionId;// 连接ID
    private Channel tcpChannel;// TCP通道
    private boolean isUDP = true;//默认使用可靠UDP
    private  volatile InetSocketAddress remoteAddress; // 远程地址,支持连接迁移
    // 心跳/检查任务相关
    private TimerTask connectionTask; // 统一命名：出站=心跳任务，入站=检查任务
    private volatile Timeout connectionTimeout; // 保存定时任务引用，用于取消
    private byte[] sharedSecret;//共享加密密钥

    //全局超时时间
    public long GLOBAL_TIMEOUT_MS = 5000;
    // 帧重传间隔
    public long RETRANSMIT_INTERVAL_MS = 1000;
    //帧最大负载
    public int MAX_FRAME_PAYLOAD = 1400 ;

    //是否过期
    private volatile boolean expired = false;
    //最后访问时间
    private volatile long lastSeen = System.currentTimeMillis();
    //true 是出站连接 false是入站连接
    private boolean isOutbound;





    /**
     * 启动连接任务（差异化：出站=主动心跳，入站=仅过期检查）
     */
    public void startHeartbeat() {
        // 避免重复启动
        if (connectionTask != null) {
            log.warn("连接任务已在运行，连接ID:{}（出站:{}）", connectionId, isOutbound);
            return;
        }

        // 分支1：出站连接 - 主动心跳（400ms）+ 过期检查
        if (isOutbound) {
            connectionTask = new TimerTask() {
                @Override
                public void run(Timeout timeout) throws Exception {
                    long now = System.currentTimeMillis();
                    // 先检查是否过期
                    if (now - lastSeen > CONNECTION_EXPIRE_TIMEOUT) {
                        markAsExpired();
                        return;
                    }

                    // 连接已失效则终止
                    if (remoteAddress == null || Global_Channel == null || expired) {
                        log.info("出站连接已关闭/过期，停止心跳，连接ID:{}", connectionId);
                        markAsExpired();
                        return;
                    }
                    QuicFrame pingFrame = QuicFrame.acquire();
                    try {
                        // 主动发送PING帧
                        long dataId = generator.nextId();
                        pingFrame.setConnectionId(connectionId);
                        pingFrame.setDataId(dataId);
                        pingFrame.setFrameType(QuicFrameEnum.PING_FRAME.getCode());
                        pingFrame.setTotal(1);
                        pingFrame.setSequence(0);
                        pingFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH);
                        pingFrame.setRemoteAddress(remoteAddress);

                        QuicFrame quicFrame = sendFrame(pingFrame);
                        if (quicFrame == null) {
                            log.warn("出站连接PING帧发送失败，连接ID:{} 时间{}", connectionId,System.currentTimeMillis());
                        } else {
                            log.debug("出站连接PING帧发送成功，连接ID:{}，PONG帧:{}", connectionId, quicFrame);
                            updateLastSeen(); // 接收PONG更新活动时间
                            quicFrame.release();
                        }
                    } catch (Exception e) {
                        log.error("[出站心跳异常] 连接ID:{}", connectionId, e);
                    }finally {
                        pingFrame.release();
                    }

                    // 继续调度下一次心跳（未过期才继续）
                    if (!timeout.isCancelled() && !expired) {
                        connectionTimeout = HEARTBEAT_TIMER.newTimeout(this, OUTBOUND_HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
                    }
                }
            };
            // 启动出站心跳（400ms间隔）
            connectionTimeout = HEARTBEAT_TIMER.newTimeout(connectionTask, OUTBOUND_HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
            log.info("出站连接心跳任务启动，连接ID:{}，心跳间隔:{}ms，过期阈值:{}ms",
                    connectionId, OUTBOUND_HEARTBEAT_INTERVAL, CONNECTION_EXPIRE_TIMEOUT);

            // 分支2：入站连接 - 仅过期检查（1000ms），不主动发PING
        } else {
            connectionTask = new TimerTask() {
                @Override
                public void run(Timeout timeout) throws Exception {
                    long now = System.currentTimeMillis();
                    // 仅检查过期，不发送任何帧
                    if (now - lastSeen > CONNECTION_EXPIRE_TIMEOUT) {
                        markAsExpired();
                        return;
                    }

                    // 连接已失效则终止
                    if (remoteAddress == null || expired) {
                        log.info("入站连接已关闭/过期，停止检查，连接ID:{}", connectionId);
                        markAsExpired();
                        return;
                    }
                    log.debug("入站连接过期检查通过，连接ID:{}（最后活动时间:{}ms前）",
                            connectionId, now - lastSeen);

                    // 继续调度下一次检查（未过期才继续）
                    if (!timeout.isCancelled() && !expired) {
                        connectionTimeout = HEARTBEAT_TIMER.newTimeout(this, CONNECTION_EXPIRE_TIMEOUT, TimeUnit.MILLISECONDS);
                    }
                }
            };
            // 启动入站过期检查（1000ms间隔）
            connectionTimeout = HEARTBEAT_TIMER.newTimeout(connectionTask, CONNECTION_EXPIRE_TIMEOUT, TimeUnit.MILLISECONDS);
            log.info("入站连接过期检查任务启动，连接ID:{}，检查间隔:{}ms，过期阈值:{}ms",
                    connectionId, CONNECTION_EXPIRE_TIMEOUT, CONNECTION_EXPIRE_TIMEOUT);
        }
    }

    /**
     * 停止连接任务（心跳/检查）
     */
    public void stopHeartbeat() {
        // 真正取消定时任务，避免内存泄漏
        if (connectionTimeout != null) {
            connectionTimeout.cancel();
            connectionTimeout = null;
        }
        connectionTask = null;
        log.info("连接任务已停止，连接ID:{}（出站:{}）", connectionId, isOutbound);
    }


    /**
     * 更新最后访问时间（所有帧交互时调用）
     */
    public void updateLastSeen() {
        this.lastSeen = System.currentTimeMillis();
        this.expired = false; // 有活动则重置过期状态
    }

    /**
     * 标记连接过期（核心方法）
     */
    public void markAsExpired() {
        release();
    }


    //基于连接发送一次完整的数据  对方对数据中的每一个帧都回复ACK 表示数据发送成功
    synchronized public boolean sendData(byte[] data) throws InterruptedException {
        long dataId = generator.nextId();
        SendQuicData sendQuicData = buildFromFullData(connectionId, dataId, data, remoteAddress, MAX_FRAME_PAYLOAD);
        addSendDataToConnect(connectionId, sendQuicData);
        sendQuicData.sendAllFrames();
        return true;
    }





    //释放该连接
    public void release() {
        if (expired) {
            return;
        }
        expired = true;
        log.warn("连接已过期... 时间{}",System.currentTimeMillis());
        // 1. 移除连接管理器的核心引用（重中之重）
        QuicConnectionManager.removeConnection(connectionId);
        // 2. 彻底清理定时任务（避免Timer持有对象引用）
        if (connectionTimeout != null) {
            connectionTimeout.cancel();
            connectionTimeout = null;
        }
        connectionTask = null; // 清空任务引用
        // 3. 清理Netty通道相关引用（避免IO线程持有）
        if (tcpChannel != null) {
            tcpChannel.close().addListener(future -> {
                tcpChannel = null; // 通道关闭后再置空，避免空指针
            });
        }

        // 4. 清空成员变量引用（辅助GC，减少引用链）
        remoteAddress = null;

        //清除所有正在发送的数据
        deleteAllSendDataByConnectId(connectionId);
        deleteAllReceiveDataByConnectId(connectionId);
        //下线节点连接
        removePeerConnect(peerId,connectionId);
    }







    //发送二进制数据 二进制到QuicData
    //处理数据帧 返回ACK帧
    private void handleDataFrame(QuicFrame quicFrame) {
        log.info("处理数据帧");

        boolean receiveDataExistInConnect = isReceiveDataExistInConnect(quicFrame.getConnectionId(), quicFrame.getDataId());
        if (receiveDataExistInConnect){
            //存在就直接获取到
            ReceiveQuicData receiveQuicData = getReceiveDataByConnectIdAndDataId(quicFrame.getConnectionId(), quicFrame.getDataId());
            receiveQuicData.handleFrame(quicFrame);
        }else {
            //是否处理过
            Long ifPresent = R_CACHE.getIfPresent(quicFrame.getDataId());
            if (ifPresent!=null){
                log.debug("数据已经处理过了");
                //回复ALL_ACK
                long connectionId = getConnectionId();
                long dataId = quicFrame.getDataId();
                QuicFrame ackFrame =  QuicFrame.acquire();
                ackFrame.setConnectionId(connectionId);
                ackFrame.setDataId(dataId);
                ackFrame.setSequence(quicFrame.getSequence()); // ACK帧序列号固定为0
                ackFrame.setTotal(1); // ACK帧不分片
                ackFrame.setFrameType(QuicFrameEnum.DATA_ACK_FRAME.getCode()); // 自定义：ACK帧类型
                ackFrame.setRemoteAddress(getRemoteAddress());
                // 计算总长度：固定头部 + 载荷长度
                int totalLength = QuicFrame.FIXED_HEADER_LENGTH;
                ackFrame.setFrameTotalLength(totalLength);
                ackFrame.setPayload(null);
                ByteBuf buf = QuicConstants.ALLOCATOR.buffer();
                ackFrame.encode(buf);
                DatagramPacket packet = new DatagramPacket(buf, ackFrame.getRemoteAddress());
                Global_Channel.writeAndFlush(packet);
                ackFrame.release();
            }else {
                ReceiveQuicData receiveDataByFrame = createReceiveDataByFrame(quicFrame);
                receiveDataByFrame.handleFrame(quicFrame);
            }
        }
    }

    //处理ACK帧


    public void handleFrame(QuicFrame quicFrame) {
        switch (QuicFrameEnum.fromCode(quicFrame.getFrameType())) {
            case DATA_FRAME:
               handleDataFrame(quicFrame);
               break;
            case DATA_ACK_FRAME:
                handleACKFrame(quicFrame);
                break;
/*            case ALL_ACK_FRAME:
                handleALLACKFrame(quicFrame);
                break;*/
            case BATCH_ACK_FRAME:
                handleBatchACKFrame(quicFrame);
                break;
            case PING_FRAME:
                handlePingFrame(quicFrame);
                break;
            case PONG_FRAME:
                handlePongFrame(quicFrame);
                break;
            case OFF_FRAME:
                handleOffFrame(quicFrame);
                break;
            case CONNECT_REQUEST_FRAME:
                handleConnectRequestFrame(quicFrame);
                break;
            case CONNECT_RESPONSE_FRAME:
                handleConnectResponseFrame(quicFrame);
            default:
                break;
        }
    }



    private void handleBatchACKFrame(QuicFrame quicFrame) {
        log.debug("处理批量ACK");
        long connectionId1 = quicFrame.getConnectionId();
        long dataId = quicFrame.getDataId();
        SendQuicData sendQuicData = getSendDataByConnectIdAndDataId(connectionId1, dataId);
        // 标记该序列号为已确认
        if (sendQuicData != null){
            byte[] payload = quicFrame.getPayload();
            sendQuicData.batchAck(payload);
        }
        quicFrame.release();
    }

    private void handleALLACKFrame(QuicFrame quicFrame) {
        log.info("收到ALL_ACK");
        long connectionId1 = quicFrame.getConnectionId();
        long dataId = quicFrame.getDataId();
        int sequence = quicFrame.getSequence();
        SendQuicData sendQuicData = getSendDataByConnectIdAndDataId(connectionId1, dataId);
        // 标记该序列号为已确认
        if (sendQuicData != null){
            sendQuicData.allReceived();
        }
        quicFrame.release();
    }

    private void handlePongFrame( QuicFrame quicFrame) {
        log.debug("处理PONG帧{}",quicFrame);
        //更新访问时间
        QuicMsg quicMsg = new QuicMsg();
        quicMsg.setConnectionId(0);
        quicMsg.setDataId(0);
        quicMsg.setData(hexToBytes(peerId));
        pushCompleteMsg(quicMsg);

        CompletableFuture<Object> ifPresent = QUIC_RESPONSE_FUTURECACHE.asMap().remove(quicFrame.getDataId());
        if (ifPresent != null) {
            ifPresent.complete(quicFrame);
        }
    }

    private void handleConnectRequestFrame(QuicFrame quicFrame) {
        QuicFrame acquire = QuicFrame.acquire();//已经释放
        try {
            long conId = quicFrame.getConnectionId();
            //发送连接响应帧
            long dataId = quicFrame.getDataId();
            byte[] payload = quicFrame.getPayload();

            //解析成握手数据
            NetworkHandshake deserialize = NetworkHandshake.deserialize(payload);

            byte[] nodeId = deserialize.getNodeId();
            String peerId = bytesToHex(nodeId);
            byte[] aPublicKey = deserialize.getSharedSecret();
            //校验握手数据 检查网络和软件 如果一致就返回协商后的信息 并上线节点

            byte[][] BKeys = SelfKey;
            byte[] bPrivateKey = BKeys[0];
            byte[] bPublicKey = BKeys[1];
            byte[] sharedSecret = ECCWithAESGCM.generateSharedSecret(bPrivateKey, aPublicKey);
            //深度复制
            byte[] sharedSecretCopy = new byte[sharedSecret.length];
            System.arraycopy(sharedSecret, 0, sharedSecretCopy, 0, sharedSecret.length);
            setSharedSecret(sharedSecretCopy);

            log.info("共享加密密钥对sharedSecret: {}", bytesToHex(sharedSecret));

            NetworkHandshake networkHandshake = new NetworkHandshake();
            networkHandshake.setNodeId(self.getId());
            networkHandshake.setSharedSecret(bPublicKey);
            networkHandshake.setLatestHash(mainTipBlock.getHash());
            networkHandshake.setLatestHeight(mainTipBlock.getHeight());
            networkHandshake.setChainWork(mainTipBlock.getChainWork());

            byte[] serialize = networkHandshake.serialize();
            acquire.setConnectionId(conId);//生成连接ID
            acquire.setDataId(dataId);
            acquire.setTotal(1);
            acquire.setFrameType(QuicFrameEnum.CONNECT_RESPONSE_FRAME.getCode());
            acquire.setPayload(serialize);
            acquire.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH+serialize.length);

            ByteBuf buffer = ALLOCATOR.buffer();
            acquire.encode(buffer);
            DatagramPacket datagramPacket = new DatagramPacket(buffer, quicFrame.getRemoteAddress());
            setPeerId(peerId);
            setSharedSecret(sharedSecret);
            Global_Channel.writeAndFlush(datagramPacket).addListener(future -> {
                if (!future.isSuccess()) {
                    log.error("[连接响应发送失败] 节点ID:{}", conId, future.cause());
                } else {
                    log.info("[连接响应发送成功] 节点ID:{}", conId);
                    //将该节点上线
                    addPeerConnect(peerId,conId);
                    //添加一个消息让系统 将节点加入到路由表
                    //添加或者更新路由表中的节点
                    //完整的节点消息 交给路由表处理
                    Peer peer = new Peer();
                    InetSocketAddress remoteAddress = quicFrame.getRemoteAddress();
                    peer.setInetSocketAddress(remoteAddress);
                    peer.setId(nodeId);
                    peer.setHeight(deserialize.getLatestHeight());
                    peer.setHashHex(bytesToHex(deserialize.getLatestHash()));
                    peer.setChainWork(deserialize.getChainWork());
                    log.info("测试工作总量{}",deserialize.getChainWork().toString());


                    P2PMessage p2PMessage = newRequestMessage(CommonConfig.getSelf().getId(), ProtocolEnum.Handshake_Success_V1, peer.serialize());
                    byte[] serialize1 = p2PMessage.serialize();
                    QuicMsg quicMsg = new QuicMsg();
                    quicMsg.setConnectionId(getConnectionId());
                    quicMsg.setDataId(dataId);
                    quicMsg.setData(serialize1);
                    pushCompleteMsg(quicMsg);

                    //被连接的一方也可以

                }
            });
        }catch (Exception e){
            log.error("解析出错",e);
        }finally {
            acquire.release();
        }
    }


    private void handleConnectResponseFrame(QuicFrame quicFrame) {
        log.info("处理连接响应{}",quicFrame);
        //核销掉这个数据
        CompletableFuture<Object> ifPresent = QUIC_RESPONSE_FUTURECACHE.asMap().remove(quicFrame.getDataId());
        if (ifPresent != null) {
            ifPresent.complete(quicFrame);
        }
    }



    private void handleOffFrame(QuicFrame quicFrame) {
        log.info("处理连接下线");
        //取消心跳关闭连接
        byte[] payload = quicFrame.getPayload();
        String peerId = Base58.encode(payload);
        QuicConnection connection = getConnection(getConnectionId());
        connection.release();
        if (PeerConnect.containsKey(peerId)){
            Long conId = PeerConnect.remove(peerId);
            //释放连接
            QuicConnection quicConnection = getConnection(conId);
            if (quicConnection!=null){
                quicConnection.release();
            }
        }
        quicFrame.release();
    }



    private void handlePingFrame(QuicFrame quicFrame) {
        QuicFrame pongFrame = QuicFrame.acquire();//已经释放
        try {
            log.debug("处理ping");
            //更新访问时间
            QuicMsg quicMsg = new QuicMsg();
            quicMsg.setConnectionId(0);
            quicMsg.setDataId(0);
            quicMsg.setData(hexToBytes(peerId));
            pushCompleteMsg(quicMsg);

            //回复PONG帧
            pongFrame.setConnectionId(connectionId);
            pongFrame.setDataId(quicFrame.getDataId()); // 临时数据ID
            pongFrame.setFrameType(QuicFrameEnum.PONG_FRAME.getCode()); // PING_FRAME类型
            pongFrame.setTotal(1); // 单帧无需分片
            pongFrame.setSequence(0);
            pongFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH); // 无载荷
            pongFrame.setRemoteAddress(quicFrame.getRemoteAddress());

            //编码
            ByteBuf buf = QuicConstants.ALLOCATOR.buffer();
            pongFrame.encode(buf);
            DatagramPacket packet = new DatagramPacket(buf, pongFrame.getRemoteAddress());
            Global_Channel.writeAndFlush(packet).addListener(future -> {
                if (!future.isSuccess()) {
                    log.info("回复失败{}", remoteAddress);
                } else {
                    log.debug("回复成功{}", remoteAddress);
                }
            });
        }finally {
            pongFrame.release();
            quicFrame.release();
        }
    }

    private void handleACKFrame( QuicFrame quicFrame) {
        long connectionId1 = quicFrame.getConnectionId();
        long dataId = quicFrame.getDataId();
        int sequence = quicFrame.getSequence();
        SendQuicData sendQuicData = getSendDataByConnectIdAndDataId(connectionId1, dataId);
        // 标记该序列号为已确认
        if (sendQuicData != null){
            sendQuicData.onAckReceived(sequence);
        }
        quicFrame.release();
    }




}
