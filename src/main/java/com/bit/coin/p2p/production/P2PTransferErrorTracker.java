package com.bit.coin.p2p.production;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.LongAdder;

@Component
public class P2PTransferErrorTracker {
    private static final int DEFAULT_PER_KEY_LIMIT = 200;
    private final ConcurrentHashMap<String, Deque<P2PTransferErrorRecord>> records = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> counts = new ConcurrentHashMap<>();
    private final int perKeyLimit;
    private AutoCloseable subscription;

    public P2PTransferErrorTracker() {
        this(DEFAULT_PER_KEY_LIMIT);
    }

    P2PTransferErrorTracker(int perKeyLimit) {
        this.perKeyLimit = Math.max(1, perKeyLimit);
    }

    @PostConstruct
    public void subscribeToTransportEvents() {
        subscription = P2PTransferErrorEvents.subscribe(this::record);
    }

    @PreDestroy
    public void unsubscribeFromTransportEvents() throws Exception {
        if (subscription != null) {
            subscription.close();
        }
    }

    public void record(P2PTransferErrorRecord record) {
        String key = keyOf(record);
        Deque<P2PTransferErrorRecord> queue = records.computeIfAbsent(key, ignored -> new ConcurrentLinkedDeque<>());
        synchronized (queue) {
            queue.addFirst(record);
            while (queue.size() > perKeyLimit) {
                queue.pollLast();
            }
        }
        counts.computeIfAbsent(key, ignored -> new LongAdder()).increment();
    }

    public void record(String peerId,
                       InetSocketAddress remoteAddress,
                       P2PErrorCode errorCode,
                       String message,
                       long dataId,
                       long connectionId,
                       Throwable cause) {
        record(P2PTransferErrorRecord.now(
                normalizePeerIdOrNull(peerId),
                remoteAddress == null ? "" : remoteAddress.toString(),
                errorCode,
                message,
                dataId,
                connectionId,
                cause
        ));
    }

    public List<P2PTransferErrorRecord> recent(String peerIdOrKey) {
        String key = normalizeKey(peerIdOrKey);
        Deque<P2PTransferErrorRecord> queue = records.get(key);
        if (queue == null) {
            return List.of();
        }
        return new ArrayList<>(queue);
    }

    public List<P2PTransferErrorRecord> recentAll() {
        List<P2PTransferErrorRecord> result = new ArrayList<>();
        for (Deque<P2PTransferErrorRecord> queue : records.values()) {
            result.addAll(queue);
        }
        result.sort(Comparator.comparingLong(P2PTransferErrorRecord::occurredAtMillis).reversed());
        return result;
    }

    public long count(String peerIdOrKey) {
        LongAdder count = counts.get(normalizeKey(peerIdOrKey));
        return count == null ? 0 : count.sum();
    }

    private String keyOf(P2PTransferErrorRecord record) {
        if (record.peerId() != null && !record.peerId().isBlank()) {
            return normalizeKey(record.peerId());
        }
        if (record.remoteAddress() != null && !record.remoteAddress().isBlank()) {
            return normalizeKey(record.remoteAddress());
        }
        return "unknown";
    }

    private String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            return "unknown";
        }
        return normalizePeerIdOrNull(key) == null
                ? key.trim().toLowerCase(Locale.ROOT)
                : normalizePeerIdOrNull(key);
    }

    private String normalizePeerIdOrNull(String peerId) {
        try {
            return P2PAddressValidator.normalizePeerId(peerId);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
