package com.bit.coin.p2p.conn;

import org.junit.jupiter.api.Test;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class SessionCipherTest {
    private final byte[] secret = new byte[32];
    private SessionCipher peer(boolean outbound) { return new SessionCipher(secret, new byte[]{1}, new byte[]{2}, 77, outbound); }
    @Test void authenticatesBothDirectionsAndRejectsReflection() throws Exception {
        try (SessionCipher client = peer(true); SessionCipher server = peer(false)) {
            byte[] wire = client.encrypt(new byte[]{7, 8});
            assertThrows(GeneralSecurityException.class, () -> client.decrypt(wire));
            assertArrayEquals(new byte[]{7, 8}, server.decrypt(wire));
            assertArrayEquals(new byte[]{9}, client.decrypt(server.encrypt(new byte[]{9})));
            assertThrows(GeneralSecurityException.class, () -> server.decrypt(wire));
        }
    }
    @Test void acceptsReorderingButNotReplayOrTampering() throws Exception {
        try (SessionCipher client = peer(true); SessionCipher server = peer(false)) {
            byte[] first = client.encrypt(new byte[]{1});
            byte[] second = client.encrypt(new byte[]{2});
            byte[] altered = second.clone(); altered[altered.length - 1] ^= 1;
            assertThrows(GeneralSecurityException.class, () -> server.decrypt(altered));
            assertArrayEquals(new byte[]{2}, server.decrypt(second));
            assertArrayEquals(new byte[]{1}, server.decrypt(first));
            assertThrows(GeneralSecurityException.class, () -> server.decrypt(first));
        }
    }
    @Test void isolatesTranscriptsAndSessionsAndErasesClosedKeys() throws Exception {
        try (SessionCipher client = peer(true);
             SessionCipher other = new SessionCipher(secret, new byte[]{3}, new byte[]{2}, 77, false)) {
            byte[] wire = client.encrypt(new byte[]{1});
            assertThrows(GeneralSecurityException.class, () -> other.decrypt(wire));
            client.close();
            assertThrows(GeneralSecurityException.class, () -> client.encrypt(new byte[]{2}));
        }
    }
    @Test void boundsReplayStateAndRejectsExpiredPackets() throws Exception {
        try (SessionCipher client = peer(true); SessionCipher server = peer(false)) {
            byte[] first = client.encrypt(new byte[]{1});
            byte[] last = null;
            for (int i = 0; i < 4096; i++) last = client.encrypt(new byte[]{2});
            assertArrayEquals(new byte[]{2}, server.decrypt(last));
            assertThrows(GeneralSecurityException.class, () -> server.decrypt(first));
        }
    }
}
