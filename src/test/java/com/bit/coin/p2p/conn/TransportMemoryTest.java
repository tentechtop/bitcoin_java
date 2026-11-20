package com.bit.coin.p2p.conn;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class TransportMemoryTest {

    @Test
    void globalBudgetCannotBeMultipliedAcrossConnections() {
        long original = TransportMemoryBudget.reservedBytes();
        int available = Math.toIntExact(96L * 1024 * 1024 - original);
        assertTrue(TransportMemoryBudget.reserve(available));
        try {
            assertFalse(TransportMemoryBudget.reserve(1));
            assertFalse(TransportMemoryBudget.reserve(-1));
        } finally {
            TransportMemoryBudget.release(available);
        }
        assertEquals(original, TransportMemoryBudget.reservedBytes());
    }

    @Test
    void partialReceiveAndPendingSendReleaseAllReservationsExactlyOnce() {
        long original = TransportMemoryBudget.reservedBytes();
        QuicConnection connection = new QuicConnection();
        connection.setConnectionId(91);
        connection.setPeerId("memory-test");
        connection.setRemoteAddress(new java.net.InetSocketAddress("127.0.0.1", 18444));
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 7);
        connection.setSharedSecret(secret);
        try {
            connection.handleFrame(ReceiveQuicDataTest.dataFrame(91, 2, 0, 2, new byte[] { 1, 2, 3 }));
            assertTrue(TransportMemoryBudget.reservedBytes() > original);
            connection.sendData(new byte[1024]);
            assertFalse(connection.getUnAckedDataMap().isEmpty());
        } finally {
            connection.release();
        }
        assertEquals(original, TransportMemoryBudget.reservedBytes());
        assertTrue(connection.getReceiveDataMap().isEmpty());
        assertTrue(connection.getUnAckedDataMap().isEmpty());
        connection.release();
        assertEquals(original, TransportMemoryBudget.reservedBytes());
    }
}
