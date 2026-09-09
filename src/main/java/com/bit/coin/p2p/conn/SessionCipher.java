package com.bit.coin.p2p.conn;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.BitSet;

/** Authenticates every data/control datagram, with direction-specific HKDF keys and a replay window. */
public final class SessionCipher implements AutoCloseable {
    public static final int MAGIC = 0x424a5632;
    public static final int PREFIX_BYTES = 13;
    public static final int OVERHEAD_BYTES = PREFIX_BYTES + 8 + 16;
    private static final int WINDOW = 4096;
    private final long connectionId;
    private final byte[] sendKey, receiveKey;
    private long nextSequence, highestSequence = -1;
    private BitSet received = new BitSet(WINDOW);
    private boolean closed;

    public SessionCipher(byte[] secret, byte[] requestTranscript, byte[] responseTranscript,
                         long connectionId, boolean outbound) {
        try {
            this.connectionId = connectionId;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(requestTranscript);
            byte[] salt = digest.digest(responseTranscript);
            byte[] prk = hmac(salt, secret);
            byte[] clientKey = expand(prk, "bitcoin_java/v2/client");
            byte[] serverKey = expand(prk, "bitcoin_java/v2/server");
            sendKey = outbound ? clientKey : serverKey;
            receiveKey = outbound ? serverKey : clientKey;
            Arrays.fill(prk, (byte) 0);
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    public synchronized byte[] encrypt(byte[] frame) throws GeneralSecurityException {
        if (closed || nextSequence == Long.MAX_VALUE) throw new GeneralSecurityException("Session exhausted");
        long sequence = nextSequence++;
        byte[] header = ByteBuffer.allocate(PREFIX_BYTES + 8).putInt(MAGIC).put((byte) 1)
                .putLong(connectionId).putLong(sequence).array();
        Cipher cipher = cipher(Cipher.ENCRYPT_MODE, sendKey, sequence, header);
        byte[] encrypted = cipher.doFinal(frame);
        return ByteBuffer.allocate(header.length + encrypted.length).put(header).put(encrypted).array();
    }

    public synchronized byte[] decrypt(byte[] datagram) throws GeneralSecurityException {
        if (closed || datagram.length < OVERHEAD_BYTES) throw new GeneralSecurityException("Invalid envelope");
        ByteBuffer b = ByteBuffer.wrap(datagram);
        if (b.getInt() != MAGIC || b.get() != 1 || b.getLong() != connectionId)
            throw new GeneralSecurityException("Wrong session");
        long sequence = b.getLong();
        if (sequence < 0 || (sequence <= highestSequence && (highestSequence - sequence >= WINDOW
                || received.get((int) (highestSequence - sequence))))) throw new GeneralSecurityException("Replay");
        byte[] plaintext = cipher(Cipher.DECRYPT_MODE, receiveKey, sequence,
                Arrays.copyOf(datagram, PREFIX_BYTES + 8)).doFinal(datagram, PREFIX_BYTES + 8, b.remaining());
        // Only authenticated packets may advance the replay window.
        if (sequence > highestSequence) {
            long shift = sequence - highestSequence;
            BitSet updated = new BitSet(WINDOW);
            if (shift < WINDOW) for (int bit = received.nextSetBit(0); bit >= 0 && bit + shift < WINDOW; bit = received.nextSetBit(bit + 1))
                updated.set((int) (bit + shift));
            received = updated;
            highestSequence = sequence;
        }
        received.set((int) (highestSequence - sequence));
        return plaintext;
    }

    public static byte[] handshake(long connectionId, byte[] frame) {
        return ByteBuffer.allocate(PREFIX_BYTES + frame.length).putInt(MAGIC).put((byte) 0).putLong(connectionId).put(frame).array();
    }

    private static Cipher cipher(int mode, byte[] key, long sequence, byte[] aad) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128,
                ByteBuffer.allocate(12).putInt(0).putLong(sequence).array()));
        cipher.updateAAD(aad);
        return cipher;
    }
    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(data);
    }
    private static byte[] expand(byte[] prk, String label) throws GeneralSecurityException {
        byte[] info = label.getBytes(StandardCharsets.US_ASCII);
        return hmac(prk, ByteBuffer.allocate(info.length + 1).put(info).put((byte) 1).array());
    }
    @Override public synchronized void close() { closed = true; Arrays.fill(sendKey, (byte) 0); Arrays.fill(receiveKey, (byte) 0); received.clear(); }
}
