package com.bit.coin.p2p.protocol.dto;

import com.bit.coin.utils.UInt256;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BroadcastResourceTest {

    @Test
    void deserializeLegacyPayloadWithoutMetadata() throws Exception {
        byte[] hash = filledHash((byte) 7);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(hash.length);
        dos.write(hash);
        dos.writeInt(BroadcastResource.TYPE_BLOCK);

        BroadcastResource resource = BroadcastResource.fromBytes(baos.toByteArray());

        assertNotNull(resource);
        assertArrayEquals(hash, resource.getHash());
        assertEquals(BroadcastResource.TYPE_BLOCK, resource.getType());
        assertEquals(BroadcastResource.UNKNOWN_HEIGHT, resource.getHeight());
        assertEquals(UInt256.ZERO, resource.getChainWork());
    }

    @Test
    void roundTripPayloadWithMetadata() {
        byte[] hash = filledHash((byte) 9);
        BroadcastResource resource = new BroadcastResource();
        resource.setHash(hash);
        resource.setType(BroadcastResource.TYPE_BLOCK);
        resource.setHeight(128);
        resource.setChainWork(UInt256.fromLong(99));
        resource.setTimestampMillis(123456789L);
        resource.setFlags(3);

        BroadcastResource parsed = BroadcastResource.fromBytes(resource.toBytes());

        assertNotNull(parsed);
        assertArrayEquals(hash, parsed.getHash());
        assertEquals(BroadcastResource.TYPE_BLOCK, parsed.getType());
        assertEquals(128, parsed.getHeight());
        assertEquals(UInt256.fromLong(99), parsed.getChainWork());
        assertEquals(123456789L, parsed.getTimestampMillis());
        assertEquals(3, parsed.getFlags());
    }

    private byte[] filledHash(byte value) {
        byte[] hash = new byte[32];
        Arrays.fill(hash, value);
        return hash;
    }
}
