package com.bit.coin.p2p.conn;

import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolEnum;
import com.bit.coin.p2p.production.P2PErrorCode;
import com.bit.coin.p2p.production.P2PTransferErrorEvents;
import com.bit.coin.p2p.production.P2PTransferErrorRecord;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import static com.bit.coin.config.SystemConfig.SelfPeer;
import static com.bit.coin.p2p.conn.FrameQueueElement.PRIORITY_NORMAL;
import static com.bit.coin.p2p.conn.QuicConnectionManager.disConnectRemoteByPeerId;
import static com.bit.coin.p2p.conn.QuicConnectionManager.sendFrameWithoutResponse;
import static com.bit.coin.p2p.conn.QuicConstants.*;
import static com.bit.coin.p2p.protocol.P2PMessage.newRequestMessage;
import static com.bit.coin.utils.ECCWithAESGCM.aesGcmDecrypt;
import static com.bit.coin.utils.ECCWithAESGCM.aesGcmEncrypt;
import static com.bit.coin.utils.ECCWithAESGCM.deriveAesKey;
import static com.bit.coin.utils.HexByteArraySerializer.bytesToHex;

/**
 *【已发送未确认队列】
 *               ↙          ↓          ↘
 * 超时重传（数据超时重传）  ACK确认（删除）  数据过期（强制删除）
 *
 * 接收方接收窗口是1024*1024字节
 * 发送1024*1024字节 并立即扣减远程窗口1024*1024 此时窗口是0 等待接收方回补窗口
 * 接收方收到1024*1024 扣减本地1024*1024 当数据处理完成或者过期时回补1024*1024字节 （当数据大于1024*1024字节时 要提前消费 并回补)
 */
@Slf4j
@Data
public class QuicConnection implements QuicConnectionInterFace{
    private String peerId;// 节点ID
    private long connectionId;// 连接ID
    private  volatile InetSocketAddress remoteAddress;  // 远程地址,支持连接迁移
    private byte[] sharedSecret;//共享加密密钥
    private volatile boolean expired = false;//是否过期
    private volatile long lastSeen = System.currentTimeMillis();//最后访问时间
    private boolean isOutbound;// 是否出站连接


    // 单帧最大负载由路径MTU估算器动态收敛。
    public volatile int MAX_FRAME_PAYLOAD = PathMtuEstimator.DEFAULT_DATAGRAM_BYTES - QuicFrame.FIXED_HEADER_LENGTH;
    private final PathMtuEstimator pathMtuEstimator = new PathMtuEstimator();
    private volatile long lastActivityTime = lastSeen;

    // 待发送数据：每个连接对应一个发送队列。
    private ConcurrentHashMap<Long, SendQuicData> unAckedDataMap = new ConcurrentHashMap<>();
    // 数据顺序：新数据加入队尾，重传数据可插入队首。
    private final OrderedConcurrentSet unAckedDataSet = new OrderedConcurrentSet();

    //接收数据
    private ConcurrentHashMap<Long, ReceiveQuicData> receiveDataMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicInteger> receiveWindowReservations = new ConcurrentHashMap<>();


    /** 坏数据数据阈值（超过则标记连接失效） */
    private static final int EXPIRED_DATA_THRESHOLD = 3;
    /** 坏数据 发送坏数据+接收坏数据 */
    private volatile int expiredDataCount = 0;


    // 发送完成记录：键为数据ID，值为发送完成时间或过期时间
    private Map<Long, Long> sendMap = new ConcurrentHashMap<>();
    // 接收完成记录：键为数据ID，值为接收完成时间或过期时间
    private Map<Long, Long> receiveMap = new ConcurrentHashMap<>();


    //发送方「可发送量」= 接收方通告的「剩余窗口」 - 发送方「已发送未确认的数据量」
    /** 单连接接收窗口大小，覆盖当前最大分片集合并给重组留出余量。 */
    public static final int INIT_WINDOW_SIZE = 16 * 1024 * 1024;

    // ========== 拆分锁：发送方/接收方各自独立 ==========
    /** 发送方窗口操作锁：保护远端接收窗口与已发送未确认字节数 */
    private final ReentrantLock senderLock = new ReentrantLock();
    /** 接收方窗口操作锁：保护本地接收窗口与待处理字节数 */
    private final ReentrantLock receiverLock = new ReentrantLock();


    // 发送方视角：远程接收方通过确认帧通告的剩余窗口。
    private final AtomicInteger remoteRwnd = new AtomicInteger(INIT_WINDOW_SIZE);

    // 接收方视角：本地剩余接收窗口。
    private final AtomicInteger localRwnd = new AtomicInteger(INIT_WINDOW_SIZE);
    private final LocalCongestionWindow congestionWindow = new LocalCongestionWindow();
    private final AtomicLong sentBytes = new AtomicLong(0);
    private final AtomicLong receivedBytes = new AtomicLong(0);
    private final ConcurrentHashMap<Long, Long> pingSendTimeMap = new ConcurrentHashMap<>();
    private volatile long lastRttMillis = -1;
    private volatile long lastStatsTime = System.currentTimeMillis();
    private volatile long lastStatsSentBytes = 0;
    private volatile long lastStatsReceivedBytes = 0;
    private volatile double sendBytesPerSecond = 0;
    private volatile double receiveBytesPerSecond = 0;


    private final BoundedFrameSendQueue frameSendQueue = new BoundedFrameSendQueue(this.unAckedDataMap);

    private void touchActivity(long now) {
        lastActivityTime = now;
    }

    private void touchReceived(long now) {
        lastSeen = now;
        lastActivityTime = now;
    }

    public void recordFrameSent(int bytes) {
        touchActivity(System.currentTimeMillis());
        if (bytes > 0) {
            sentBytes.addAndGet(bytes);
        }
    }

    public void recordFrameReceived(int bytes) {
        touchReceived(System.currentTimeMillis());
        if (bytes > 0) {
            receivedBytes.addAndGet(bytes);
        }
    }

    public boolean isExpectedRemoteAddress(InetSocketAddress incomingAddress) {
        if (incomingAddress == null) {
            return false;
        }
        InetSocketAddress expectedAddress = remoteAddress;
        if (expectedAddress == null) {
            remoteAddress = incomingAddress;
            return true;
        }
        return expectedAddress.equals(incomingAddress);
    }

    public synchronized QuicConnectionStats snapshotStats(long now) {
        long elapsedMillis = Math.max(1, now - lastStatsTime);
        long currentSentBytes = sentBytes.get();
        long currentReceivedBytes = receivedBytes.get();
        sendBytesPerSecond = (currentSentBytes - lastStatsSentBytes) * 1000.0 / elapsedMillis;
        receiveBytesPerSecond = (currentReceivedBytes - lastStatsReceivedBytes) * 1000.0 / elapsedMillis;
        lastStatsTime = now;
        lastStatsSentBytes = currentSentBytes;
        lastStatsReceivedBytes = currentReceivedBytes;

        return new QuicConnectionStats(
                peerId,
                connectionId,
                remoteAddress == null ? "" : remoteAddress.toString(),
                isOutbound,
                expired,
                lastSeen,
                Math.max(0, now - lastActivityTime),
                currentSentBytes,
                currentReceivedBytes,
                sendBytesPerSecond,
                receiveBytesPerSecond,
                lastRttMillis,
                remoteRwnd.get(),
                localRwnd.get(),
                congestionWindow.get(),
                congestionWindow.getSlowStartThreshold(),
                frameSendQueue.getPendingSizeMB(),
                frameSendQueue.getSentUnAckedSizeMB(),
                frameSendQueue.getSentUnAckedSize(),
                MAX_FRAME_PAYLOAD,
                pathMtuEstimator.currentDatagramBytes(),
                QuicConnectionManager.getGlobalOutboundAvailableBytes()
        );
    }



    public void handleFrame(QuicFrame quicFrame) {
        if (expired){
            return;
        }
        // 基础校验：帧为空/无dataId直接返回
        if (quicFrame == null) {
            log.warn("[处理ACK帧] 无效的确认帧，connectionId={}", connectionId);
            return;
        }
        QuicFrameEnum frameEnum = QuicFrameEnum.fromCode(quicFrame.getFrameType());
        if (quicFrame.getDataId() <= 0 && frameEnum != QuicFrameEnum.OFF_FRAME) {
            log.warn("[handleFrame] invalid dataId, connectionId={}, frameType={}, dataId={}",
                    connectionId, frameEnum, quicFrame.getDataId());
            return;
        }
        touchReceived(System.currentTimeMillis());
        switch (frameEnum) {
            case DATA_FRAME:
                handleDataFrame(quicFrame);
                break;
            case DATA_ACK_FRAME:
                handleDataACKFrame(quicFrame);
                break;
            case ALL_ACK_FRAME:
                handleAllACKFrame(quicFrame);
                break;
            case BATCH_ACK_FRAME:
                handleBatchACKFrame(quicFrame);
                break;
            case BITMAP_ACK_FRAME:
                handleBitMapACKFrame(quicFrame);
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
            case CANCEL_FRAME:
                handleCancelFrame(quicFrame);
                break;
            case UPDATE_WINDOW_FRAME:
                handleUpdateWindowFrame(quicFrame);
                break;
            default:
                break;
        }
    }

    private void handleDataFrame(QuicFrame quicFrame) {
        if (expired) {
            // 发送下线帧
            disConnectRemoteByPeerId(peerId);
            return;
        }
        // 已处理或已过期的数据直接取消，避免重复扣减接收窗口。
        if (receiveMap.containsKey(quicFrame.getDataId())) {
            sendFrameWithoutResponse(buildCancelFrame(quicFrame,localRwnd.get()));
            return;
        }
        // ========== 1. 基础参数准备 ==========
        long dataId = quicFrame.getDataId();
        int framePayloadSize = quicFrame.getFrameTotalLength();
        ReceiveQuicData receiveQuicData = null;
        if (receiveDataMap.containsKey(quicFrame.getDataId())) {
            receiveQuicData = receiveDataMap.get(quicFrame.getDataId());
            // 检查接收中的数据是否已过期。
            if (System.currentTimeMillis() - receiveQuicData.getStartReceiveTime() > receiveQuicData.getExpireTime()) {
                log.warn("[数据过期] dataId={}已过期，丢弃帧", quicFrame.getDataId());
                sendFrameWithoutResponse(buildCancelFrame(quicFrame,localRwnd.get()));
                receiveDataMap.remove(quicFrame.getDataId());
                releaseReservedReceiveWindow(quicFrame.getDataId());
                return;
            }
        }else {
            receiveQuicData = new ReceiveQuicData();
            receiveQuicData.setDataId(quicFrame.getDataId());
            receiveQuicData.setConnectionId(quicFrame.getConnectionId());
            receiveQuicData.setTotal(quicFrame.getTotal());
            receiveQuicData.setRemoteAddress(quicFrame.getRemoteAddress());
            receiveQuicData.setStartReceiveTime(System.currentTimeMillis());
            receiveQuicData.setExpireTime(5000);// 5秒过期
            receiveDataMap.put(quicFrame.getDataId(), receiveQuicData);
        }
        boolean newInboundFrame = !receiveQuicData.hasReceivedSequence(quicFrame.getSequence());
        // 仅对非空业务帧扣减窗口
        if (framePayloadSize > 0 && newInboundFrame) {
            // 窗口不足 → 缓存或拒绝
            if (!deductLocalWindow(framePayloadSize)) {
                handleWindowNotEnough(quicFrame);
                return;
            }
            reserveReceiveWindow(dataId, framePayloadSize);
            log.debug("[窗口扣减成功] dataId={}，扣减{}B，剩余窗口{}B",
                    quicFrame.getDataId(), framePayloadSize, localRwnd.get());
        }
        ReceiveResult receiveResult = receiveQuicData.handleFrame(quicFrame, localRwnd.get());
        // 接收窗口在数据完成、失败或过期后统一回补。
        if (!receiveResult.isSuccess()) {
            receiveDataMap.remove(quicFrame.getDataId());
            releaseReservedReceiveWindow(dataId);
            sendFrameWithoutResponse(buildCancelFrame(quicFrame, localRwnd.get()));
            return;
        }
        if (receiveResult.isCompleted()){
            releaseReservedReceiveWindow(dataId);
            boolean delivered = deliverCompletedData(receiveQuicData, quicFrame);
            receiveDataMap.remove(quicFrame.getDataId());
            if (!delivered) {
                sendFrameWithoutResponse(buildCancelFrame(quicFrame, localRwnd.get()));
                return;
            }
            receiveMap.put(quicFrame.getDataId(), System.currentTimeMillis());
        }
        QuicFrame ackFrame = receiveResult.getAckFrame();
        if(ackFrame != null){
            sendFrameWithoutResponse(ackFrame);
        }
    }

    private boolean deliverCompletedData(ReceiveQuicData receiveQuicData, QuicFrame sourceFrame) {
        byte[] combinedFullData = receiveQuicData.getCombinedFullData();
        if (combinedFullData == null) {
            log.error("[receive complete failed] connectionId={} dataId={} combined data is null",
                    connectionId, sourceFrame.getDataId());
            return false;
        }
        try {
            byte[] plainData = decryptApplicationData(combinedFullData);
            QuicMsg quicMsg = new QuicMsg();
            quicMsg.setConnectionId(connectionId);
            quicMsg.setDataId(sourceFrame.getDataId());
            quicMsg.setData(plainData);
            return pushCompleteMsg(quicMsg);
        } catch (Exception e) {
            expiredDataCount++;
            log.error("[decrypt received data failed] connectionId={} peerId={} dataId={} badDataCount={}",
                    connectionId, peerId, sourceFrame.getDataId(), expiredDataCount, e);
            InetSocketAddress errorRemoteAddress = sourceFrame.getRemoteAddress() == null
                    ? remoteAddress
                    : sourceFrame.getRemoteAddress();
            P2PTransferErrorEvents.publish(P2PTransferErrorRecord.now(
                    peerId,
                    errorRemoteAddress == null ? "" : errorRemoteAddress.toString(),
                    P2PErrorCode.RECEIVE_DECRYPT_FAILED,
                    "decrypt received data failed",
                    sourceFrame.getDataId(),
                    connectionId,
                    e
            ));
            if (expiredDataCount >= EXPIRED_DATA_THRESHOLD) {
                release();
            }
            return false;
        }
    }

    private byte[] encryptApplicationData(byte[] plainData) throws Exception {
        if (plainData == null || plainData.length == 0) {
            return plainData;
        }
        if (!QuicConnectionManager.isValidSharedSecretMaterial(sharedSecret)) {
            throw new IllegalStateException("sharedSecret negotiation not completed");
        }
        return aesGcmEncrypt(deriveAesKey(sharedSecret), plainData);
    }

    private byte[] decryptApplicationData(byte[] encryptedData) throws Exception {
        if (encryptedData == null || encryptedData.length == 0) {
            return encryptedData;
        }
        if (!QuicConnectionManager.isValidSharedSecretMaterial(sharedSecret)) {
            throw new IllegalStateException("sharedSecret negotiation not completed");
        }
        return aesGcmDecrypt(deriveAesKey(sharedSecret), encryptedData);
    }

    private boolean deductLocalWindow(int bytes) {
        if (bytes <= 0) {
            return true;
        }
        receiverLock.lock();
        try {
            int current = localRwnd.get();
            if (current < bytes) {
                return false;
            }
            localRwnd.set(current - bytes);
            return true;
        } finally {
            receiverLock.unlock();
        }
    }

    private void releaseLocalWindow(int bytes) {
        if (bytes <= 0) {
            return;
        }
        receiverLock.lock();
        try {
            int next = Math.min(INIT_WINDOW_SIZE, localRwnd.get() + bytes);
            localRwnd.set(next);
        } finally {
            receiverLock.unlock();
        }
    }

    private void reserveReceiveWindow(long dataId, int bytes) {
        if (bytes <= 0) {
            return;
        }
        receiveWindowReservations
                .computeIfAbsent(dataId, key -> new AtomicInteger())
                .addAndGet(bytes);
    }

    private void releaseReservedReceiveWindow(long dataId) {
        AtomicInteger reserved = receiveWindowReservations.remove(dataId);
        if (reserved == null) {
            return;
        }
        int bytes = reserved.get();
        if (bytes > 0) {
            releaseLocalWindow(bytes);
            consumeWaitQueue();
            log.debug("[接收窗口回补] dataId={} bytes={} localWindow={}", dataId, bytes, localRwnd.get());
        }
    }



    private void handleDataACKFrame(QuicFrame quicFrame) {
        long dataId = quicFrame.getDataId();
        int sequence = quicFrame.getSequence();
        int i = quicFrame.readAckFrameWindowSize();//窗口大小
        SendQuicData sendQuicData = unAckedDataMap.get(dataId);
        boolean acked = false;
        int ackedBytes = 0;
        if (sendQuicData!=null && !sendQuicData.isExpired() && !sendQuicData.isCompleted()){
            try {
                //加锁
                senderLock.lock();
                //修改远程窗口 将远程窗口 设置为i
                remoteRwnd.set(i);
                //通知数据来处理
                boolean isCompleted = sendQuicData.onAckReceived(sequence);
                if (isCompleted){
                    //立即删除不做任何判断
                    removeSendData(dataId);
                    //将这个ID加入到已经发送
                    sendMap.put(dataId, System.currentTimeMillis());
                    //通知队列
                    ackedBytes = frameSendQueue.ackAllFramesByDataId(dataId);
                }else {
                    //通知队列
                    ackedBytes = frameSendQueue.ackFrame(dataId, sequence);
                }
                acked = ackedBytes > 0;
            }finally {
                //解锁
                senderLock.unlock();
            }
        }
        if (acked) {
            onAckedOutboundBytes(ackedBytes);
            pickUnAckedFrames();
        }
    }

    private void onAckedOutboundBytes(int ackedBytes) {
        if (ackedBytes <= 0) {
            return;
        }
        pathMtuEstimator.onAckedBytes(ackedBytes);
        MAX_FRAME_PAYLOAD = pathMtuEstimator.currentPayloadBytes();
        congestionWindow.onAckedBytes(ackedBytes, MAX_FRAME_PAYLOAD);
    }


    private void handleAllACKFrame(QuicFrame quicFrame) {
        long dataId = quicFrame.getDataId();
        int i = quicFrame.readAckFrameWindowSize();//窗口大小
        log.info("handleAllACKFrame接收方窗口{}",i);
        SendQuicData sendQuicData = unAckedDataMap.get(dataId);
        boolean acked = false;
        int ackedBytes = 0;
        if (sendQuicData!=null && !sendQuicData.isExpired() && !sendQuicData.isCompleted()){
            try {
                //加锁
                senderLock.lock();
                //修改远程窗口 将远程窗口 设置为i
                remoteRwnd.set(i);
                //将这个ID加入到已经发送
                sendMap.put(dataId, System.currentTimeMillis());
                //立即删除不做任何判断
                removeSendData(dataId);
                //通知队列
                ackedBytes = frameSendQueue.ackAllFramesByDataId(dataId);
                acked = ackedBytes > 0;
            }finally {
                //解锁
                senderLock.unlock();
            }
        }
        if (acked) {
            onAckedOutboundBytes(ackedBytes);
            pickUnAckedFrames();
        }
    }

    private void handleBatchACKFrame(QuicFrame quicFrame) {
        long dataId = quicFrame.getDataId();
        int i = quicFrame.readBatchAckFrameWindowSize();
        log.debug("handleBatchACKFrame 窗口大小是{}", i);
        SendQuicData sendQuicData = unAckedDataMap.get(dataId);
        int ackedBytes = 0;
        if (sendQuicData!=null && !sendQuicData.isExpired() && !sendQuicData.isCompleted()){
            try {
                //加锁
                senderLock.lock();
                //修改远程窗口 将远程窗口 设置为i
                remoteRwnd.set(i);
                int[] ints = quicFrame.readBatchAckFrameSequences();
                AckResult ackResult = sendQuicData.handleBatchACKFrame(ints);
                if (ackResult.isSuccess()){
                    boolean completed = ackResult.isCompleted();
                    if (completed){
                        //将这个ID加入到已经发送
                        sendMap.put(dataId, System.currentTimeMillis());
                        //立即删除不做任何判断
                        removeSendData(dataId);
                        //通知队列
                        ackedBytes = frameSendQueue.ackAllFramesByDataId(dataId);
                    }else {
                        ackedBytes = frameSendQueue.batchAckFrame(dataId, ints);
                    }
                }
            }finally {
                //解锁
                senderLock.unlock();
            }
        }
        if (ackedBytes > 0) {
            onAckedOutboundBytes(ackedBytes);
            pickUnAckedFrames();
        }
    }

    private void handleBitMapACKFrame(QuicFrame quicFrame) {
        long dataId = quicFrame.getDataId();
        int receiveWindow = quicFrame.readBitmapAckFrameWindowSize();
        SendQuicData sendQuicData = unAckedDataMap.get(dataId);
        int ackedBytes = 0;
        if (sendQuicData != null && !sendQuicData.isExpired() && !sendQuicData.isCompleted()) {
            try {
                senderLock.lock();
                remoteRwnd.set(receiveWindow);
                AckResult ackResult = sendQuicData.handleBitMapACKFrame(quicFrame.getPayload());
                if (ackResult.isSuccess()) {
                    if (ackResult.isCompleted()) {
                        sendMap.put(dataId, System.currentTimeMillis());
                        removeSendData(dataId);
                        ackedBytes = frameSendQueue.ackAllFramesByDataId(dataId);
                    } else {
                        int[] sequences = quicFrame.readBitmapAckFrameReceivedSequences();
                        ackedBytes = frameSendQueue.batchAckFrame(dataId, sequences);
                    }
                }
            } finally {
                senderLock.unlock();
            }
        }
        if (ackedBytes > 0) {
            onAckedOutboundBytes(ackedBytes);
            pickUnAckedFrames();
        }
    }


    private void handleUpdateWindowFrame(QuicFrame quicFrame) {
        long dataId = quicFrame.getDataId();
        int i = quicFrame.readAckFrameWindowSize();//窗口大小
        log.info("handleUpdateWindowFrame接收方窗口{}",i);
        //设置窗口
        remoteRwnd.set(i);
        pickUnAckedFrames();
    }


    private void handleCancelFrame(QuicFrame quicFrame) {
        handleAllACKFrame(quicFrame);
    }




    private void handleOffFrame(QuicFrame quicFrame) {
        release();
    }

    private void handlePongFrame(QuicFrame quicFrame) {
        log.debug("收到pong帧{}", quicFrame);
        lastSeen = System.currentTimeMillis();
        Long pingTime = pingSendTimeMap.remove(quicFrame.getDataId());
        if (pingTime != null) {
            lastRttMillis = Math.max(0, lastSeen - pingTime);
        }
    }


    private void handlePingFrame(QuicFrame quicFrame) {
        log.debug("处理ping{}", quicFrame);
        QuicFrame pongFrame = new QuicFrame();//已经释放
        //更新连接访问时间
        lastSeen = System.currentTimeMillis();
        //回复PONG帧
        pongFrame.setConnectionId(connectionId);
        pongFrame.setDataId(quicFrame.getDataId()); // 临时数据ID
        pongFrame.setFrameType(QuicFrameEnum.PONG_FRAME.getCode());
        pongFrame.setTotal(1); // 单帧无需分片
        pongFrame.setSequence(0);
        pongFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH); // 无载荷
        pongFrame.setRemoteAddress(quicFrame.getRemoteAddress());
        sendFrameWithoutResponse(pongFrame);
    }




    /**
     * 释放连接  停止所有的数据发送
     */
    public void release(){
        expired = true;

        //发送数据
        unAckedDataMap.clear();
        unAckedDataSet.clear();

        //接收数据
        receiveDataMap.clear();
        receiveWindowReservations.clear();
    }


    /**
     * 给目标节点发送ping
     */
    public void ping(){
        //发送ping
        QuicFrame pingFrame = new QuicFrame();
        try {
            // 主动发送PING帧
            long dataId = ID_Generator.nextId();
            pingFrame.setConnectionId(connectionId);
            pingFrame.setDataId(dataId);
            pingFrame.setFrameType(QuicFrameEnum.PING_FRAME.getCode());
            pingFrame.setTotal(1);
            pingFrame.setSequence(0);
            pingFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH);
            pingFrame.setRemoteAddress(remoteAddress);
            pingSendTimeMap.put(dataId, System.currentTimeMillis());
            sendFrameWithoutResponse(pingFrame);
        } catch (Exception e) {
            log.error("[出站心跳异常] 连接ID:{}", connectionId, e);
        }
    }


    /**
     * 清理过期数据（发送/接收）
     * 1. 清理逻辑：每秒最多执行1次（减轻性能压力）
     * 2. 发送数据：超时未确认 → 标记重传/删除；过期（超期未完成）→ 直接删除+窗口回补
     * 3. 接收数据：超期未完成重组 → 删除+窗口回补
     * 4. 坏数据计数：超过阈值则标记连接失效
     */
    public void cleanExpiredData() {
        // 1. 频率控制：每秒最多清理1次，避免高频执行
        long currentTime = System.currentTimeMillis();
        // 连接已过期，直接返回（无需清理）
        if (expired) {
            return;
        }
        try {
            // ========== 第一步：清理过期的发送数据 ==========
            cleanExpiredSendData(currentTime);

            // ========== 第二步：清理过期的接收数据 ==========
            cleanExpiredReceiveData(currentTime);

        } catch (Exception e) {
            log.error("[清理过期数据异常] connectionId={}", connectionId, e);
        }
    }

    /**
     * 清理过期的接收数据
     * 规则：
     * - 超期未完成重组 → 删除+回补本地接收窗口
     * - 已完成重组 → 从map中移除，标记到receiveMap
     */
    private void cleanExpiredReceiveData(long currentTime) {
        if (receiveDataMap.isEmpty()) {
            return;
        }

        // 遍历接收数据（使用迭代器保证并发安全）
        Iterator<Map.Entry<Long, ReceiveQuicData>> iterator = receiveDataMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, ReceiveQuicData> entry = iterator.next();
            long dataId = entry.getKey();
            ReceiveQuicData receiveData = entry.getValue();

            // 1. 数据已完成接收 → 标记并移除
            if (receiveData.isCompleted()) {
                log.debug("[接收数据完成] dataId={} 清理", dataId);
                receiveMap.put(dataId, currentTime); // 标记为已处理
                iterator.remove();
                releaseReservedReceiveWindow(dataId);
                continue;
            }

            // 2. 数据过期（超期未完成重组）→ 回补窗口+删除
            long startReceiveTime = receiveData.getStartReceiveTime();
            long expireTime = receiveData.getExpireTime(); // 5000ms
            if (currentTime - startReceiveTime > expireTime) {
                log.warn("[接收数据过期] dataId={} 超过{}ms未完成重组", dataId, expireTime);
                // 删除数据
                iterator.remove();
                receiveMap.put(dataId, currentTime); // 标记为已过期
                // 坏数据计数+1
                releaseReservedReceiveWindow(dataId);
                expiredDataCount++;
            }
        }
    }


    /**
     * 清理过期的发送数据
     * 规则：
     * - 超时（未在超时时间内收到ACK）：标记为待重传（仅重传1次，避免无限重传）
     * - 过期（超期未完成）：直接删除 + 回补远程接收窗口
     * - 已完成/已处理：直接删除
     */
    private void cleanExpiredSendData(long currentTime) {
        if (unAckedDataSet.isEmpty()) {
            return;
        }

        // 加锁保证并发安全（避免遍历过程中数据被修改）
        // 遍历所有待确认的dataId（使用迭代器遍历，但使用Set.remove()删除）
        Iterator<Long> iterator = unAckedDataSet.iterator();
        while (iterator.hasNext()) {
            Long dataId = iterator.next();
            SendQuicData sendData = unAckedDataMap.get(dataId);

            // 数据已被移除（空指针保护）
            if (sendData == null) {
                unAckedDataSet.remove(dataId);
                continue;
            }

            // 1. 数据已完成发送 → 直接删除
            if (sendData.isCompleted()) {
                removeSendData(dataId);
                sendMap.put(dataId, currentTime); // 标记为已处理，避免重复ACK
                log.debug("[清理发送数据] dataId={} 已完成发送，移除", dataId);
                continue;
            }

            // 2. 计算超时/过期时间
            long sendTime = sendData.getSendTime();
            long timeout = sendData.getTimeout(); // 单次发送超时（500ms）
            long expireTime = sendData.getExpireTime(); // 整体过期时间（5000ms）
            long timeoutDeadline = sendTime + timeout;
            long expireDeadline = sendTime + expireTime;

            // 3. 超时
            if (currentTime > timeoutDeadline && sendData.isNeedRetryWithInterval()) {
                sendData.incrementRetryCount();
                congestionWindow.onTimeout();
                pathMtuEstimator.onPotentialBlackHole();
                MAX_FRAME_PAYLOAD = pathMtuEstimator.currentPayloadBytes();
                log.debug("[发送数据超时] dataId={} 超时，准备重传（重传次数={}）", dataId, sendData.getRetryCount());
                // 重传逻辑：将数据ID放回队首，优先发送
                unAckedDataSet.offerFirst(dataId);
                //单帧超过150ms未ACK的都重新发送
                frameSendQueue.retransmitFrame(dataId, 150);
                continue;
            }

            // 4. 数据过期（超期未完成）→ 删除+回补远程接收窗口
            if (currentTime > expireDeadline) {
                log.warn("[发送数据过期] dataId={} 超过{}ms未完成", dataId, expireTime);
                // 删除数据
                congestionWindow.onTimeout();
                pathMtuEstimator.onPotentialBlackHole();
                MAX_FRAME_PAYLOAD = pathMtuEstimator.currentPayloadBytes();
                frameSendQueue.expireDataById(dataId);
                removeSendData(dataId);
                sendMap.put(dataId, currentTime); // 标记为已处理
                // 坏数据计数+1
                expiredDataCount++;
            }
        }
    }


    /**
     * 移除已完成/已确认的发送数据（统一包装类参数，null安全）
     * @param dataId 数据ID（允许null，null时直接返回）
     */
    private void removeSendData(Long dataId) {
        // 前置null校验：避免无意义的移除操作，同时打日志便于排查
        if (dataId == null) {
            log.warn("[移除已完成数据] 数据ID为null，跳过移除操作");
            return;
        }
        // 先删Set（遍历依赖），再删Map，逻辑不变
        boolean isSetRemoved = unAckedDataSet.remove(dataId);
        boolean isMapRemoved = unAckedDataMap.remove(dataId) != null;
        // 日志细化：区分“存在并移除”和“不存在”，便于排查数据一致性问题
        if (isSetRemoved || isMapRemoved) {
            log.debug("[移除已完成数据] dataId={}，Set移除={}，Map移除={}", dataId, isSetRemoved, isMapRemoved);
        } else {
            log.debug("[移除已完成数据] dataId={} 不存在，无需移除", dataId);
        }
    }

    /**
     * 构建发送数据
     * @param connectionId 连接ID
     * @param dataId 数据ID
     * @param sendData 需要发送的数据
     * @param maxFrameSize 每帧最多maxFrameSize字节
     * @return
     */
    public static SendQuicData buildSendData(long connectionId, long dataId,
                                             byte[] sendData, InetSocketAddress remoteAddress, int maxFrameSize) {
        // 前置校验
        if (sendData == null) {
            throw new IllegalArgumentException("数据不能为空");
        }
        if (maxFrameSize <= 0) {
            throw new IllegalArgumentException("单帧最大帧载荷长度必须大于0，当前值: " + maxFrameSize);
        }
        if (remoteAddress == null) {
            throw new IllegalArgumentException("目标地址不能为空");
        }

        SendQuicData quicData = new SendQuicData();
        quicData.setConnectionId(connectionId);
        quicData.setDataId(dataId);

        int fullDataLength = sendData.length;
        int totalFrames = (fullDataLength + maxFrameSize - 1) / maxFrameSize;
        //如果总帧数大于MAX_FRAME 直接报错
        if (totalFrames > MAX_FRAME) {
            log.info("当前帧数量{} ",totalFrames);
            throw new IllegalArgumentException("帧数量超出最大限制");
        }
        // 计算分片总数：向上取整（避免因整数除法丢失最后一个不完整分片）
        quicData.setTotal(totalFrames);//分片总数
        quicData.setFrameArray(new QuicFrame[totalFrames]);
        // 2. 分片构建QuicFrame
        try {
            for (int sequence = 0; sequence < totalFrames; sequence++) {
                // 创建帧实例
                QuicFrame frame =  new QuicFrame();
                frame.setConnectionId(connectionId);
                frame.setDataId(dataId);
                frame.setTotal(totalFrames);
                frame.setFrameType(QuicFrameEnum.DATA_FRAME.getCode());
                frame.setSequence(sequence);
                frame.setRemoteAddress(remoteAddress);

                int startIndex = sequence * maxFrameSize;
                int endIndex = Math.min(startIndex + maxFrameSize, fullDataLength);
                int currentPayloadLength = endIndex - startIndex;

                // 截取载荷数据（从原始数据复制对应区间）
                byte[] payload = new byte[currentPayloadLength];
                System.arraycopy(sendData, startIndex, payload, 0, currentPayloadLength);
                // 设置帧的总长度（固定头部 + 实际载荷长度）
                frame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH + currentPayloadLength);
                frame.setPayload(payload);
                quicData.getFrameArray()[sequence] = frame;
                log.debug("构建分片完成: connectionId={}, dataId={}, 序列号={}, 载荷长度={}, 总长度={}",
                        connectionId, dataId, sequence, currentPayloadLength, frame.getFrameTotalLength());
            }
            quicData.setSize(sendData.length);
        } catch (Exception e) {
            log.error("构建QuicData失败 connectionId={}, dataId={}", connectionId, dataId, e);
            // 异常时释放已创建的帧
            throw new RuntimeException("构建QuicData失败", e);
        }
        return quicData;
    }



    @Override
    public byte[] sendData(String peerId, ProtocolEnum protocol, byte[] sendMsg, int time)  {
        try {
            P2PMessage p2PMessage = newRequestMessage(SelfPeer.getId(), protocol, sendMsg);
            byte[] serialize = p2PMessage.serialize();
            String requestId = bytesToHex(p2PMessage.getRequestId());
            CompletableFuture<QuicMsg> responseFuture = null;
            if (protocol.isHasResponse()){
                responseFuture = new CompletableFuture<>();
                MSG_RESPONSE_FUTURECACHE.put(requestId, responseFuture);
            }
            sendData(serialize);
            if (protocol.isHasResponse()){
                try {
                    byte[] data = responseFuture.get(time, TimeUnit.MILLISECONDS).getData();
                log.debug("返回结果时间{}", System.currentTimeMillis());
                    return data;
                } finally {
                    MSG_RESPONSE_FUTURECACHE.invalidate(requestId);
                }
            }else {
                return null;
            }
        }catch (Exception e){
            log.error("发送数据失败",e);
            return null;
        }
    }

    @Override
    public void sendData(byte[] data) {
        if (expired) {
            log.warn("[send data skipped] connectionId={} peerId={} is expired", connectionId, peerId);
            return;
        }
        byte[] wireData;
        try {
            wireData = encryptApplicationData(data);
        } catch (Exception e) {
            log.error("[encrypt send data failed] connectionId={} peerId={}", connectionId, peerId, e);
            return;
        }
        long dataId = ID_Generator.nextId();
        SendQuicData sendQuicData = buildSendData(connectionId, dataId, wireData, remoteAddress, MAX_FRAME_PAYLOAD);
        
        sendQuicData.setSendTime(System.currentTimeMillis());
        //根据数据大小和平均网络时间计算得到数据发送超时 TODO 目前默认5秒
        sendQuicData.setTimeout(500);//500毫秒过期 会根据平均时间来
        sendQuicData.setExpireTime(5000);

        //存入未确认数据映射（唯一存储位置）
        unAckedDataMap.put(dataId, sendQuicData);//存数据
        unAckedDataSet.offerLast(dataId); // 再存ID到队尾

        // 构建帧元数据列表（仅传元数据，不传完整帧）
        List<BoundedFrameSendQueue.FrameMeta> frameMetas = new ArrayList<>();
        for (QuicFrame frame : sendQuicData.getFrameArray()) {
            frameMetas.add(new BoundedFrameSendQueue.FrameMeta(
                    frame.getDataId(),
                    frame.getSequence(),
                    frame.getFrameTotalLength()
            ));
        }
        // 批量入队元数据
        boolean enqueueSuccess = frameSendQueue.batchEnqueuePending(frameMetas, PRIORITY_NORMAL);
        if (!enqueueSuccess) {
            frameSendQueue.expireDataById(dataId);
            removeSendData(dataId);
            log.warn("[发送数据入队失败] dataId={} size={}B，已清理待发送数据", dataId, data.length);
            return;
        }
        pickUnAckedFrames();
    }


    /**
     * 计算发送方可发送的字节数（核心公式）
     * 可发送量 = 远程窗口 - 已发送未确认字节数
     */
    private int calculateAvailableSendBytes() {
        senderLock.lock(); // 仅加发送方锁
        try {
            int inflightBytes = frameSendQueue.getSentUnAckedSize();
            int available = Math.min(remoteRwnd.get(), congestionWindow.get()) - inflightBytes;
            int positiveAvailable = Math.max(available, 0);
            int frameBudget = MAX_FRAME_PAYLOAD + QuicFrame.FIXED_HEADER_LENGTH;
            int pacingBudget = Math.max(frameBudget,
                    Math.min(congestionWindow.get() / 2, frameBudget * 32));
            int requestedBytes = Math.min(positiveAvailable, pacingBudget);
            return QuicConnectionManager.reserveGlobalOutboundBytes(requestedBytes);
        } finally {
            senderLock.unlock();
        }
    }


    public void pickUnAckedFrames() {
        if (expired || frameSendQueue.isPendingEmpty()) {
            return;
        }
        int reservedBytes = calculateAvailableSendBytes();
        if (reservedBytes <= 0) {
            return;
        }
        List<QuicFrame> quicFrames = frameSendQueue.takePendingToSent(reservedBytes);
        int submittedBytes = 0;
        for (QuicFrame quicFrame : quicFrames) {
            if (sendFrameWithoutResponse(quicFrame)) {
                submittedBytes += quicFrame.getFrameTotalLength();
            }
        }
        if (reservedBytes > submittedBytes) {
            QuicConnectionManager.refundGlobalOutboundBytes(reservedBytes - submittedBytes);
        }
    }


    // 构建数据取消帧
    private QuicFrame buildCancelFrame(QuicFrame sourceFrame,int receiveWindow) {
        QuicFrame cancelFrame = new QuicFrame();
        cancelFrame.setConnectionId(sourceFrame.getConnectionId());
        cancelFrame.setDataId(sourceFrame.getDataId());
        cancelFrame.setFrameType(QuicFrameEnum.CANCEL_FRAME.getCode());
        cancelFrame.setTotal(0);
        cancelFrame.setSequence(0);
        cancelFrame.setRemoteAddress(sourceFrame.getRemoteAddress());
        cancelFrame.writeAckFrameWindowSize(receiveWindow);
        cancelFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH + Integer.BYTES);
        return cancelFrame;
    }

    private QuicFrame buildUpdateWindow(QuicFrame sourceFrame) {
        QuicFrame updateFrame = new QuicFrame();
        updateFrame.setConnectionId(sourceFrame.getConnectionId());
        updateFrame.setDataId(sourceFrame.getDataId());
        updateFrame.setFrameType(QuicFrameEnum.UPDATE_WINDOW_FRAME.getCode());
        updateFrame.setTotal(0);
        updateFrame.setSequence(0);
        updateFrame.setRemoteAddress(sourceFrame.getRemoteAddress());
        updateFrame.writeAckFrameWindowSize(localRwnd.get());
        updateFrame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH + Integer.BYTES);
        return updateFrame;
    }



    // 新增：窗口不足时的合法帧缓存队列（有界，防OOM）
    private final Deque<QuicFrame> windowWaitQueue = new ConcurrentLinkedDeque<>();
    // 新增：恶意帧计数（防攻击）
    private final AtomicInteger maliciousFrameCount = new AtomicInteger(0);
    private static final int WAIT_QUEUE_MAX_SIZE = 1024; // 窗口不足时的帧缓存队列上限


    /**
     * 窗口不足时的处理逻辑（缓存合法帧/拒绝恶意帧）
     */
    private void handleWindowNotEnough(QuicFrame frame) {
        int currentLocal = localRwnd.get();
        int frameSize = frame.getFrameTotalLength();
        log.warn("[窗口不足] dataId={}，需要{}B，剩余{}B", frame.getDataId(), frameSize, currentLocal);

        if (frameSize > INIT_WINDOW_SIZE) {
            int maliciousCount = maliciousFrameCount.incrementAndGet();
            sendFrameWithoutResponse(buildCancelFrame(frame, currentLocal));
            log.warn("[窗口不足] 单帧超过接收窗口，拒绝 dataId={} frameSize={}B limit={}B maliciousCount={}",
                    frame.getDataId(), frameSize, INIT_WINDOW_SIZE, maliciousCount);
            if (maliciousCount >= EXPIRED_DATA_THRESHOLD) {
                release();
            }
            return;
        }

        // 队列未满 → 缓存合法帧，后续重试
        if (windowWaitQueue.size() < WAIT_QUEUE_MAX_SIZE) {
            windowWaitQueue.offerLast(frame);
            log.debug("[缓存窗口不足帧] dataId={}，队列当前大小={}", frame.getDataId(), windowWaitQueue.size());
            // 发送“窗口不足”的ACK帧，让发送方限流
            sendFrameWithoutResponse(buildUpdateWindow(frame));
        } else {
            // 队列已满（可能是攻击）→ 直接拒绝
            maliciousFrameCount.incrementAndGet();
            sendFrameWithoutResponse(buildCancelFrame(frame, currentLocal));
            log.warn("[队列已满] 拒绝窗口不足帧 | dataId={}，队列上限={}", frame.getDataId(), WAIT_QUEUE_MAX_SIZE);
        }
    }


    /**
     * 消费窗口不足时缓存的帧（窗口回补后重试）
     */
    private void consumeWaitQueue() {
        if (windowWaitQueue.isEmpty()) {
            return;
        }
        // 每次最多消费10帧，避免窗口再次耗尽
        int consumeCount = 0;
        while (!windowWaitQueue.isEmpty() && consumeCount < 10) {
            QuicFrame cachedFrame = windowWaitQueue.pollFirst();
            if (cachedFrame == null) {
                break;
            }
            int frameSize = cachedFrame.getFrameTotalLength();
            // 检查窗口是否足够
            if (localRwnd.get() >= frameSize) {
                handleDataFrame(cachedFrame); // 递归处理缓存帧
                consumeCount++;
            } else {
                windowWaitQueue.offerFirst(cachedFrame);
                break; // 窗口再次不足，停止消费
            }
        }
        log.debug("[消费缓存队列] 处理{}帧，剩余队列大小={}", consumeCount, windowWaitQueue.size());
    }
}
