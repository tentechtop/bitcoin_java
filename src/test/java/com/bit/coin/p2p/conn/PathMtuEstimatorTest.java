package com.bit.coin.p2p.conn;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathMtuEstimatorTest {
    @Test
    void shrinksAfterRepeatedPotentialBlackHole() {
        PathMtuEstimator estimator = new PathMtuEstimator(1200, 1320, 1429);

        estimator.onPotentialBlackHole();
        assertEquals(1320, estimator.currentDatagramBytes());

        estimator.onPotentialBlackHole();
        assertTrue(estimator.currentDatagramBytes() < 1320);
        assertTrue(estimator.currentDatagramBytes() >= 1200);
    }
}
