package com.bit.coin.net;

import com.bit.coin.utils.UInt256;
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
        when(net.getCurrentLocalChainWork()).thenReturn(UInt256.fromLong(10));
        AtomicLong now = new AtomicLong(100_000);
        SmartSyncTrigger trigger = new SmartSyncTrigger(net, now::get);
        try {
            assertTrue(trigger.triggerIfRemoteAhead(UInt256.fromLong(11), "first"));
            when(progress.running()).thenReturn(true);
            assertFalse(trigger.triggerIfRemoteAhead(UInt256.fromLong(13), "newer"));
            now.addAndGet(6000); trigger.checkPending();
            verify(net, times(1)).startSyncBlockHeadersFirst();
            when(progress.running()).thenReturn(false);
            when(net.getCurrentLocalChainWork()).thenReturn(UInt256.fromLong(11));
            trigger.checkPending();
            verify(net, times(2)).startSyncBlockHeadersFirst();
            when(net.getCurrentLocalChainWork()).thenReturn(UInt256.fromLong(13));
            now.addAndGet(6000); trigger.checkPending();
            verify(net, times(2)).startSyncBlockHeadersFirst();
        } finally { trigger.stop(); }
    }

    @Test void staleRemoteHintDoesNotTriggerUnboundedRetries() {
        NetService net = mock(NetService.class);
        SyncProgress progress = mock(SyncProgress.class);
        when(net.getSyncProgress()).thenReturn(progress);
        when(net.startSyncBlockHeadersFirst()).thenReturn(progress);
        when(net.getCurrentLocalChainWork()).thenReturn(UInt256.ZERO);
        AtomicLong now = new AtomicLong(100_000);
        SmartSyncTrigger trigger = new SmartSyncTrigger(net, now::get);
        try {
            trigger.triggerIfRemoteAhead(UInt256.fromLong(100), "hint");
            now.addAndGet(60_001); trigger.checkPending();
            verify(net, times(1)).startSyncBlockHeadersFirst();
        } finally { trigger.stop(); }
    }

    @Test void ignoresMissingEqualAndLowerWork() {
        NetService net = mock(NetService.class);
        when(net.getCurrentLocalChainWork()).thenReturn(UInt256.fromLong(10));
        SmartSyncTrigger trigger = new SmartSyncTrigger(net, () -> 100_000L);
        try {
            assertFalse(trigger.triggerIfRemoteAhead(null, "missing"));
            assertFalse(trigger.triggerIfRemoteAhead(UInt256.fromLong(10), "equal"));
            assertFalse(trigger.triggerIfRemoteAhead(UInt256.fromLong(9), "lower"));
            trigger.checkPending();
            verify(net, never()).startSyncBlockHeadersFirst();
        } finally { trigger.stop(); }
    }
}
