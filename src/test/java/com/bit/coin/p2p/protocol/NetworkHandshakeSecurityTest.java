package com.bit.coin.p2p.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkHandshakeSecurityTest {

    @Test
    void sharedSecretIsNeverSerializedIntoPublicHandshake() throws Exception {
        NetworkHandshake handshake = new NetworkHandshake();
        byte[] secret = new byte[32];
        secret[0] = 7;
        handshake.setSharedSecret(secret);

        byte[] wire = handshake.serialize();
        NetworkHandshake decoded = NetworkHandshake.deserialize(wire);

        assertTrue(decoded.getSharedSecret() == null || decoded.getSharedSecret().length == 0);
        assertArrayEquals(new byte[0], decoded.toProto().getSharedSecret().toByteArray());
    }

    @Test
    void oversizedHandshakeIsRejectedBeforeProtobufParsing() {
        assertThrows(java.io.IOException.class,
                () -> NetworkHandshake.deserialize(new byte[NetworkHandshake.MAX_SERIALIZED_BYTES + 1]));
    }
}
