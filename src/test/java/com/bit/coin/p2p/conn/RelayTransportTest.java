package com.bit.coin.p2p.conn;

import com.bit.coin.p2p.production.P2PAddressValidator;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.net.InetSocketAddress;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RelayTransportTest {

    private static final String A = "01".repeat(32), B = "02".repeat(32), R = "03".repeat(32);
    private static final InetSocketAddress REMOTE = new InetSocketAddress("127.0.0.1", 8334);

    @AfterEach
    void reset() {
        RelayTransport.configure(false, Set.of(), 2 * 1024 * 1024);
    }

    @Test
    void parsesOneHopCircuitAndRejectsAmbiguousOrRecursiveAddresses() {
        String relay = "/ip4/127.0.0.1/quic/8334/p2p/" + R;
        var address = P2PAddressValidator.parseQuicAddress(relay + "/p2p-circuit/p2p/" + B);
        assertEquals(B, address.peerIdHex());
        assertEquals(relay, address.relayAddress());
        assertThrows(IllegalArgumentException.class,
                () -> P2PAddressValidator.parseQuicAddress(relay + "/p2p-circuit/p2p/" + R));
        assertThrows(IllegalArgumentException.class,
                () -> P2PAddressValidator.parseQuicAddress(address.rawAddress() + "/p2p-circuit/p2p/" + A));
    }

    @Test
    void forwardsOpaqueBytesWithAuthenticatedSourceAndRejectsMissingNestedAndDisabledTargets() {
        QuicConnection source = connection(1, A), target = connection(2, B);
        byte[] inner = new byte[400];
        new Random(42).nextBytes(inner);
        QuicFrame request = envelope(QuicFrameEnum.RELAY_FORWARD_FRAME, B, inner);
        List<QuicFrame> sent = new ArrayList<>();
        try (MockedStatic<QuicConnectionManager> manager = mockStatic(QuicConnectionManager.class)) {
            manager.when(() -> QuicConnectionManager.getConnectionByPeerId(B)).thenReturn(target);
            manager.when(() -> QuicConnectionManager.sendFrameWithoutResponse(any())).thenAnswer(call -> {
                sent.add(call.getArgument(0));
                return true;
            });
            RelayTransport.configure(false, Set.of(), 2 * 1024 * 1024);
            RelayTransport.receive(source, request);
            assertTrue(sent.isEmpty());
            RelayTransport.configure(true, Set.of(), 2 * 1024 * 1024);
            RelayTransport.receive(source, request);
            assertEquals(1, sent.size());
            assertEquals(2, sent.getFirst().getConnectionId());
            assertEquals(QuicFrameEnum.RELAY_DELIVER_FRAME.getCode(), sent.getFirst().getFrameType());
            assertArrayEquals(HexFormat.of().parseHex(A), Arrays.copyOf(sent.getFirst().getPayload(), 32));
            assertArrayEquals(inner, Arrays.copyOfRange(sent.getFirst().getPayload(), 32, 432));
            target.setRelayPath(new RelayPath(3, B));
            RelayTransport.receive(source, request);
            RelayTransport.receive(source, envelope(QuicFrameEnum.RELAY_FORWARD_FRAME, R, inner));
            RelayTransport.receive(source,
                    envelope(QuicFrameEnum.RELAY_FORWARD_FRAME, B, new byte[RelayTransport.INNER_DATAGRAM_BYTES + 1]));
            assertEquals(1, sent.size());
        } finally {
            source.release();
            target.release();
        }
    }

    @Test
    void innerAuthenticationIsBoundToRelaySessionAndCannotBeBypassedByDirectPacketsOrReplay() throws Exception {
        QuicConnection end = connection(77, B);
        end.setRelayPath(new RelayPath(9, B));
        RelayTransport.configure(false, Set.of(R), 2 * 1024 * 1024);
        try (SessionCipher sender = cipher(77, true);
                MockedStatic<QuicConnectionManager> manager = mockStatic(QuicConnectionManager.class)) {
            manager.when(() -> QuicConnectionManager.getConnection(77)).thenReturn(end);
            QuicFrame ping = ReceiveQuicDataTest.dataFrame(77, 99, 0, 1, new byte[0]);
            ping.setFrameType(QuicFrameEnum.PING_FRAME.getCode());
            byte[] encrypted = sender.encrypt(ping.serialize());
            assertThrows(java.security.GeneralSecurityException.class, () -> sender.decrypt(encrypted));
            end.setLastSeen(1);
            deliver(encrypted, null);
            deliver(encrypted, new RelayPath(8, B));
            deliver(encrypted, new RelayPath(9, A));
            assertEquals(1, end.getLastSeen());
            byte[] corrupt = encrypted.clone();
            corrupt[corrupt.length - 1] ^= 1;
            deliver(corrupt, end.getRelayPath());
            assertEquals(1, end.getLastSeen());
            deliver(encrypted, end.getRelayPath());
            assertTrue(end.getLastSeen() > 1);
            end.setLastSeen(2);
            deliver(encrypted, end.getRelayPath());
            assertEquals(2, end.getLastSeen());
        } finally {
            end.release();
        }
    }

    @Test
    void rejectsUntrustedDeliveriesBeforeProcessingInnerDatagram() {
        QuicConnection carrier = connection(9, R);
        try (MockedStatic<QuicConnectionManager> manager = mockStatic(QuicConnectionManager.class)) {
            RelayTransport.receive(carrier, envelope(QuicFrameEnum.RELAY_DELIVER_FRAME, B, new byte[100]));
            manager.verifyNoInteractions();
        } finally {
            carrier.release();
        }
    }

    @Test
    void unauthenticatedRelayFrameIsNotDispatched() {
        QuicFrame forward = envelope(QuicFrameEnum.RELAY_FORWARD_FRAME, B, new byte[100]);
        forward.setConnectionId(9);
        try (MockedStatic<QuicConnectionManager> manager = mockStatic(QuicConnectionManager.class)) {
            deliver(SessionCipher.handshake(9, forward.serialize()), null);
            manager.verifyNoInteractions();
        }
    }

    @Test
    void relayMtuIncludesBothEncryptionLayersAndLargestBitmapAck() {
        QuicConnection c = connection(9, B);
        c.setRelayPath(new RelayPath(8, B));
        assertEquals(1200,
                c.MAX_FRAME_PAYLOAD + 2 * (QuicFrame.FIXED_HEADER_LENGTH + SessionCipher.OVERHEAD_BYTES) + 32);
        assertTrue((QuicConstants.MAX_FRAME + 7) / 8 + 4 + 66 <= RelayTransport.INNER_DATAGRAM_BYTES);
        c.getPathMtuEstimator().onAckedBytes(1_000_000);
        assertEquals(RelayTransport.INNER_DATAGRAM_BYTES, c.getPathMtuEstimator().currentDatagramBytes());
        c.release();
    }

    @Test
    void relayBudgetBoundsSmallPacketFloodAndBandwidth() {
        RelayTransport.configure(true, Set.of(), 1200);
        assertTrue(RelayTransport.allowForward(A, B, 1200));
        assertFalse(RelayTransport.allowForward(A, B, 1));
        assertFalse(RelayTransport.allowForward(R, B, 1));
        for (int i = 0; i < 100; i++) {
            assertFalse(RelayTransport.allowForward(A, B, 1200));
        }
        assertTrue(RelayTransport.allowForward(R, "04".repeat(32), 1200),
                "Rejected traffic must not consume the global budget");
        assertThrows(IllegalArgumentException.class, () -> RelayTransport.configure(true, Set.of(), Long.MAX_VALUE));
    }

    private static void deliver(byte[] datagram, RelayPath route) {
        var buffer = Unpooled.wrappedBuffer(datagram);
        try {
            QuicServiceHandler.receiveDatagram(buffer, REMOTE, route);
        } finally {
            buffer.release();
        }
    }

    private static SessionCipher cipher(long id, boolean initiator) {
        return new SessionCipher(new byte[32], new byte[] { 1 }, new byte[] { 2 }, id, initiator);
    }

    private static QuicConnection connection(long id, String peer) {
        QuicConnection c = new QuicConnection();
        c.setConnectionId(id);
        c.setPeerId(peer);
        c.setRemoteAddress(REMOTE);
        c.setSessionCipher(cipher(id, false));
        return c;
    }

    private static QuicFrame envelope(QuicFrameEnum type, String peer, byte[] inner) {
        byte[] payload = new byte[32 + inner.length];
        System.arraycopy(HexFormat.of().parseHex(peer), 0, payload, 0, 32);
        System.arraycopy(inner, 0, payload, 32, inner.length);
        QuicFrame frame = ReceiveQuicDataTest.dataFrame(1, 10, 0, 1, payload);
        frame.setFrameType(type.getCode());
        return frame;
    }
}
