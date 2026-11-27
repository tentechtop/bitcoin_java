package com.bit.coin.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class SystemConfigIdentityEncryptionTest {

    @Test
    void identityPrivateKeyRoundTripsThroughAuthenticatedEncryption() throws Exception {
        SystemConfig config = new SystemConfig();
        config.setIdentityEncryptionKey(Base64.getEncoder().encodeToString(new byte[] {
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32 }));
        byte[] privateKey = new byte[32];
        privateKey[0] = 42;

        byte[] sealed = invoke(config, "sealIdentityKey", privateKey);
        assertEquals(64, sealed.length);
        assertFalse(Arrays.equals(privateKey, sealed));
        assertArrayEquals(privateKey, invoke(config, "unsealIdentityKey", sealed));

        sealed[sealed.length - 1] ^= 1;
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> invoke(config, "unsealIdentityKey", sealed));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }

    @Test
    void allZeroEncryptionKeyIsRejected() {
        SystemConfig config = new SystemConfig();
        config.setIdentityEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> invoke(config, "sealIdentityKey", new byte[32]));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }

    private static byte[] invoke(SystemConfig config, String method, byte[] value) throws Exception {
        Method target = SystemConfig.class.getDeclaredMethod(method, byte[].class);
        target.setAccessible(true);
        return (byte[]) target.invoke(config, value);
    }
}
