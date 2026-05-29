package com.bit.coin.p2p.conn;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class P2PGlobalTrafficControllerTest {
    @Test
    void reservesAndRefundsGlobalBytes() {
        P2PGlobalTrafficController controller = new P2PGlobalTrafficController(1_000, 100);

        assertEquals(80, controller.reserve(80));
        assertEquals(20, controller.reserve(80));
        assertEquals(0, controller.reserve(1));

        controller.refund(50);
        assertEquals(50, controller.reserve(80));
    }
}
