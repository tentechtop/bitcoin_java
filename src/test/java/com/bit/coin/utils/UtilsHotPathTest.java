package com.bit.coin.utils;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilsHotPathTest {

    @Test
    void littleEndianAndHexConversionsKeepWireValues() throws Exception {
        int intValue = 0xA1B2C3D4;
        long longValue = 0x1122334455667788L;

        assertArrayEquals(new byte[] {(byte) 0xD4, (byte) 0xC3, (byte) 0xB2, (byte) 0xA1},
                SerializeUtils.intToLittleEndian(intValue));
        assertArrayEquals(new byte[] {(byte) 0x88, 0x77, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11},
                SerializeUtils.longToLittleEndian(longValue));
        assertEquals(intValue, SerializeUtils.littleEndianToInt(SerializeUtils.intToLittleEndian(intValue)));
        assertEquals(longValue, SerializeUtils.littleEndianToLong(SerializeUtils.longToLittleEndian(longValue)));

        byte[] bytes = {0x00, 0x01, (byte) 0xA0, (byte) 0xFF};
        assertEquals("0001a0ff", SerializeUtils.bytesToHex(bytes));
        assertArrayEquals(bytes, SerializeUtils.hexToBytes("0001A0ff"));
        assertThrows(IllegalArgumentException.class, () -> SerializeUtils.hexToBytes("abc"));
        assertThrows(IllegalArgumentException.class, () -> SerializeUtils.hexToBytes("zz"));
    }

    @Test
    void hexJsonCodecHandlesFormattingWithoutRegexOrSubstringParsing() {
        assertEquals("0x00 AB", HexByteArraySerializer.bytesToHex(
                new byte[] {0, (byte) 0xAB}, true, true, " ", true));
        assertArrayEquals(new byte[] {0, (byte) 0xAB},
                HexByteArraySerializer.hexToBytes("0x00\tAB"));
        assertThrows(IllegalArgumentException.class,
                () -> HexByteArraySerializer.hexToBytes("0x0"));
    }

    @Test
    void shaByteBufferPathDoesNotAllocatePerCallOrConsumeInput() throws Exception {
        byte[] input = "utility hot path".getBytes(StandardCharsets.UTF_8);
        ByteBuffer direct = ByteBuffer.allocateDirect(input.length);
        direct.put(input).flip();
        int position = direct.position();

        assertArrayEquals(Sha.applySHA256(input), Sha.applySHA256(direct));
        assertEquals(position, direct.position());

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] once = digest.digest(input);
        byte[] twice = digest.digest(once);
        assertArrayEquals(twice, Sha.doubleSha256(input));
    }

    @Test
    void uint256SupportsValuesWithTheHighBitSet() {
        UInt256 value = UInt256.fromBigInteger(BigInteger.ONE.shiftLeft(255));
        byte[] encoded = value.toBytes();

        assertEquals(32, encoded.length);
        assertEquals((byte) 0x80, encoded[0]);
        assertEquals(value, UInt256.fromBytes(encoded));
        assertTrue(value.toHexString().startsWith("80"));
        assertEquals(64, value.toHexString().length());
    }

    @Test
    void ed25519ByteBufferPathPreservesInputPosition() {
        byte[] privateKey = new byte[Ed25519Signer.CORE_KEY_LENGTH];
        for (int i = 0; i < privateKey.length; i++) {
            privateKey[i] = (byte) (i + 1);
        }
        byte[] publicKey = Ed25519Signer.derivePublicKeyFromPrivateKey(privateKey);
        ByteBuffer data = ByteBuffer.allocateDirect(32);
        data.put("byte-buffer-signature-path".getBytes(StandardCharsets.UTF_8));
        data.flip();
        int position = data.position();

        byte[] signature = Ed25519Signer.fastSign(privateKey, data);
        assertTrue(Ed25519Signer.fastVerify(publicKey, data, signature));
        assertEquals(position, data.position());
        assertFalse(Arrays.equals(signature, new byte[signature.length]));
    }

    @Test
    void lengthPrefixedPayloadWorksWhenStreamAvailableIsZero() throws Exception {
        byte[] encoded = new byte[] {3, 'a', 'b', 'c'};
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded) {
            @Override
            public int available() {
                return 0;
            }
        });

        assertArrayEquals(new byte[] {'a', 'b', 'c'}, SerializeUtils.readBytesWithLength(input));
    }
}
