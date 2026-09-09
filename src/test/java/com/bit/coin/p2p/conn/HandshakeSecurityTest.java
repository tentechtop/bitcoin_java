package com.bit.coin.p2p.conn;

import com.bit.coin.p2p.protocol.RequestConnect;
import com.bit.coin.utils.Ed25519Signer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HandshakeSecurityTest {
    private RequestConnect signed() {
        var keys = Ed25519Signer.generateKeyPair();
        RequestConnect r = new RequestConnect();
        r.setPeerId(Ed25519Signer.extractPublicKeyCore(keys.getPublic()));
        r.setLatestHash(new byte[32]); r.setSharedSecretPubKey(new byte[32]);
        r.bind(123, 45, 67, RequestConnect.REQUEST, null);
        r.setSignature(Ed25519Signer.fastSign(Ed25519Signer.extractPrivateKeyCore(keys.getPrivate()), r.getSignatureData()));
        return r;
    }
    @Test void bindsSignatureToNetworkDirectionIdentifiersAndFreshness() {
        RequestConnect r = RequestConnect.deserialize(signed().serialize()); long now = r.getTimestamp();
        assertTrue(r.verifyContext(123, 45, 67, RequestConnect.REQUEST, null, now));
        assertFalse(r.verifyContext(124, 45, 67, RequestConnect.REQUEST, null, now));
        assertFalse(r.verifyContext(123, 46, 67, RequestConnect.REQUEST, null, now));
        assertFalse(r.verifyContext(123, 45, 68, RequestConnect.REQUEST, null, now));
        assertFalse(r.verifyContext(123, 45, 67, RequestConnect.RESPONSE, null, now));
        assertFalse(r.verifyContext(123, 45, 67, RequestConnect.REQUEST, null, now + 61));
        assertFalse(r.verifyContext(123, 45, 67, RequestConnect.REQUEST, null, now - 61));
        r.getNonce()[0] ^= 1; assertFalse(r.verifySignature());
    }
    @Test void rejectsMalformedLengthsAndWrongResponseChallenge() {
        RequestConnect r = signed();
        assertThrows(IllegalArgumentException.class, () -> RequestConnect.deserialize(new byte[200]));
        assertThrows(IllegalArgumentException.class, () -> RequestConnect.deserialize(new byte[RequestConnect.WIRE_BYTES + 1]));
        byte[] wrongChallenge = new byte[32]; wrongChallenge[0] = 1;
        assertFalse(r.verifyContext(123, 45, 67, RequestConnect.REQUEST, wrongChallenge, r.getTimestamp()));
        r.setPeerId(new byte[31]); assertFalse(r.verifySignature());
        assertThrows(IllegalArgumentException.class, r::serialize);
    }
}
