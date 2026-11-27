package com.bit.coin.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ECCWithAESGCMTest {

    @Test
    void rejectsInvalidX25519KeyLengths() {
        assertThrows(IllegalArgumentException.class,
                () -> ECCWithAESGCM.generateSharedSecret(new byte[31], new byte[32]));
        assertThrows(IllegalArgumentException.class,
                () -> ECCWithAESGCM.generateSharedSecret(new byte[32], new byte[31]));
    }

    @Test
    void rejectsAllZeroSharedSecrets() {
        assertThrows(IllegalArgumentException.class,
                () -> ECCWithAESGCM.deriveAesKey(new byte[32]));
    }

    @Test
    void rejectsLowOrderPublicKey() {
        assertThrows(IllegalArgumentException.class,
                () -> ECCWithAESGCM.generateSharedSecret(new byte[32], new byte[32]));
    }
}
