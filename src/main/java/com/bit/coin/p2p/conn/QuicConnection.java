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
 * QUIC Connection implementation for Bitcoin P2P communication.
 * 
 * Manages a single QUIC connection with frame-level send/receive,
 * window-based flow control (remote and local congestion windows),
 * batch ACK/bitmap ACK handling, timeout detection and retransmission,
 * and AES-GCM application data encryption.
 * 
 * Features:
 * - Ordered/unordered frame queue with priority support
 * - Per-dataId tracking of sent and received data chunks
 * - Malicious frame detection and auto-disconnection
 * - Window-not-enough queuing with bounded wait queue (max 1024 entries)
 * - Expired data cleanup after threshold (3 consecutive expirations)
 * 
 * Key constants:
 * - INIT_WINDOW_SIZE = 1024*1024 (1MB initial flow control window)
 * - MAX_FRAME_PAYLOAD = 1400 bytes
 * - WAIT_QUEUE_MAX_SIZE = 1024
 * - EXPIRED_DATA_THRESHOLD = 3
 * - Default retry timeout: 500ms, expire timeout: 5000ms
 */
@Slf4j
@Data
public class QuicConnection implements QuicConnectionInterFace{
    private String peerId; // Remote peer identifier
    private long connectionId; // QUIC connection identifier
    private volatile InetSocketAddress remoteAddress; // Remote peer address:port
    private byte[] sharedSecret; // ECC-derived AES shared secret for encryption
    private volatile boolean expired = false; // Connection expiration flag
    private volatile long lastSeen = System.currentTimeMillis(); // Timestamp of last received frame
    private boolean isOutbound; // true = this peer initiated the connection, false = accepted incoming


    // TODO: Optimize send map cleanup - remove stale entries after connection expires or max retention period
    public volatile int MAX_FRAME_PAYLOAD = 1400 ;
    private volatile long lastActivityTime = lastSeen;

 //
    private ConcurrentHashMap<Long, SendQuicData> unAckedDataMap = new ConcurrentHashMap<>();
 //
    private final OrderedConcurrentSet unAckedDataSet = new OrderedConcurrentSet();

 //
    private ConcurrentHashMap<Long, ReceiveQuicData> receiveDataMap = new ConcurrentHashMap<>();


    /** Expired data threshold for auto-disconnection. Connection is released after this many consecutive expirations. */
    private static final int EXPIRED_DATA_THRESHOLD = 3;
    /** Counter for consecutive expired data events. Triggers disconnection at threshold (3). */
    private volatile int expiredDataCount = 0;


 //
    private Map<Long, Long> sendMap = new ConcurrentHashMap<>();
 //
    private Map<Long, Long> receiveMap = new ConcurrentHashMap<>();


 //
    /** Initial flow control window size: 1MB. Both sender and receiver start with this value. Frame size exceeding this is treated as malicious. */
    public static final int INIT_WINDOW_SIZE = 1024 * 1024;

    // ========== Sender / Receiver Locks ==========
    /** Lock for sender-side operations. Protects sendMap, unAckedDataMap, and remoteRwnd. */
    private final ReentrantLock senderLock = new ReentrantLock();
    /** */
    public void release(){
        expired = true;

 //
        unAckedDataMap.clear();
        unAckedDataSet.clear();

 //
        receiveDataMap.clear();
    }


    /** */
    public void ping(){
 //
        QuicFrame pingFrame = new QuicFrame();
        try {
 //
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
            log.error("[sanitized invalid encoded log]", connectionId, e);
        }
    }


    /** */
    public void cleanExpiredData() {
 //
        long currentTime = System.currentTimeMillis();
 //
        if (expired) {
            return;
        }
        try {
 //
            cleanExpiredSendData(currentTime);

 //
            cleanExpiredReceiveData(currentTime);

        } catch (Exception e) {
            log.error("[sanitized invalid encoded log]", connectionId, e);
        }
    }

    /** */
    private void cleanExpiredReceiveData(long currentTime) {
        if (receiveDataMap.isEmpty()) {
            return;
        }

 //
        Iterator<Map.Entry<Long, ReceiveQuicData>> iterator = receiveDataMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, ReceiveQuicData> entry = iterator.next();
            long dataId = entry.getKey();
            ReceiveQuicData receiveData = entry.getValue();

 //
            if (receiveData.isCompleted()) {
                log.debug("[sanitized invalid encoded log]");
                receiveMap.put(dataId, currentTime); //
                iterator.remove();
                continue;
            }

 //
            long startReceiveTime = receiveData.getStartReceiveTime();
            long expireTime = receiveData.getExpireTime(); // 5000ms
            if (currentTime - startReceiveTime > expireTime) {
                log.warn("[sanitized invalid encoded log]");
 //
                iterator.remove();
                receiveMap.put(dataId, currentTime); //
 //
                expiredDataCount++;
            }
        }
    }


    /** */
    private void cleanExpiredSendData(long currentTime) {
        if (unAckedDataSet.isEmpty()) {
            return;
        }

 //
 //
        Iterator<Long> iterator = unAckedDataSet.iterator();
        while (iterator.hasNext()) {
            Long dataId = iterator.next();
            SendQuicData sendData = unAckedDataMap.get(dataId);

 //
            if (sendData == null) {
                unAckedDataSet.remove(dataId);
                continue;
            }

 //
            if (sendData.isCompleted()) {
                removeSendData(dataId);
                        // All frames ACKed: record completion and cleanup sender state
                log.debug("[sanitized invalid encoded log]");
                continue;
            }

 //
            long sendTime = sendData.getSendTime();
            long timeout = sendData.getTimeout(); // Retry timeout in ms (default: 500ms)
            long expireTime = sendData.getExpireTime(); // Expiry time in ms (default: 5000ms)
            long timeoutDeadline = sendTime + timeout;
            long expireDeadline = sendTime + expireTime;

 //
            if (currentTime > timeoutDeadline && sendData.isNeedRetryWithInterval()) {
                sendData.incrementRetryCount();
                congestionWindow.decreaseSmoothly();
                log.debug("[sanitized invalid encoded log]");
 //
                unAckedDataSet.offerFirst(dataId);
 //
                frameSendQueue.retransmitFrame(dataId, 150);
                continue;
            }

 //
            if (currentTime > expireDeadline) {
                log.warn("[sanitized invalid encoded log]");
 //
                congestionWindow.decreaseSmoothly();
                frameSendQueue.expireDataById(dataId);
                removeSendData(dataId);
                        // All frames ACKed: record completion and cleanup sender state
 //
                expiredDataCount++;
            }
        }
    }


    /**
         * Remove sent data tracking entries after successful ACK or expiration.
         * Cleans up both {@code unAckedDataSet} and {@code unAckedDataMap}.
         *
         * @param dataId the data identifier to remove
         */
    private void removeSendData(Long dataId) {
        if (dataId == null) {
            log.warn("[remove send data skipped] dataId is null");
            return;
        }
        boolean isSetRemoved = unAckedDataSet.remove(dataId);
        boolean isMapRemoved = unAckedDataMap.remove(dataId) != null;
        if (isSetRemoved || isMapRemoved) {
            log.debug("[remove send data] dataId={}, setRemoved={}, mapRemoved={}", dataId, isSetRemoved, isMapRemoved);
        } else {
            log.debug("[remove send data skipped] dataId={} not found", dataId);
        }
    }

    /**
         * Build a {@link SendQuicData} object by splitting raw data into multiple
         * QUIC frames based on {@code maxFrameSize}.
         * Each frame gets a monotonically increasing sequence number.
         *
         * @param connectionId the connection identifier
         * @param dataId the data identifier for this send batch
         * @param sendData the raw payload bytes to send
         * @param remoteAddress the destination address
         * @param maxFrameSize maximum payload per frame
         * @return the constructed SendQuicData with frame array
         */
    public static SendQuicData buildSendData(long connectionId, long dataId,
                                             byte[] sendData, InetSocketAddress remoteAddress, int maxFrameSize) {
 //
        if (sendData == null) {
            throw new IllegalArgumentException("invalid quic data");
        }
        if (maxFrameSize <= 0) {
            throw new IllegalArgumentException("invalid argument" + maxFrameSize);
        }
        if (remoteAddress == null) {
            throw new IllegalArgumentException("invalid quic data");
        }

        SendQuicData quicData = new SendQuicData();
        quicData.setConnectionId(connectionId);
        quicData.setDataId(dataId);

        int fullDataLength = sendData.length;
        int totalFrames = (fullDataLength + maxFrameSize - 1) / maxFrameSize;
 //
        if (totalFrames > MAX_FRAME) {
            log.info("[sanitized invalid encoded log]",totalFrames);
            throw new IllegalArgumentException("invalid quic data");
        }
 //
        quicData.setTotal(totalFrames); // Set total number of frames for this data batch
        quicData.setFrameArray(new QuicFrame[totalFrames]);
 //
        try {
            for (int sequence = 0; sequence < totalFrames; sequence++) {
 //
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

 //
                byte[] payload = new byte[currentPayloadLength];
                System.arraycopy(sendData, startIndex, payload, 0, currentPayloadLength);
 //
                frame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH + currentPayloadLength);
                frame.setPayload(payload);
                quicData.getFrameArray()[sequence] = frame;
                log.debug("[sanitized invalid encoded log]",
                        connectionId, dataId, sequence, currentPayloadLength, frame.getFrameTotalLength());
            }
            quicData.setSize(sendData.length);
        } catch (Exception e) {
            log.error("[sanitized invalid encoded log]", connectionId, dataId, e);
 //
            throw new RuntimeException("build QuicData failed", e);
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
                log.debug("response received at {}", System.currentTimeMillis());
                    return data;
                } finally {
                    MSG_RESPONSE_FUTURECACHE.invalidate(requestId);
                }
            }else {
                return null;
            }
        }catch (Exception e){
            log.error("[sanitized invalid encoded log]");
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
 //
        sendQuicData.setSendTime(System.currentTimeMillis());
    // TODO: Optimize send map cleanup - remove stale entries after connection expires or max retention period
        sendQuicData.setTimeout(500); // 500ms retry timeout
        sendQuicData.setExpireTime(5000);

 //
        unAckedDataMap.put(dataId, sendQuicData); // Register in un-acked map for ACK tracking
        unAckedDataSet.offerLast(dataId); // Enqueue for FIFO processing

 //
        List<BoundedFrameSendQueue.FrameMeta> frameMetas = new ArrayList<>();
        for (QuicFrame frame : sendQuicData.getFrameArray()) {
            frameMetas.add(new BoundedFrameSendQueue.FrameMeta(
                    frame.getDataId(),
                    frame.getSequence(),
                    frame.getFrameTotalLength()
            ));
        }
        boolean enqueueSuccess = frameSendQueue.batchEnqueuePending(frameMetas, PRIORITY_NORMAL);
        if (!enqueueSuccess) {
            frameSendQueue.expireDataById(dataId);
            removeSendData(dataId);
            log.warn("[send data enqueue failed] dataId={} size={}B, pending data cleaned", dataId, wireData.length);
            return;
        }
        pickUnAckedFrames();
    }


    /**
         * Calculate how many bytes can be sent right now, considering:
         * <ul>
         *   <li>Remote window: {@code remoteRwnd}</li>
         *   <li>Congestion window: controlled by congestion controller</li>
         *   <li>In-flight bytes: currently sent but un-acked frames</li>
         *   <li>Pacing budget: at minimum one frame, at most half the congestion window</li>
         * </ul>
         * Formula: available = min(remoteRwnd, cwnd) - inflightBytes
         *
         * @return the number of bytes available for new sends (never negative)
         */
    private int calculateAvailableSendBytes() {
                // Lock sender state before processing batch ACK
        try {
            int inflightBytes = frameSendQueue.getSentUnAckedSize();
            int available = Math.min(remoteRwnd.get(), congestionWindow.get()) - inflightBytes;
            int positiveAvailable = Math.max(available, 0);
            int pacingBudget = Math.max(MAX_FRAME_PAYLOAD,
                    Math.min(congestionWindow.get() / 2, MAX_FRAME_PAYLOAD * 32));
            return Math.min(positiveAvailable, pacingBudget);
        } finally {
            senderLock.unlock();
        }
    }


    public void pickUnAckedFrames() {
        List<QuicFrame> quicFrames = frameSendQueue.takePendingToSent(calculateAvailableSendBytes());
        for (QuicFrame quicFrame : quicFrames) {
            sendFrameWithoutResponse(quicFrame);
        }
    }


 //
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



 //
    private final Deque<QuicFrame> windowWaitQueue = new ConcurrentLinkedDeque<>();
 //
    private final AtomicInteger maliciousFrameCount = new AtomicInteger(0);
    private static final int WAIT_QUEUE_MAX_SIZE = 1024; // Maximum wait queue size to prevent memory exhaustion.


    /**
         * Handle case when local receive window is too small for an incoming frame.
         * <p>
         * If frame size exceeds INIT_WINDOW_SIZE, it is treated as malicious:
         * the malicious frame counter is incremented and a CANCEL frame is sent back.
         * After 3 malicious frames, the connection is released.
         * <p>
         * Otherwise the frame is queued in {@code windowWaitQueue} (bounded to 1024)
         * and an UPDATE_WINDOW frame is sent to notify the peer of current window.
         * If the wait queue is full, the frame is treated as malicious.
         */
    private void handleWindowNotEnough(QuicFrame frame) {
        int currentLocal = localRwnd.get();
        int frameSize = frame.getFrameTotalLength();
        log.warn("[sanitized invalid encoded log]");

        if (frameSize > INIT_WINDOW_SIZE) {
            int maliciousCount = maliciousFrameCount.incrementAndGet();
            sendFrameWithoutResponse(buildCancelFrame(frame, currentLocal));
            log.warn("[sanitized invalid encoded log]",
                    frame.getDataId(), frameSize, INIT_WINDOW_SIZE, maliciousCount);
            if (maliciousCount >= EXPIRED_DATA_THRESHOLD) {
                release();
            }
            return;
        }

 //
        if (windowWaitQueue.size() < WAIT_QUEUE_MAX_SIZE) {
            windowWaitQueue.offerLast(frame);
            log.debug("[sanitized invalid encoded log]", frame.getDataId(), windowWaitQueue.size());
 //
            sendFrameWithoutResponse(buildUpdateWindow(frame));
        } else {
 //
            maliciousFrameCount.incrementAndGet();
            sendFrameWithoutResponse(buildCancelFrame(frame, currentLocal));
            log.warn("[sanitized invalid encoded log]", frame.getDataId(), WAIT_QUEUE_MAX_SIZE);
        }
    }


    /**
         * Process frames that were queued due to insufficient receive window.
         * Called whenever local window space is freed (after completed data delivery).
         * Attempts to dequeue and handle up to 10 queued frames at a time.
         */
    private void consumeWaitQueue() {
        if (windowWaitQueue.isEmpty()) {
            return;
        }
        int consumeCount = 0;
        while (!windowWaitQueue.isEmpty() && consumeCount < 10) {
            QuicFrame cachedFrame = windowWaitQueue.pollFirst();
            if (cachedFrame == null) {
                break;
            }
            int frameSize = cachedFrame.getFrameTotalLength();
            if (localRwnd.get() >= frameSize) {
                handleDataFrame(cachedFrame);
                consumeCount++;
            } else {
                windowWaitQueue.offerFirst(cachedFrame);
                break;
            }
        }
        log.debug("[consume wait queue] handled={}, remaining={}", consumeCount, windowWaitQueue.size());
    }
}
