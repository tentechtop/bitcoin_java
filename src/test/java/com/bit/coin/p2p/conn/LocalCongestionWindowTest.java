package com.bit.coin.p2p.conn;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalCongestionWindowTest {
    @Test
    void slowStartGrowsByActualAckedBytes() {
        LocalCongestionWindow window = new LocalCongestionWindow();

        window.onAckedBytes(100, LocalCongestionWindow.MSS);

        assertEquals(LocalCongestionWindow.INITIAL_CWND + 100, window.get());
    }

    @Test
    void congestionAvoidanceAccumulatesSmallAckRemainder() {
        LocalCongestionWindow window = new LocalCongestionWindow();

        window.onCongestionEvent();
        int afterLossWindow = window.get();
        window.onAckedBytes(1, LocalCongestionWindow.MSS);
        assertEquals(afterLossWindow, window.get());

        for (int index = 0; index < afterLossWindow; index++) {
            window.onAckedBytes(1, LocalCongestionWindow.MSS);
        }

        assertTrue(window.get() > afterLossWindow);
        assertTrue(window.get() <= afterLossWindow + LocalCongestionWindow.MSS + 1);
    }

    @Test
    void timeoutResetsWindowToMinimumAndKeepsThresholdAtHalf() {
        LocalCongestionWindow window = new LocalCongestionWindow();

        window.onAckedBytes(20_000, LocalCongestionWindow.MSS);
        int beforeTimeoutWindow = window.get();
        window.onTimeout();

        assertEquals(LocalCongestionWindow.MIN_CWND, window.get());
        assertEquals(beforeTimeoutWindow / 2, window.getSlowStartThreshold());
    }

    @Test
    void windowNeverExceedsMaximum() {
        LocalCongestionWindow window = new LocalCongestionWindow();

        for (int index = 0; index < 20; index++) {
            window.onAckedBytes(Integer.MAX_VALUE, LocalCongestionWindow.MSS);
        }

        assertEquals(LocalCongestionWindow.MAX_CWND, window.get());
    }
}
