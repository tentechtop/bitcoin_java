package com.bit.coin.p2p.conn;

import com.bit.coin.p2p.production.P2PErrorCode;
import com.bit.coin.p2p.production.P2PTransferErrorEvents;
import com.bit.coin.p2p.production.P2PTransferErrorRecord;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.bit.coin.utils.ECCWithAESGCM.aesGcmEncrypt;
import static com.bit.coin.utils.ECCWithAESGCM.deriveAesKey;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuicConnectionReceiveFailureTest {
    private static final InetSocketAddress REMOTE = new InetSocketAddress("127.0.0.1", 8334);

    @Test
    void sharedSecretMaterialMustBeNegotiated() {
        assertFalse(QuicConnectionManager.isValidSharedSecretMaterial(null));
        assertFalse(QuicConnectionManager.isValidSharedSecretMaterial(new byte[0]));
        assertFalse(QuicConnectionManager.isValidSharedSecretMaterial(new byte[32]));

        byte[] sharedSecret = new byte[32];
        Arrays.fill(sharedSecret, (byte) 7);
        assertTrue(QuicConnectionManager.isValidSharedSecretMaterial(sharedSecret));
    }

    @Test
    void decryptFailureIsRecordedAndDoesNotMarkDataAsReceived() throws Exception {
        QuicConstants.drainAllMsg();
        byte[] sharedSecret = new byte[32];
        Arrays.fill(sharedSecret, (byte) 7);
        byte[] encrypted = aesGcmEncrypt(deriveAesKey(sharedSecret), "hello".getBytes(StandardCharsets.UTF_8));
        encrypted[encrypted.length - 1] ^= 1;

        QuicConnection connection = new QuicConnection();
        connection.setPeerId("peer-a");
        connection.setConnectionId(1L);
        connection.setRemoteAddress(REMOTE);
        connection.setSharedSecret(sharedSecret);

        List<P2PTransferErrorRecord> records = new CopyOnWriteArrayList<>();
        try (AutoCloseable ignored = P2PTransferErrorEvents.subscribe(records::add)) {
            connection.handleFrame(ReceiveQuicDataTest.dataFrame(1L, 2L, 0, 1, encrypted));
        }

        assertFalse(connection.getReceiveMap().containsKey(2L));
        assertTrue(records.stream().anyMatch(record ->
                record.errorCode() == P2PErrorCode.RECEIVE_DECRYPT_FAILED
                        && record.dataId() == 2L
                        && record.connectionId() == 1L
                        && "peer-a".equals(record.peerId())));
    }
}
