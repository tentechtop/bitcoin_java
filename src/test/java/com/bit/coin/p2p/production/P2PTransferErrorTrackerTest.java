package com.bit.coin.p2p.production;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class P2PTransferErrorTrackerTest {

    @Test
    void keepsRecentRecordsWithinGlobalLimitAndPreservesCounts() {
        P2PTransferErrorTracker tracker = new P2PTransferErrorTracker(200);

        for (int i = 0; i < 10_005; i++) {
            tracker.record(new P2PTransferErrorRecord(
                    "peer-" + i, "address-" + i, P2PErrorCode.INTERNAL_ERROR, "failure", i, 1, i, ""));
        }

        assertEquals(10_000, tracker.recentAll().size());
        assertEquals("peer-10004", tracker.recentAll().getFirst().peerId());
        assertEquals(1, tracker.count("peer-10004"));
    }

    @Test
    void limitsPerPeerResultsAndNormalizesPeerIds() {
        P2PTransferErrorTracker tracker = new P2PTransferErrorTracker(2);
        String peerId = "01".repeat(32);
        for (int i = 0; i < 4; i++) {
            tracker.record(new P2PTransferErrorRecord(peerId, "", P2PErrorCode.INTERNAL_ERROR,
                    "failure", i, 1, i, ""));
        }

        assertEquals(2, tracker.recent(peerId).size());
        assertEquals(4, tracker.count(peerId));
    }
}