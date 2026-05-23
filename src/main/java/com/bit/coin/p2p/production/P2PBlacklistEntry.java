package com.bit.coin.p2p.production;

public record P2PBlacklistEntry(
        P2PBlacklistType type,
        String key,
        String reason,
        long createdAtMillis,
        long expiresAtMillis
) {
    public boolean permanent() {
        return expiresAtMillis <= 0;
    }

    public boolean expired(long nowMillis) {
        return !permanent() && nowMillis >= expiresAtMillis;
    }
}
