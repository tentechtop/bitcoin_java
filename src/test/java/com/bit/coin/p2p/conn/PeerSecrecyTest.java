package com.bit.coin.p2p.conn;

import com.bit.coin.p2p.peer.Peer;
import com.bit.coin.utils.Ed25519Signer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

class PeerSecrecyTest {
    @Test void publicSerializationNeverIncludesNodePrivateKey() {
        var keys = Ed25519Signer.generateKeyPair();
        Peer peer = new Peer(); peer.setId(Ed25519Signer.extractPublicKeyCore(keys.getPublic()));
        peer.setPrivateKey(Ed25519Signer.extractPrivateKeyCore(keys.getPrivate())); peer.setPort(18444); peer.setAddress("127.0.0.1");
        assertNull(Peer.deserialize(peer.serialize()).getPrivateKey());
        assertArrayEquals(peer.getPrivateKey(), Peer.deserialize(peer.serializeLocal()).getPrivateKey());
        String json = new JsonMapper().writeValueAsString(peer);
        assertFalse(json.contains("privateKey")); assertFalse(json.contains("sharedSecret"));
        assertFalse(peer.toString().contains("privateKey"));
    }
    @Test void malformedPeerLengthIsRejectedBeforeAllocation() {
        assertNull(Peer.deserialize(ByteBuffer.allocate(4096).putInt(Integer.MAX_VALUE).array()));
        assertNull(Peer.deserialize(new byte[4097]));
    }
}
