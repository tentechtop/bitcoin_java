package com.bit.coin.p2p.conn;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TransportRecoveryTest {
    @Test @SuppressWarnings("unchecked") void staleRelayCleanupPreservesReplacementAndReleasesDependentCircuit() {
        QuicConnectionManager.clearAllConnections();
        Map<Long, QuicConnection> connections = (Map<Long, QuicConnection>) ReflectionTestUtils.getField(QuicConnectionManager.class, "CONNECTION_MAP");
        QuicConnection old = new QuicConnection(), replacement = new QuicConnection(), circuit = new QuicConnection();
        old.setConnectionId(1); old.setPeerId("relay"); old.setExpired(true);
        replacement.setConnectionId(2); replacement.setPeerId("relay");
        circuit.setConnectionId(3); circuit.setPeerId("target"); circuit.setRelayPath(new RelayPath(1, "target"));
        connections.put(1L, old); connections.put(2L, replacement); connections.put(3L, circuit);
        QuicConnectionManager.PEER_CONNECTION.put("relay", 2L); QuicConnectionManager.PEER_CONNECTION.put("target", 3L);
        try (var manager = mockStatic(QuicConnectionManager.class, CALLS_REAL_METHODS)) {
            manager.when(() -> QuicConnectionManager.sendFrameWithoutResponse(any())).thenReturn(true);
            new QuicConnectionManager().checkConnection(); // Schedulers are not started by this unit fixture.
            assertEquals(2L, QuicConnectionManager.PEER_CONNECTION.get("relay"));
            assertNull(QuicConnectionManager.getConnection(3)); assertTrue(circuit.isExpired());
        } finally { QuicConnectionManager.clearAllConnections(); }
    }
    @Test void exactSendBudgetDoesNotRemoveTheNextUnsentFrame() {
        var map = new ConcurrentHashMap<Long, SendQuicData>();
        SendQuicData data = data(); map.put(2L, data);
        BoundedFrameSendQueue queue = new BoundedFrameSendQueue(map);
        for (QuicFrame frame : data.getFrameArray()) assertTrue(queue.enqueuePending(2, frame.getSequence(), frame.getFrameTotalLength(), 1));
        Set<Integer> taken = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            var frames = queue.takePendingToSent(30);
            assertEquals(1, frames.size()); taken.add(frames.getFirst().getSequence());
        }
        assertEquals(Set.of(0, 1, 2), taken);
        assertTrue(queue.isPendingEmpty()); assertEquals(90, queue.getSentUnAckedSize());
        assertEquals(90, queue.ackAllFramesByDataId(2));
    }

    @Test void retriesAreBoundedPerFrameSoLatePacketsRetainTheirRecoveryBudget() {
        var map = new ConcurrentHashMap<Long, SendQuicData>();
        SendQuicData data = data(); map.put(2L, data);
        BoundedFrameSendQueue queue = new BoundedFrameSendQueue(map);
        queue.enqueuePending(2, 0, 30, 1); queue.takePendingToSent(30);
        for (int i = 0; i < SendQuicData.MAX_FRAME_RETRIES; i++) {
            ageFrames(queue);
            assertEquals(1, queue.retransmitFrame(2, 300));
            assertEquals(1, queue.takePendingToSent(30).size());
            data.incrementRetryCount();
        }
        ageFrames(queue); assertEquals(0, queue.retransmitFrame(2, 300));
        queue.enqueuePending(2, 1, 30, 1); queue.takePendingToSent(30); ageFrames(queue);
        assertEquals(1, queue.retransmitFrame(2, 300));
        assertEquals(1, queue.takePendingToSent(30).getFirst().getSequence());
        data.setSendTime(System.currentTimeMillis() - 6000); ageFrames(queue);
        assertEquals(0, queue.retransmitFrame(2, 300));
    }

    @Test void deliveredDuplicateGetsAllAckWhileExpiredTransferGetsCancel() {
        QuicConnection connection = new QuicConnection(); connection.setConnectionId(1);
        byte[] secret = new byte[32]; Arrays.fill(secret, (byte) 7); connection.setSharedSecret(secret);
        QuicConstants.drainAllMsg();
        List<QuicFrame> replies = new ArrayList<>();
        try (var manager = mockStatic(QuicConnectionManager.class)) {
            manager.when(() -> QuicConnectionManager.isValidSharedSecretMaterial(any())).thenCallRealMethod();
            manager.when(() -> QuicConnectionManager.sendFrameWithoutResponse(any())).thenAnswer(call -> { replies.add(call.getArgument(0)); return true; });
            byte[] message = com.bit.coin.p2p.protocol.P2PMessage.newRequestMessage(new byte[32],
                    com.bit.coin.p2p.protocol.ProtocolEnum.TEXT_V1, new byte[]{1, 2, 3}).serialize();
            byte[] encrypted = com.bit.coin.utils.ECCWithAESGCM.aesGcmEncrypt(
                    com.bit.coin.utils.ECCWithAESGCM.deriveAesKey(secret), message);
            QuicFrame frame = ReceiveQuicDataTest.dataFrame(1, 2, 0, 1, encrypted);
            connection.handleFrame(frame); connection.handleFrame(frame);
            assertEquals(2, replies.size());
            assertTrue(replies.stream().allMatch(f -> f.getFrameType() == QuicFrameEnum.ALL_ACK_FRAME.getCode()));
            assertEquals(1, QuicConstants.drainAllMsg().size());
            connection.getReceiveMap().put(3L, -System.currentTimeMillis());
            frame.setDataId(3); connection.handleFrame(frame);
            assertEquals(QuicFrameEnum.CANCEL_FRAME.getCode(), replies.getLast().getFrameType());
        } catch (Exception e) { throw new AssertionError(e); }
        finally { connection.release(); QuicConstants.drainAllMsg(); }
    }

    @SuppressWarnings("unchecked") private static void ageFrames(BoundedFrameSendQueue queue) {
        Map<String, FrameQueueElement> sent = (Map<String, FrameQueueElement>) ReflectionTestUtils.getField(queue, "sentUnAckedMap");
        sent.values().forEach(frame -> frame.setSendTime(System.currentTimeMillis() - 4000));
    }
    private static SendQuicData data() {
        SendQuicData data = new SendQuicData(); data.setConnectionId(1); data.setDataId(2); data.setTotal(3);
        data.setFrameArray(new QuicFrame[]{ReceiveQuicDataTest.dataFrame(1, 2, 0, 3, new byte[1]),
                ReceiveQuicDataTest.dataFrame(1, 2, 1, 3, new byte[1]), ReceiveQuicDataTest.dataFrame(1, 2, 2, 3, new byte[1])});
        return data;
    }
}
