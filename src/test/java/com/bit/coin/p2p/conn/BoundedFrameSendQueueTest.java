package com.bit.coin.p2p.conn;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static com.bit.coin.p2p.conn.FrameQueueElement.PRIORITY_NORMAL;
import static org.junit.jupiter.api.Assertions.*;

class BoundedFrameSendQueueTest {

    private final ConcurrentHashMap<Long, SendQuicData> transfers = new ConcurrentHashMap<>();
    private final AtomicLong now = new AtomicLong(1);
    private final BoundedFrameSendQueue queue = new BoundedFrameSendQueue(transfers, now::get);

    @Test
    void batchWithDuplicateFrameIsRejectedWithoutChangingTheQueue() {
        var frames = transfer(1, 100, 200);
        assertTrue(queue.enqueuePending(1, 0, 100, PRIORITY_NORMAL));
        assertFalse(queue.batchEnqueuePending(List.of(frames.get(1), frames.get(1)), PRIORITY_NORMAL));
        assertFalse(queue.batchEnqueuePending(List.of(frames.get(1), frames.get(0)), PRIORITY_NORMAL));
        assertEquals(1, queue.takePendingToSent(300).size());
        assertTrue(queue.isPendingEmpty());
        assertEquals(100, queue.getSentUnAckedSize());
    }

    @Test
    void fullPendingQueuePreservesInFlightFrameAndItsRetryBudget() {
        var original = transfer(1, 100);
        assertTrue(queue.batchEnqueuePending(original, PRIORITY_NORMAL));
        assertEquals(1, queue.takePendingToSent(100).size());
        int capacity = 32 * 1024 * 1024;
        assertTrue(queue.batchEnqueuePending(transfer(2, capacity), PRIORITY_NORMAL));
        now.addAndGet(10);
        for (int i = 0; i <= SendQuicData.MAX_FRAME_RETRIES; i++) {
            assertEquals(0, queue.retransmitFrame(1, 1));
        }
        assertEquals(100, queue.getSentUnAckedSize());
        assertEquals(1, queue.expireDataById(2));
        assertEquals(1, queue.retransmitFrame(1, 1));
        assertEquals(1, queue.takePendingToSent(100).size());
        assertEquals(100, queue.ackFrame(1, 0));
        assertTrue(queue.isSentUnAckedEmpty());
    }

    @Test
    void delayedAcksCancelQueuedRetriesAndLeaveOtherFramesAlone() {
        assertTrue(queue.batchEnqueuePending(transfer(1, 100, 100, 100), PRIORITY_NORMAL));
        assertEquals(3, queue.takePendingToSent(300).size());
        now.addAndGet(10);
        assertEquals(3, queue.retransmitFrame(1, 1));
        assertTrue(queue.batchEnqueuePending(transfer(2, 100), PRIORITY_NORMAL));
        assertEquals(0, queue.ackFrame(1, 0));
        assertEquals(0, queue.batchAckFrame(1, new int[] { 1, 1 }));
        var remaining = queue.takePendingToSent(200);
        assertEquals(2, remaining.size());
        assertEquals(2, remaining.get(0).getSequence());
        assertEquals(2L, remaining.get(1).getDataId());
        assertTrue(queue.isPendingEmpty());
        assertEquals(100, queue.ackAllFramesByDataId(1));
        assertEquals(100, queue.getSentUnAckedSize());
    }

    private List<BoundedFrameSendQueue.FrameMeta> transfer(long id, int... sizes) {
        SendQuicData data = new SendQuicData();
        data.setTotal(sizes.length);
        QuicFrame[] frames = new QuicFrame[sizes.length];
        var metadata = new ArrayList<BoundedFrameSendQueue.FrameMeta>();
        for (int i = 0; i < sizes.length; i++) {
            frames[i] = ReceiveQuicDataTest.dataFrame(1, id, i, sizes.length, new byte[0]);
            frames[i].setFrameTotalLength(sizes[i]);
            metadata.add(new BoundedFrameSendQueue.FrameMeta(id, i, sizes[i]));
        }
        data.setFrameArray(frames);
        transfers.put(id, data);
        return metadata;
    }
}
