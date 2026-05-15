package com.bit.coin.p2p.production;

import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Slf4j
public final class P2PTransferErrorEvents {
    private static final CopyOnWriteArrayList<Consumer<P2PTransferErrorRecord>> LISTENERS = new CopyOnWriteArrayList<>();

    private P2PTransferErrorEvents() {
    }

    public static AutoCloseable subscribe(Consumer<P2PTransferErrorRecord> listener) {
        Objects.requireNonNull(listener, "listener");
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }

    public static void publish(P2PTransferErrorRecord record) {
        if (record == null) {
            return;
        }
        for (Consumer<P2PTransferErrorRecord> listener : LISTENERS) {
            try {
                listener.accept(record);
            } catch (RuntimeException e) {
                log.warn("[p2p-error-event] listener failed", e);
            }
        }
    }
}
