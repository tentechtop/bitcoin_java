package com.bit.coin.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ed25519SignerMemoryTest {

    @Test
    void batchSigningWorksAcrossChunks() throws Exception {
        byte[] privateKey = new byte[Ed25519Signer.CORE_KEY_LENGTH];
        for (int i = 0; i < privateKey.length; i++) {
            privateKey[i] = (byte) (i + 1);
        }
        List<byte[]> keys = new ArrayList<>();
        List<byte[]> messages = new ArrayList<>();
        for (int i = 0; i < 1_025; i++) {
            keys.add(privateKey);
            messages.add(new byte[] { (byte) i });
        }

        List<byte[]> signatures = Ed25519Signer.batchSign(keys, messages);

        assertEquals(messages.size(), signatures.size());
        for (int i = 0; i < signatures.size(); i++) {
            assertTrue(signatures.get(i).length > 0);
        }
    }

    @Test
    void rejectsMismatchedBatchInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> Ed25519Signer.batchSign(List.of(new byte[32]), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> Ed25519Signer.batchVerify(List.of(new byte[32]), List.of(), List.of()));
    }
}