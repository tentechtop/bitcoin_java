package com.bit.coin.p2p.conn;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceiveQuicDataTest {
    @Test void duplicateNonzeroSequenceProducesSerializableAck() {
        ReceiveQuicData receive = new ReceiveQuicData();
        QuicFrame frame = dataFrame(1, 2, 223, 300, new byte[]{1});
        receive.handleFrame(frame, 1024);
        QuicFrame ack = receive.handleFrame(frame, 1024).getAckFrame();
        assertEquals(223, QuicFrame.deserialize(ack.serialize()).getSequence());
        assertEquals(QuicFrameEnum.DATA_ACK_FRAME.getCode(), ack.getFrameType());
    }
    @Test void flushesPartialAckBatchWithoutWaitingForAnotherPacket() {
        ReceiveQuicData receive = new ReceiveQuicData();
        receive.setLastAckTime(System.currentTimeMillis());
        receive.handleFrame(dataFrame(1, 2, 0, 3, new byte[]{1}), 1024);
        long lastAck = receive.getLastAckTime();
        assertNull(receive.pollDelayedAck(lastAck + 99, 512));
        QuicFrame ack = receive.pollDelayedAck(lastAck + 100, 512);
        assertNotNull(ack);
        assertEquals(QuicFrameEnum.BITMAP_ACK_FRAME.getCode(), ack.getFrameType());
        assertEquals(512, ack.readBitmapAckFrameWindowSize());
        assertEquals(0, ack.readBitmapAckFrameReceivedSequences()[0]);
        assertNull(receive.pollDelayedAck(lastAck + 200, 512));
    }
    private static final InetSocketAddress REMOTE = new InetSocketAddress("127.0.0.1", 8334);

    @Test
    void rejectsInvalidSequenceWithoutAllocatingReceiveBuffer() {
        ReceiveQuicData receive = new ReceiveQuicData();

        ReceiveResult result = receive.handleFrame(dataFrame(1L, 2L, 2, 2, new byte[]{1}), 1024);

        assertFalse(result.isSuccess());
        assertFalse(result.isCompleted());
        assertNull(receive.getFrameArray());
        assertEquals(0, receive.getReceivedSequencesArray().length);
    }

    @Test
    void rejectsInconsistentTotalForSameDataId() {
        ReceiveQuicData receive = new ReceiveQuicData();

        ReceiveResult first = receive.handleFrame(dataFrame(1L, 2L, 0, 2, new byte[]{1}), 1024);
        ReceiveResult inconsistent = receive.handleFrame(dataFrame(1L, 2L, 1, 3, new byte[]{2}), 1024);

        assertTrue(first.isSuccess());
        assertFalse(first.isCompleted());
        assertFalse(inconsistent.isSuccess());
        assertFalse(inconsistent.isCompleted());
        assertEquals(2, receive.getTotal());
        assertEquals(1, receive.getReceivedSequencesArray().length);
        assertNull(receive.getFrameArray()[1]);
    }

    @Test
    void duplicateFrameIsHandledAndAcked() {
        ReceiveQuicData receive = new ReceiveQuicData();
        QuicFrame frame = dataFrame(1L, 2L, 0, 2, new byte[]{1});

        ReceiveResult first = receive.handleFrame(frame, 1024);
        ReceiveResult duplicate = receive.handleFrame(frame, 1024);

        assertTrue(first.isSuccess());
        assertTrue(duplicate.isSuccess());
        assertFalse(duplicate.isCompleted());
        assertNotNull(duplicate.getAckFrame());
        assertEquals(1, receive.getReceivedSequencesArray().length);
    }

    @Test
    void missingFrameCannotBeCombined() {
        QuicData data = new QuicData();
        data.setConnectionId(1L);
        data.setDataId(2L);
        data.setTotal(2);
        data.setFrameArray(new QuicFrame[2]);
        data.getFrameArray()[0] = dataFrame(1L, 2L, 0, 2, new byte[]{1});

        assertNull(data.getCombinedFullData());
    }

    static QuicFrame dataFrame(long connectionId, long dataId, int sequence, int total, byte[] payload) {
        QuicFrame frame = new QuicFrame();
        frame.setConnectionId(connectionId);
        frame.setDataId(dataId);
        frame.setSequence(sequence);
        frame.setTotal(total);
        frame.setFrameType(QuicFrameEnum.DATA_FRAME.getCode());
        frame.setPayload(payload);
        frame.setFrameTotalLength(QuicFrame.FIXED_HEADER_LENGTH + (payload == null ? 0 : payload.length));
        frame.setRemoteAddress(REMOTE);
        return frame;
    }
}
