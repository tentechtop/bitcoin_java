package com.bit.coin.p2p.production;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

@Component
public class P2PBlacklist {
    private final ConcurrentHashMap<String, P2PBlacklistEntry> peerEntries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, P2PBlacklistEntry> ipEntries = new ConcurrentHashMap<>();
    private final LongSupplier nowMillis;

    public P2PBlacklist() {
        this(System::currentTimeMillis);
    }

    P2PBlacklist(LongSupplier nowMillis) {
        this.nowMillis = nowMillis;
    }

    public P2PBlacklistEntry blockPeer(String peerId, String reason, Duration ttl) {
        String peerIdHex = P2PAddressValidator.normalizePeerId(peerId);
        P2PBlacklistEntry entry = newEntry(P2PBlacklistType.PEER, peerIdHex, reason, ttl);
        peerEntries.put(peerIdHex, entry);
        return entry;
    }

    public P2PBlacklistEntry blockPeerHex(String peerIdHex, String reason, Duration ttl) {
        String normalized = P2PAddressValidator.normalizePeerId(peerIdHex);
        P2PBlacklistEntry entry = newEntry(P2PBlacklistType.PEER, normalized, reason, ttl);
        peerEntries.put(normalized, entry);
        return entry;
    }

    public P2PBlacklistEntry blockIp(String ipAddress, String reason, Duration ttl) {
        String normalized = normalizeIp(ipAddress);
        P2PAddressValidator.validateIp(normalized.contains(":") ? "ip6" : "ip4", normalized);
        P2PBlacklistEntry entry = newEntry(P2PBlacklistType.IP, normalized, reason, ttl);
        ipEntries.put(normalized, entry);
        return entry;
    }

    public boolean unblockPeer(String peerId) {
        return peerEntries.remove(P2PAddressValidator.normalizePeerId(peerId)) != null;
    }

    public boolean unblockIp(String ipAddress) {
        return ipEntries.remove(normalizeIp(ipAddress)) != null;
    }

    public Optional<P2PBlacklistEntry> find(P2PAddress address) {
        cleanupExpired();
        Optional<P2PBlacklistEntry> peerEntry = findPeerHex(address.peerIdHex());
        if (peerEntry.isPresent()) {
            return peerEntry;
        }
        return findIp(address.ipAddress());
    }

    public Optional<P2PBlacklistEntry> findPeerHex(String peerIdHex) {
        cleanupExpired();
        return Optional.ofNullable(peerEntries.get(P2PAddressValidator.normalizePeerId(peerIdHex)));
    }

    public Optional<P2PBlacklistEntry> findIp(String ipAddress) {
        cleanupExpired();
        return Optional.ofNullable(ipEntries.get(normalizeIp(ipAddress)));
    }

    public boolean isBlocked(P2PAddress address) {
        return find(address).isPresent();
    }

    public List<P2PBlacklistEntry> entries() {
        cleanupExpired();
        List<P2PBlacklistEntry> result = new ArrayList<>(peerEntries.values());
        result.addAll(ipEntries.values());
        result.sort((a, b) -> Long.compare(b.createdAtMillis(), a.createdAtMillis()));
        return result;
    }

    private P2PBlacklistEntry newEntry(P2PBlacklistType type, String key, String reason, Duration ttl) {
        long now = nowMillis.getAsLong();
        long expiresAt = ttl == null || ttl.isZero() || ttl.isNegative() ? 0 : now + ttl.toMillis();
        return new P2PBlacklistEntry(type, key, normalizeReason(reason), now, expiresAt);
    }

    private String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? "manual" : reason.trim();
    }

    private String normalizeIp(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            throw new IllegalArgumentException("ipAddress is required");
        }
        return ipAddress.trim().toLowerCase(Locale.ROOT);
    }

    private void cleanupExpired() {
        long now = nowMillis.getAsLong();
        peerEntries.entrySet().removeIf(entry -> entry.getValue().expired(now));
        ipEntries.entrySet().removeIf(entry -> entry.getValue().expired(now));
    }
}
