package com.bit.coin.p2p.production;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class P2PBlacklistTest {
    @Test
    void blocksAndUnblocksPeerAndIp() {
        AtomicLong now = new AtomicLong(1_000);
        P2PBlacklist blacklist = new P2PBlacklist(now::get);
        String peerHex = "a".repeat(64);

        P2PBlacklistEntry peerEntry = blacklist.blockPeerHex(peerHex, "bad handshake", Duration.ZERO);
        P2PBlacklistEntry ipEntry = blacklist.blockIp(" 127.0.0.1 ", "rate limit", Duration.ZERO);

        assertEquals(P2PBlacklistType.PEER, peerEntry.type());
        assertEquals(peerHex, peerEntry.key());
        assertEquals(P2PBlacklistType.IP, ipEntry.type());
        assertTrue(blacklist.findPeerHex(peerHex).isPresent());
        assertTrue(blacklist.findIp("127.0.0.1").isPresent());
        assertEquals(2, blacklist.entries().size());

        assertTrue(blacklist.unblockPeer(peerHex));
        assertTrue(blacklist.unblockIp("127.0.0.1"));
        assertFalse(blacklist.findPeerHex(peerHex).isPresent());
        assertFalse(blacklist.findIp("127.0.0.1").isPresent());
    }

    @Test
    void expiresTemporaryEntries() {
        AtomicLong now = new AtomicLong(1_000);
        P2PBlacklist blacklist = new P2PBlacklist(now::get);
        String peerHex = "b".repeat(64);

        blacklist.blockPeerHex(peerHex, "temporary failure", Duration.ofSeconds(5));

        assertTrue(blacklist.findPeerHex(peerHex).isPresent());
        now.set(5_999);
        assertTrue(blacklist.findPeerHex(peerHex).isPresent());
        now.set(6_000);
        Optional<P2PBlacklistEntry> expired = blacklist.findPeerHex(peerHex);
        assertFalse(expired.isPresent());
        assertTrue(blacklist.entries().isEmpty());
    }
}
