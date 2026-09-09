package com.bit.coin.net;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmartSyncTriggerTest {
    @Test void preservesNewerAnnouncementDuringDebounceAndRunningSync() {
        NetService net = mock(NetService.class);
        SyncProgress progress = mock(SyncProgress.class);
        when(net.getSyncProgress()).thenReturn(progress);
        when(net.startSyncBlockHeadersFirst()).thenReturn(progress);
        AtomicLong now = new AtomicLong(100_000);
        SmartSyncTrigger trigger = new SmartSyncTrigger(net, now::get);
        try {
            assertTrue(trigger.triggerIfRemoteAhead(1, "first"));
            when(progress.running()).thenReturn(true);
            assertFalse(trigger.triggerIfRemoteAhead(3, "newer"));
            now.addAndGet(6000); trigger.checkPending();
            verify(net, times(1)).startSyncBlockHeadersFirst();
            when(progress.running()).thenReturn(false);
            when(net.getCurrentLocalHeight()).thenReturn(1);
            trigger.checkPending();
            verify(net, times(2)).startSyncBlockHeadersFirst();
            when(net.getCurrentLocalHeight()).thenReturn(3);
            now.addAndGet(6000); trigger.checkPending();
            verify(net, times(2)).startSyncBlockHeadersFirst();
        } finally { trigger.stop(); }
    }

    @Test void staleRemoteHintDoesNotTriggerUnboundedRetries() {
        NetService net = mock(NetService.class);
        SyncProgress progress = mock(SyncProgress.class);
        when(net.getSyncProgress()).thenReturn(progress);
        when(net.startSyncBlockHeadersFirst()).thenReturn(progress);
        AtomicLong now = new AtomicLong(100_000);
        SmartSyncTrigger trigger = new SmartSyncTrigger(net, now::get);
        try {
            trigger.triggerIfRemoteAhead(100, "hint");
            now.addAndGet(60_001); trigger.checkPending();
            verify(net, times(1)).startSyncBlockHeadersFirst();
        } finally { trigger.stop(); }
    }
}
