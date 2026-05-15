package com.bit.coin.p2p.production;

import org.bitcoinj.core.Base58;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class P2PAddressValidatorTest {
    @Test
    void parsesValidQuicMultiAddressAndNormalizesPeerId() {
        byte[] peerBytes = peerBytes();
        String peerBase58 = Base58.encode(peerBytes);
        String raw = "/ip4/127.0.0.1/quic/8334/p2p/" + peerBase58;

        P2PAddress address = P2PAddressValidator.parseQuicAddress(raw);

        assertEquals(raw, address.rawAddress());
        assertEquals("ip4", address.ipType());
        assertEquals("127.0.0.1", address.ipAddress());
        assertEquals(8334, address.port());
        assertEquals(peerBase58, address.peerIdBase58());
        assertEquals(HexFormat.of().formatHex(peerBytes), address.peerIdHex());
        assertEquals("127.0.0.1:8334", address.endpointKey());
    }

    @Test
    void acceptsUppercaseHexPeerIds() {
        String peerHex = HexFormat.of().formatHex(peerBytes()).toUpperCase();

        String normalized = P2PAddressValidator.normalizePeerId(peerHex);

        assertEquals(peerHex.toLowerCase(), normalized);
    }

    @Test
    void rejectsInvalidMultiAddressParts() {
        String peerBase58 = Base58.encode(peerBytes());

        assertThrows(IllegalArgumentException.class,
                () -> P2PAddressValidator.parseQuicAddress("/ip4/999.1.1.1/quic/8334/p2p/" + peerBase58));
        assertThrows(IllegalArgumentException.class,
                () -> P2PAddressValidator.parseQuicAddress("/ip4/127.0.0.1/tcp/8334/p2p/" + peerBase58));
        assertThrows(IllegalArgumentException.class,
                () -> P2PAddressValidator.parseQuicAddress("/ip4/127.0.0.1/quic/0/p2p/" + peerBase58));
        assertThrows(IllegalArgumentException.class,
                () -> P2PAddressValidator.parseQuicAddress("/ip4/127.0.0.1/quic/8334/p2p/abc"));
    }

    private static byte[] peerBytes() {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i + 1);
        }
        return bytes;
    }
}
