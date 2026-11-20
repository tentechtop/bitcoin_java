package com.bit.coin.net;

import com.bit.coin.utils.UInt256;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;
import java.util.function.LongSupplier;

@Slf4j
@Component
public class SmartSyncTrigger {

    private static final long AUTO_SYNC_DEBOUNCE_MILLIS = 5_000L;  // 两次自动启动同步的最短间隔，单位为毫秒

    private final NetService netService;
    private final LongSupplier clock;
    private long lastAutoSyncAtMillis;
    private UInt256 pendingWork = UInt256.ZERO;
    private long pendingUntil;
    @org.springframework.beans.factory.annotation.Autowired
    private com.bit.coin.config.SystemConfig systemConfig;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "pending-chain-sync");
        t.setDaemon(true);
        return t;
    });

    @org.springframework.beans.factory.annotation.Autowired
    public SmartSyncTrigger(NetService netService) {
        this(netService, System::currentTimeMillis);
    }

    SmartSyncTrigger(NetService netService, LongSupplier clock) {
        this.netService = netService;
        this.clock = clock;
    }

    @jakarta.annotation.PostConstruct
    void start() {
        if (systemConfig.isNetworkingEnabled()) {
            executor.scheduleWithFixedDelay(() -> {
                try {
                    checkPending();
                } catch (RuntimeException e) {
                    log.debug("Pending sync failed: {}", e.toString());
                }
            }, 1, 1, TimeUnit.SECONDS);
        }
    }

    @jakarta.annotation.PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    /** 暂存累计工作量领先的通告；返回 true 表示本次已触发区块头优先同步。 */
    public synchronized boolean triggerIfRemoteAhead(UInt256 remoteWork, String reason) {
        if (remoteWork == null || remoteWork.compareTo(netService.getCurrentLocalChainWork()) <= 0) {
            return false;
        }
        long now = clock.getAsLong();
        if (now > pendingUntil || remoteWork.compareTo(pendingWork) >= 0) {
            pendingWork = remoteWork;
            pendingUntil = now + 60_000;  // 保留通告一分钟，供同步忙碌或处于冷却期时重试
        }
        return tryPending(reason);
    }

    synchronized void checkPending() {
        tryPending("deferred-announcement");
    }

    private boolean tryPending(String reason) {
        if (pendingWork.equals(UInt256.ZERO)) {
            return false;
        }
        long now = clock.getAsLong();
        UInt256 localWork = netService.getCurrentLocalChainWork();
        if (now > pendingUntil || pendingWork.compareTo(localWork) <= 0) {
            pendingWork = UInt256.ZERO;
            return false;
        }
        SyncProgress current = netService.getSyncProgress();
        if (current != null && current.running()) {
            return false;
        }
        if (now - lastAutoSyncAtMillis < AUTO_SYNC_DEBOUNCE_MILLIS) {
            return false;
        }
        lastAutoSyncAtMillis = now;

        SyncProgress progress = netService.startSyncBlockHeadersFirst();
        log.info("Trigger headers-first sync: reason={}, remoteWork={}, localWork={}, status={}, message={}",
                reason, pendingWork, localWork, progress.status(), progress.message());
        return true;
    }

    public int getLocalHeight() {
        return netService.getCurrentLocalHeight();
    }
}
