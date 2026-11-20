package com.bit.coin.p2p.conn;

import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolEnum;
import com.bit.coin.p2p.protocol.ProtocolHandler;
import com.bit.coin.p2p.protocol.ProtocolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static com.bit.coin.utils.ECCWithAESGCM.aesGcmEncrypt;
import static com.bit.coin.utils.ECCWithAESGCM.deriveAesKey;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuicMessageDispatchTest {

    private static final long CONNECTION_ID = 72;
    private final byte[] sender = new byte[32];

    @Test
    void queuedRequestIsDecodedOnceAndDispatchedToItsHandler() throws Exception {
        byte[] wire = P2PMessage.newRequestMessage(sender, ProtocolEnum.TEXT_V1, new byte[131072]).serialize();
        var handler = mock(ProtocolHandler.class);
        var registry = mock(ProtocolRegistry.class);
        when(registry.getHandlerMap()).thenReturn(Map.of(ProtocolEnum.TEXT_V1, handler));
        var processor = new QuicDataProcessor();
        ReflectionTestUtils.setField(processor, "protocolRegistry", registry);
        var connection = new QuicConnection();
        connection.setPeerId(bytesToHex(sender));
        connection.setConnectionId(CONNECTION_ID);
        byte[] secret = new byte[32];
        secret[0] = 1;
        connection.setSharedSecret(secret);
        var frames = QuicConnection.buildSendData(CONNECTION_ID, 1, aesGcmEncrypt(deriveAesKey(secret), wire),
                new InetSocketAddress("127.0.0.1", 8334), 1024).getFrameArray();

        try (var codec = mockStatic(P2PMessage.class, CALLS_REAL_METHODS);
                var manager = mockStatic(QuicConnectionManager.class)) {
            manager.when(() -> QuicConnectionManager.getConnection(CONNECTION_ID)).thenReturn(connection);
            manager.when(() -> QuicConnectionManager.isValidSharedSecretMaterial(any())).thenCallRealMethod();
            long before = QuicConstants.queuedBytes();
            for (QuicFrame frame : frames) {
                connection.handleFrame(frame);
            }
            connection.handleFrame(frames[frames.length - 1]);
            assertEquals(before + wire.length, QuicConstants.queuedBytes());
            QuicMsg incoming = QuicConstants.pollMsg(1, TimeUnit.SECONDS);
            assertNotNull(incoming);
            processor.processSingleMsg(incoming, 1);
            assertEquals(before, QuicConstants.queuedBytes());
            verify(handler).handle(same(incoming.message()));
            codec.verify(() -> P2PMessage.deserialize(any(byte[].class)), times(1));
        } finally {
            connection.release();
        }
    }

    @Test
    void onlyTheExpectedConnectionCompletesTheFutureWithTheDecodedResponse() throws Exception {
        P2PMessage request = P2PMessage.newRequestMessage(sender, ProtocolEnum.TEXT_V1, new byte[0]);
        P2PMessage reply = P2PMessage.newResponseMessage(sender, ProtocolEnum.TEXT_V1, request.getRequestId(),
                new byte[] { 7 });
        byte[] wire = reply.serialize();
        String id = bytesToHex(request.getRequestId());
        var future = new CompletableFuture<P2PMessage>();
        QuicConstants.MSG_EXPECTED_CONNECTIONS.put(id, CONNECTION_ID);
        QuicConstants.MSG_RESPONSE_FUTURECACHE.put(id, future);
        var connection = new QuicConnection();
        connection.setPeerId(bytesToHex(sender));
        try (var codec = mockStatic(P2PMessage.class, CALLS_REAL_METHODS);
                var manager = mockStatic(QuicConnectionManager.class)) {
            manager.when(() -> QuicConnectionManager.getConnection(anyLong())).thenReturn(connection);
            assertTrue(QuicDataProcessor.completeResponse(QuicMsg.fromWire(CONNECTION_ID + 1, 2, wire)));
            assertFalse(future.isDone());
            QuicMsg incoming = QuicMsg.fromWire(CONNECTION_ID, 3, wire);
            connection.setPeerId("different peer");
            assertTrue(QuicDataProcessor.completeResponse(incoming));
            assertFalse(future.isDone());
            connection.setPeerId(bytesToHex(sender));
            assertTrue(QuicDataProcessor.completeResponse(incoming));
            assertSame(incoming.message(), future.getNow(null));
            codec.verify(() -> P2PMessage.deserialize(any(byte[].class)), times(2));
        } finally {
            QuicConstants.MSG_EXPECTED_CONNECTIONS.invalidate(id);
            QuicConstants.MSG_RESPONSE_FUTURECACHE.invalidate(id);
        }
    }

    @Test
    void invalidEnvelopeIsRejectedBeforeQueueAdmission() throws Exception {
        byte[] wire = P2PMessage.newRequestMessage(sender, ProtocolEnum.TEXT_V1, new byte[0]).serialize();
        wire[1] = 2;
        assertThrows(IOException.class, () -> QuicMsg.fromWire(CONNECTION_ID, 1, wire));
    }
}
