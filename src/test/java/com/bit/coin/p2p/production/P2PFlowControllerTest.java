package com.bit.coin.p2p.production;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class P2PFlowControllerTest {
    @Test
    void controlsInboundReceiveWindow() {
        P2PFlowController flow = new P2PFlowController(1_000,
                new AimdCongestionController(100, 200, 1_000, 10_000, 2_000));

        assertTrue(flow.tryAcquireInbound(400));
        assertFalse(flow.tryAcquireInbound(700));
        assertEquals(600, flow.snapshot().localReceiveWindowBytes());

        flow.releaseInbound(300);
        assertEquals(900, flow.snapshot().localReceiveWindowBytes());
        flow.releaseInbound(500);
        assertEquals(1_000, flow.snapshot().localReceiveWindowBytes());
    }

    @Test
    void limitsOutboundByRemoteWindowAndCongestionWindow() {
        P2PFlowController flow = new P2PFlowController(1_000,
                new AimdCongestionController(100, 200, 1_000, 10_000, 2_000));

        flow.updateRemoteReceiveWindow(500);
        assertTrue(flow.tryReserveOutbound(400));
        assertFalse(flow.tryReserveOutbound(101));

        FlowControlSnapshot reserved = flow.snapshot();
        assertEquals(500, reserved.remoteReceiveWindowBytes());
        assertEquals(400, reserved.congestion().inflightBytes());
        assertEquals(100, reserved.congestion().availableBytes());

        flow.onAck(400, 1_000);
        assertEquals(1_000, flow.snapshot().congestion().availableBytes());
        assertTrue(flow.tryReserveOutbound(1_000));

        flow.updateRemoteReceiveWindow(0);
        assertFalse(flow.tryReserveOutbound(1));
    }
}
