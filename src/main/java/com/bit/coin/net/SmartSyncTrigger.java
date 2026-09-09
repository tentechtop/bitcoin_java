package com.bit.coin.net;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

@Slf4j
@Component
public class SmartSyncTrigger {
    private static final long AUTO_SYNC_DEBOUNCE_MILLIS = 5_000L;

    private final NetService netService;
    private final AtomicLong lastAutoSyncAtMillis = new AtomicLong(0L);
    private final LongSupplier clock;
    private int pendingHeight;
    private long pendingUntil;
    @org.springframework.beans.factory.annotation.Autowired private com.bit.coin.config.SystemConfig systemConfig;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "pending-chain-sync"); t.setDaemon(true); return t;
    });

    @org.springframework.beans.factory.annotation.Autowired
    public SmartSyncTrigger(NetService netService) {
        this(netService, System::currentTimeMillis);
    }

    SmartSyncTrigger(NetService netService, LongSupplier clock) {
        this.netService = netService;
        this.clock = clock;
    }

    @jakarta.annotation.PostConstruct void start() {
        if (systemConfig.isNetworkingEnabled()) executor.scheduleWithFixedDelay(() -> {
            try { checkPending(); } catch (RuntimeException e) { log.debug("Pending sync failed: {}", e.toString()); }
        }, 1, 1, TimeUnit.SECONDS);
    }

    @jakarta.annotation.PreDestroy void stop() { executor.shutdownNow(); }

    public synchronized boolean triggerIfRemoteAhead(int remoteHeight, String reason) {
        int localHeight = netService.getCurrentLocalHeight();
        if (remoteHeight <= localHeight) {
            return false;
        }
        pendingHeight = Math.max(pendingHeight, remoteHeight);
        pendingUntil = clock.getAsLong() + 60_000;
        return tryPending(reason);
    }

    synchronized void checkPending() { tryPending("deferred-announcement"); }

    private boolean tryPending(String reason) {
        long now = clock.getAsLong();
        int localHeight = netService.getCurrentLocalHeight();
        if (pendingHeight <= localHeight || now > pendingUntil) { pendingHeight = 0; return false; }
        SyncProgress current = netService.getSyncProgress();
        if (current != null && current.running()) return false;
        long last = lastAutoSyncAtMillis.get();
        if (now - last < AUTO_SYNC_DEBOUNCE_MILLIS) {
            return false;
        }
        if (!lastAutoSyncAtMillis.compareAndSet(last, now)) {
            return false;
        }

        SyncProgress progress = netService.startSyncBlockHeadersFirst();
        log.info("Trigger headers-first sync: reason={}, remoteHeight={}, localHeight={}, status={}, message={}",
                reason, pendingHeight, localHeight, progress.status(), progress.message());
        return true;
    }

    public int getLocalHeight() {
        return netService.getCurrentLocalHeight();
    }
}
