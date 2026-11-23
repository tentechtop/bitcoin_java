package com.bit.coin.p2p.production;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

@Component
public class P2PTransferErrorTracker {

    private static final int DEFAULT_PER_KEY_LIMIT = 200;
    private static final int MAX_RECORDS = 10_000;
    private final Deque<P2PTransferErrorRecord> records = new ConcurrentLinkedDeque<>();
    private final ConcurrentSkipListMap<Long, String> countKeyOrder = new ConcurrentSkipListMap<>();
    private final ConcurrentHashMap<String, CountEntry> counts = new ConcurrentHashMap<>();
    private final AtomicLong countSequence = new AtomicLong();
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
        P2PTransferErrorRecord keyedRecord = new P2PTransferErrorRecord(
                key, record.remoteAddress(), record.errorCode(), record.message(), record.dataId(),
                record.connectionId(), record.occurredAtMillis(), record.exceptionClass());
        records.addFirst(keyedRecord);
        while (records.size() > MAX_RECORDS) {
            records.pollLast();
        }
        CountEntry entry = counts.computeIfAbsent(key, ignored -> {
            long sequence = countSequence.incrementAndGet();
            countKeyOrder.put(sequence, key);
            return new CountEntry(sequence);
        });
        entry.count.increment();
        while (counts.size() > MAX_RECORDS) {
            var oldest = countKeyOrder.pollFirstEntry();
            if (oldest == null) {
                break;
            }
            CountEntry oldestEntry = counts.get(oldest.getValue());
            if (oldestEntry != null && oldestEntry.sequence == oldest.getKey()) {
                counts.remove(oldest.getValue(), oldestEntry);
            }
        }
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
                cause));
    }

    public List<P2PTransferErrorRecord> recent(String peerIdOrKey) {
        String key = normalizeKey(peerIdOrKey);
        return records.stream()
                .filter(record -> key.equals(keyOf(record)))
                .limit(perKeyLimit)
                .toList();
    }

    public List<P2PTransferErrorRecord> recentAll() {
        return new java.util.ArrayList<>(records);
    }

    public long count(String peerIdOrKey) {
        CountEntry count = counts.get(normalizeKey(peerIdOrKey));
        return count == null ? 0 : count.count.sum();
    }

    private static final class CountEntry {

        private final long sequence;
        private final LongAdder count = new LongAdder();

        private CountEntry(long sequence) {
            this.sequence = sequence;
        }
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
