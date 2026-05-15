package com.bit.coin.p2p.production;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AimdCongestionControllerTest {
    @Test
    void reservesWindowGrowsOnAckAndShrinksOnLoss() {
        AimdCongestionController controller = new AimdCongestionController(100, 200, 1_000, 10_000, 2_000);

        assertTrue(controller.tryReserve(600, 700));
        assertFalse(controller.tryReserve(101, 700));
        CongestionControlSnapshot reserved = controller.snapshot(700);
        assertEquals(1_000, reserved.congestionWindowBytes());
        assertEquals(600, reserved.inflightBytes());
        assertEquals(100, reserved.availableBytes());

        controller.onAck(0);
        assertEquals(600, controller.snapshot(700).inflightBytes());
        assertEquals(1_000, controller.snapshot(700).congestionWindowBytes());

        controller.onAck(300);
        CongestionControlSnapshot acked = controller.snapshot(700);
        assertEquals(1_300, acked.congestionWindowBytes());
        assertEquals(300, acked.inflightBytes());
        assertEquals(400, acked.availableBytes());

        controller.onLoss(100);
        CongestionControlSnapshot lost = controller.snapshot(700);
        assertEquals(650, lost.congestionWindowBytes());
        assertEquals(650, lost.slowStartThresholdBytes());
        assertEquals(200, lost.inflightBytes());

        controller.onTimeout();
        CongestionControlSnapshot timeout = controller.snapshot(700);
        assertEquals(200, timeout.congestionWindowBytes());
        assertEquals(0, timeout.inflightBytes());
        assertEquals(200, timeout.availableBytes());
    }
}
