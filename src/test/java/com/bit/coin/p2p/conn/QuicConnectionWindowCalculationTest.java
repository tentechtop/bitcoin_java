package com.bit.coin.p2p.conn;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;

import static com.bit.coin.p2p.conn.FrameQueueElement.PRIORITY_NORMAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuicConnectionWindowCalculationTest {
    private static final InetSocketAddress REMOTE = new InetSocketAddress("127.0.0.1", 8334);

    @AfterEach
    void restoreGlobalOutboundBytes() {
        QuicConnectionManager.refundGlobalOutboundBytes((int) P2PGlobalTrafficController.DEFAULT_BURST_BYTES);
    }

    @Test
    void doesNotReserveWhenConnectionWindowCannotFitNextFrame() throws Exception {
        QuicConnection connection = newConnectionWithPendingFrame(1_000);
        connection.getRemoteRwnd().set(999);

        int reservedBytes = calculateAvailableSendBytes(connection);

        assertEquals(0, reservedBytes);
        assertTrue(QuicConnectionManager.getGlobalOutboundAvailableBytes() >= 0);
    }

    @Test
    void refundsGlobalReservationWhenGrantCannotFitNextFrame() throws Exception {
        drainGlobalOutboundBytes();
        QuicConnectionManager.refundGlobalOutboundBytes(100);
        long beforeAvailableBytes = QuicConnectionManager.getGlobalOutboundAvailableBytes();
        QuicConnection connection = newConnectionWithPendingFrame(100_000);
        connection.getCongestionWindow().onAckedBytes(200_000);

        int reservedBytes = calculateAvailableSendBytes(connection);
        long afterAvailableBytes = QuicConnectionManager.getGlobalOutboundAvailableBytes();

        assertEquals(0, reservedBytes);
        assertTrue(afterAvailableBytes >= beforeAvailableBytes);
    }

    @Test
    void clampsRemoteReceiveWindowAdvertisement() throws Exception {
        QuicConnection connection = new QuicConnection();

        updateRemoteReceiveWindow(connection, -1);
        assertEquals(0, connection.getRemoteRwnd().get());

        updateRemoteReceiveWindow(connection, QuicConnection.INIT_WINDOW_SIZE * 2);
        assertEquals(QuicConnection.INIT_WINDOW_SIZE, connection.getRemoteRwnd().get());
    }

    private QuicConnection newConnectionWithPendingFrame(int frameBytes) {
        QuicConnection connection = new QuicConnection();
        connection.setConnectionId(1L);
        connection.setPeerId("peer-a");
        connection.setRemoteAddress(REMOTE);

        SendQuicData sendData = new SendQuicData();
        sendData.setConnectionId(1L);
        sendData.setDataId(1L);
        sendData.setTotal(1);
        sendData.setFrameArray(new QuicFrame[] {dataFrame(frameBytes)});
        connection.getUnAckedDataMap().put(1L, sendData);
        connection.getFrameSendQueue().batchEnqueuePending(
                java.util.List.of(new BoundedFrameSendQueue.FrameMeta(1L, 0, frameBytes)),
                PRIORITY_NORMAL
        );
        return connection;
    }

    private QuicFrame dataFrame(int frameBytes) {
        QuicFrame frame = new QuicFrame();
        frame.setConnectionId(1L);
        frame.setDataId(1L);
        frame.setSequence(0);
        frame.setTotal(1);
        frame.setFrameType(QuicFrameEnum.DATA_FRAME.getCode());
        frame.setFrameTotalLength(frameBytes);
        frame.setRemoteAddress(REMOTE);
        return frame;
    }

    private int calculateAvailableSendBytes(QuicConnection connection) throws Exception {
        Method method = QuicConnection.class.getDeclaredMethod("calculateAvailableSendBytes");
        method.setAccessible(true);
        return (int) method.invoke(connection);
    }

    private void updateRemoteReceiveWindow(QuicConnection connection, int advertisedWindowBytes)
            throws Exception {
        Method method = QuicConnection.class.getDeclaredMethod("updateRemoteReceiveWindow", int.class);
        method.setAccessible(true);
        method.invoke(connection, advertisedWindowBytes);
    }

    private void drainGlobalOutboundBytes() {
        int drainedBytes;
        do {
            drainedBytes = QuicConnectionManager.reserveGlobalOutboundBytes(Integer.MAX_VALUE);
        } while (drainedBytes > 0);
    }
}
