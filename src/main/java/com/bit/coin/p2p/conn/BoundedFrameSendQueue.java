package com.bit.coin.p2p.conn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** 跟踪一条连接上的待发送帧和在途帧。所有索引和字节计数在同一个锁内更新。 */
public class BoundedFrameSendQueue {

    private static final long MAX_PENDING_QUEUE_BYTES = 32L * 1024 * 1024;  // 单连接待发送帧的字节预算，在途帧单独计数
    private static final double BYTES_PER_MB = 1024.0 * 1024.0;

    // 同优先级按入队顺序发送；按 dataId 的索引用于局部确认、重传和过期清理。
    private final TreeMap<Integer, LinkedHashSet<FrameQueueElement>> pendingByPriority = new TreeMap<>();
    private final Map<Long, TransferFrames> transfers = new HashMap<>();
    private final ConcurrentHashMap<Long, SendQuicData> unAckedDataMap;
    private final LongSupplier clockMillis;
    private long pendingBytes;
    private long inFlightBytes;

    private static class TransferFrames {

        final Map<Integer, FrameQueueElement> pending = new HashMap<>();
        final Map<Integer, FrameQueueElement> inFlight = new HashMap<>();

        boolean contains(int sequence) {
            return pending.containsKey(sequence) || inFlight.containsKey(sequence);
        }
    }

    public BoundedFrameSendQueue(ConcurrentHashMap<Long, SendQuicData> unAckedDataMap) {
        this(unAckedDataMap, System::currentTimeMillis);
    }

    BoundedFrameSendQueue(ConcurrentHashMap<Long, SendQuicData> unAckedDataMap, LongSupplier clockMillis) {
        this.unAckedDataMap = unAckedDataMap;
        this.clockMillis = clockMillis;
    }

    public synchronized void clear() {
        pendingByPriority.clear();
        transfers.clear();
        pendingBytes = 0;
        inFlightBytes = 0;
    }

    public boolean enqueuePending(long dataId, int sequence, int frameSize, int priority) {
        return batchEnqueuePending(List.of(new FrameMeta(dataId, sequence, frameSize)), priority);
    }

    /** 先检查整批分片和剩余容量，全部通过才入队。 */
    public synchronized boolean batchEnqueuePending(List<FrameMeta> frameMetas, int priority) {
        if (frameMetas == null || frameMetas.isEmpty()) {
            return false;
        }
        var batch = new LinkedHashSet<FrameQueueElement>();
        long bytes = pendingBytes;
        for (FrameMeta meta : frameMetas) {
            if (meta == null || meta.dataId <= 0 || meta.sequence < 0 || meta.frameSize <= 0) {
                return false;
            }
            bytes += meta.frameSize;
            SendQuicData data = unAckedDataMap.get(meta.dataId);
            TransferFrames transfer = transfers.get(meta.dataId);
            if (bytes > MAX_PENDING_QUEUE_BYTES || !isActive(data) || meta.sequence >= data.getTotal()
                    || (transfer != null && transfer.contains(meta.sequence))) {
                return false;
            }
            if (!batch.add(new FrameQueueElement(meta.dataId, meta.sequence, meta.frameSize, priority))) {
                return false;
            }
        }
        for (FrameQueueElement frame : batch) {
            addPending(transfers.computeIfAbsent(frame.getDataId(), id -> new TransferFrames()), frame);
        }
        return true;
    }

    private static boolean isActive(SendQuicData data) {
        return data != null && !data.isCompleted() && !data.isExpired();
    }

    private void addPending(TransferFrames transfer, FrameQueueElement frame) {
        pendingByPriority.computeIfAbsent(frame.getPriority(), priority -> new LinkedHashSet<>()).add(frame);
        transfer.pending.put(frame.getSequence(), frame);
        pendingBytes += frame.getFrameSize();
    }

    private void removeFromPriorityQueue(FrameQueueElement frame) {
        var bucket = pendingByPriority.get(frame.getPriority());
        bucket.remove(frame);
        if (bucket.isEmpty()) {
            pendingByPriority.remove(frame.getPriority());
        }
        pendingBytes -= frame.getFrameSize();
    }

    public synchronized List<QuicFrame> takePendingToSent(int maxBytes) {
        List<QuicFrame> result = new ArrayList<>();
        long now = clockMillis.getAsLong();
        while (maxBytes > 0 && !pendingByPriority.isEmpty()) {
            FrameQueueElement element = pendingByPriority.firstEntry().getValue().getFirst();
            if (element.getFrameSize() > maxBytes) {
                break;
            }
            long id = element.getDataId();
            TransferFrames transfer = transfers.get(id);
            removeFromPriorityQueue(element);
            transfer.pending.remove(element.getSequence());
            SendQuicData data = unAckedDataMap.get(id);
            QuicFrame frame = isActive(data) ? data.getFrameBySequence(element.getSequence()) : null;
            if (frame == null) {
                forgetIfEmpty(id, transfer);
                continue;
            }
            element.setSendTime(now);
            transfer.inFlight.put(element.getSequence(), element);
            inFlightBytes += element.getFrameSize();
            maxBytes -= element.getFrameSize();
            result.add(frame);
        }
        return result;
    }

    public synchronized int retransmitFrame(long dataId, long timeoutMs) {
        TransferFrames transfer = transfers.get(dataId);
        SendQuicData data = unAckedDataMap.get(dataId);
        if (timeoutMs <= 0 || transfer == null || !isActive(data)) {
            return 0;
        }
        int count = 0;
        long now = clockMillis.getAsLong();
        Iterator<FrameQueueElement> iterator = transfer.inFlight.values().iterator();
        while (iterator.hasNext()) {
            FrameQueueElement frame = iterator.next();
            int sequence = frame.getSequence();
            if (pendingBytes + frame.getFrameSize() > MAX_PENDING_QUEUE_BYTES
                    || now - frame.getSendTime() <= data.retryDelayMillis(sequence, timeoutMs)
                    || !data.reserveFrameRetry(sequence)) {
                continue;
            }
            iterator.remove();
            inFlightBytes -= frame.getFrameSize();
            frame.setPriority(FrameQueueElement.PRIORITY_RETRY);
            addPending(transfer, frame);
            count++;
        }
        return count;
    }

    public synchronized int ackFrame(long dataId, int sequence) {
        TransferFrames transfer = transfers.get(dataId);
        if (transfer == null) {
            return 0;
        }
        long bytes = sequence == -1 ? clearInFlight(transfer) : acknowledge(transfer, sequence);
        forgetIfEmpty(dataId, transfer);
        return (int) Math.min(bytes, Integer.MAX_VALUE);
    }

    public synchronized int batchAckFrame(long dataId, int[] sequences) {
        TransferFrames transfer = transfers.get(dataId);
        if (transfer == null || sequences == null) {
            return 0;
        }
        long bytes = 0;
        for (int sequence : sequences) {
            bytes += acknowledge(transfer, sequence);
        }
        forgetIfEmpty(dataId, transfer);
        return (int) Math.min(bytes, Integer.MAX_VALUE);
    }

    private int acknowledge(TransferFrames transfer, int sequence) {
        FrameQueueElement frame = transfer.inFlight.remove(sequence);
        if (frame != null) {
            inFlightBytes -= frame.getFrameSize();
            return frame.getFrameSize();
        }
        // 晚到的 ACK 可以取消尚未发出的重传；移入待发送时已扣过在途字节。
        frame = transfer.pending.get(sequence);
        if (frame != null && frame.getSendTime() != 0) {
            transfer.pending.remove(sequence);
            removeFromPriorityQueue(frame);
        }
        return 0;
    }

    private void forgetIfEmpty(long dataId, TransferFrames transfer) {
        if (transfer.pending.isEmpty() && transfer.inFlight.isEmpty()) {
            transfers.remove(dataId);
        }
    }

    public synchronized int expireDataById(long dataId) {
        TransferFrames transfer = transfers.remove(dataId);
        if (transfer == null) {
            return 0;
        }
        int count = transfer.pending.size() + transfer.inFlight.size();
        clearTransfer(transfer);
        return count;
    }

    public synchronized int ackAllFramesByDataId(long dataId) {
        TransferFrames transfer = transfers.remove(dataId);
        return transfer == null ? 0 : (int) Math.min(clearTransfer(transfer), Integer.MAX_VALUE);
    }

    private long clearTransfer(TransferFrames transfer) {
        transfer.pending.values().forEach(this::removeFromPriorityQueue);
        return clearInFlight(transfer);
    }

    private long clearInFlight(TransferFrames transfer) {
        long bytes = 0;
        for (FrameQueueElement frame : transfer.inFlight.values()) {
            bytes += frame.getFrameSize();
        }
        transfer.inFlight.clear();
        inFlightBytes -= bytes;
        return bytes;
    }

    public synchronized double getPendingSizeMB() {
        return pendingBytes / BYTES_PER_MB;
    }

    public synchronized double getSentUnAckedSizeMB() {
        return inFlightBytes / BYTES_PER_MB;
    }

    public synchronized int getSentUnAckedSize() {
        return (int) Math.min(inFlightBytes, Integer.MAX_VALUE);
    }

    synchronized int peekNextPendingFrameSize() {
        return pendingByPriority.isEmpty() ? 0 : pendingByPriority.firstEntry().getValue().getFirst().getFrameSize();
    }

    public synchronized boolean isPendingEmpty() {
        return pendingBytes == 0;
    }

    public synchronized boolean isSentUnAckedEmpty() {
        return inFlightBytes == 0;
    }

    public static class FrameMeta {

        private final long dataId;
        private final int sequence;
        private final int frameSize;

        public FrameMeta(long dataId, int sequence, int frameSize) {
            this.dataId = dataId;
            this.sequence = sequence;
            this.frameSize = frameSize;
        }

        public long getDataId() {
            return dataId;
        }

        public int getSequence() {
            return sequence;
        }

        public int getFrameSize() {
            return frameSize;
        }
    }
}
