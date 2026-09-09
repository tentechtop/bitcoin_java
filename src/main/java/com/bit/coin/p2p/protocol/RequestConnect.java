package com.bit.coin.p2p.protocol;

import com.bit.coin.utils.UInt256;
import com.bit.coin.utils.Ed25519Signer;
import lombok.Data;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

/** Revision 2 handshake: identity signs the network, direction and fresh session transcript. */
@Data
public class RequestConnect {
    public static final int VERSION = 2;
    public static final int SIGNED_BYTES = 229;
    public static final int WIRE_BYTES = SIGNED_BYTES + 64;
    public static final byte REQUEST = 0, RESPONSE = 1;
    private byte[] peerId;
    private int version = VERSION;
    private byte[] sharedSecretPubKey;
    private byte[] latestHash;
    private int latestHeight;
    private UInt256 chainWork = UInt256.ZERO;
    private int network;
    private long timestamp;
    private long connectionId;
    private long dataId;
    private byte kind;
    private byte[] nonce;
    private byte[] replyTo = new byte[32];
    private byte[] signature;

    public void bind(int network, long connectionId, long dataId, byte kind, byte[] replyTo) {
        this.network = network;
        this.connectionId = connectionId;
        this.dataId = dataId;
        this.kind = kind;
        this.timestamp = System.currentTimeMillis() / 1000;
        this.nonce = new byte[32];
        new SecureRandom().nextBytes(this.nonce);
        this.replyTo = replyTo == null ? new byte[32] : replyTo.clone();
    }

    public byte[] getSignatureData() {
        require(peerId, 32); require(sharedSecretPubKey, 32); require(latestHash, 32);
        require(nonce, 32); require(replyTo, 32);
        if (chainWork == null || latestHeight < 0 || kind < REQUEST || kind > RESPONSE)
            throw new IllegalArgumentException("Invalid handshake fields");
        return ByteBuffer.allocate(SIGNED_BYTES).put(peerId).putInt(version).put(sharedSecretPubKey)
                .put(latestHash).putInt(latestHeight).put(chainWork.toBytes()).putInt(network)
                .putLong(timestamp).putLong(connectionId).putLong(dataId).put(kind).put(nonce).put(replyTo).array();
    }

    public byte[] serialize() {
        require(signature, 64);
        return ByteBuffer.allocate(WIRE_BYTES).put(getSignatureData()).put(signature).array();
    }

    public static RequestConnect deserialize(byte[] data) {
        require(data, WIRE_BYTES);
        ByteBuffer b = ByteBuffer.wrap(data);
        RequestConnect r = new RequestConnect();
        r.peerId = read(b, 32); r.version = b.getInt(); r.sharedSecretPubKey = read(b, 32);
        r.latestHash = read(b, 32); r.latestHeight = b.getInt(); r.chainWork = UInt256.fromBytes(read(b, 32));
        r.network = b.getInt(); r.timestamp = b.getLong(); r.connectionId = b.getLong(); r.dataId = b.getLong();
        r.kind = b.get(); r.nonce = read(b, 32); r.replyTo = read(b, 32); r.signature = read(b, 64);
        r.getSignatureData();
        return r;
    }

    public boolean verifyContext(int expectedNetwork, long expectedConnection, long expectedDataId,
                                 byte expectedKind, byte[] expectedReply, long now) {
        return version == VERSION && network == expectedNetwork && connectionId == expectedConnection
                && dataId == expectedDataId && kind == expectedKind && timestamp >= now - 60 && timestamp <= now + 60
                && nonce != null && !Arrays.equals(nonce, new byte[32])
                && Arrays.equals(replyTo, expectedReply == null ? new byte[32] : expectedReply) && verifySignature();
    }

    public boolean verifySignature() {
        try { require(signature, 64); return Ed25519Signer.fastVerify(peerId, getSignatureData(), signature); }
        catch (RuntimeException invalid) { return false; }
    }
    private static byte[] read(ByteBuffer b, int n) { byte[] v = new byte[n]; b.get(v); return v; }
    private static void require(byte[] value, int size) {
        if (value == null || value.length != size) throw new IllegalArgumentException("Invalid handshake field length");
    }
}
