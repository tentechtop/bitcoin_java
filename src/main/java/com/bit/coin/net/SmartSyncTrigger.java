package com.bit.coin.net;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class SmartSyncTrigger {
    private static final long AUTO_SYNC_DEBOUNCE_MILLIS = 5_000L;

    private final NetService netService;
    private final AtomicLong lastAutoSyncAtMillis = new AtomicLong(0L);

    public SmartSyncTrigger(NetService netService) {
        this.netService = netService;
    }

    public boolean triggerIfRemoteAhead(int remoteHeight, String reason) {
        int localHeight = netService.getCurrentLocalHeight();
        if (remoteHeight <= localHeight) {
            return false;
        }

        long now = System.currentTimeMillis();
        long last = lastAutoSyncAtMillis.get();
        if (now - last < AUTO_SYNC_DEBOUNCE_MILLIS) {
            return false;
        }
        if (!lastAutoSyncAtMillis.compareAndSet(last, now)) {
            return false;
        }

        SyncProgress progress = netService.startSyncBlockHeadersFirst();
        log.info("Trigger headers-first sync: reason={}, remoteHeight={}, localHeight={}, status={}, message={}",
                reason, remoteHeight, localHeight, progress.status(), progress.message());
        return true;
    }

    public int getLocalHeight() {
        return netService.getCurrentLocalHeight();
    }
}
