package com.bit.coin.p2p.protocol.message;

import com.bit.coin.structure.block.Block;
import com.bit.coin.utils.UInt256;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Arrays;

public final class NodeStatusPayload {
    public static final int WIRE_VERSION = 1;
    public static final int SERVICE_BLOCKS = 1;
    public static final int SERVICE_TX_RELAY = 1 << 1;

    public static final int REASON_HANDSHAKE = 1;
    public static final int REASON_TIP_CHANGED = 2;
    public static final int REASON_PERIODIC = 3;

    private static final int HASH_LENGTH = 32;
    private static final int WIRE_LENGTH = 4 + 4 + 4 + 4 + HASH_LENGTH + 32 + 8 + 4;

    private final int protocolVersion;
    private final int services;
    private final int height;
    private final byte[] tipHash;
    private final UInt256 chainWork;
    private final long timestampMillis;
    private final int reason;

    public NodeStatusPayload(
            int protocolVersion,
            int services,
            int height,
            byte[] tipHash,
            UInt256 chainWork,
            long timestampMillis,
            int reason
    ) {
        this.protocolVersion = protocolVersion;
        this.services = services;
        this.height = height;
        this.tipHash = normalizeHash(tipHash);
        this.chainWork = chainWork == null ? UInt256.ZERO : chainWork;
        this.timestampMillis = timestampMillis > 0 ? timestampMillis : System.currentTimeMillis();
        this.reason = reason;
    }

    public static NodeStatusPayload fromTip(Block tip, int reason) {
        if (tip == null) {
            return new NodeStatusPayload(
                    WIRE_VERSION,
                    SERVICE_BLOCKS | SERVICE_TX_RELAY,
                    0,
                    new byte[HASH_LENGTH],
                    UInt256.ZERO,
                    System.currentTimeMillis(),
                    reason
            );
        }
        return new NodeStatusPayload(
                WIRE_VERSION,
                SERVICE_BLOCKS | SERVICE_TX_RELAY,
                tip.getHeight(),
                tip.getHash(),
                tip.getChainWork(),
                System.currentTimeMillis(),
                reason
        );
    }

    public byte[] serialize() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(WIRE_LENGTH);
             DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(WIRE_VERSION);
            dos.writeInt(protocolVersion);
            dos.writeInt(services);
            dos.writeInt(height);
            dos.write(tipHash);
            dos.write(chainWork.toBytes());
            dos.writeLong(timestampMillis);
            dos.writeInt(reason);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalArgumentException("Serialize node status payload failed", e);
        }
    }

    public static NodeStatusPayload deserialize(byte[] bytes) {
        if (bytes == null || bytes.length != WIRE_LENGTH) {
            throw new IllegalArgumentException("Node status payload length must be " + WIRE_LENGTH + " bytes");
        }
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int wireVersion = dis.readInt();
            if (wireVersion != WIRE_VERSION) {
                throw new IllegalArgumentException("Unsupported node status payload version: " + wireVersion);
            }
            int protocolVersion = dis.readInt();
            int services = dis.readInt();
            int height = dis.readInt();
            byte[] tipHash = new byte[HASH_LENGTH];
            dis.readFully(tipHash);
            byte[] chainWorkBytes = new byte[32];
            dis.readFully(chainWorkBytes);
            long timestampMillis = dis.readLong();
            int reason = dis.readInt();
            return new NodeStatusPayload(
                    protocolVersion,
                    services,
                    height,
                    tipHash,
                    UInt256.fromBytes(chainWorkBytes),
                    timestampMillis,
                    reason
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("Deserialize node status payload failed", e);
        }
    }

    public boolean hasUsableTip() {
        return height >= 0 && tipHash != null && tipHash.length == HASH_LENGTH && !isAllZero(tipHash);
    }

    public int protocolVersion() {
        return protocolVersion;
    }

    public int services() {
        return services;
    }

    public int height() {
        return height;
    }

    public byte[] tipHash() {
        return tipHash.clone();
    }

    public UInt256 chainWork() {
        return chainWork;
    }

    public long timestampMillis() {
        return timestampMillis;
    }

    public int reason() {
        return reason;
    }

    private static byte[] normalizeHash(byte[] hash) {
        if (hash == null) {
            return new byte[HASH_LENGTH];
        }
        if (hash.length == HASH_LENGTH) {
            return hash.clone();
        }
        return Arrays.copyOf(hash, HASH_LENGTH);
    }

    private static boolean isAllZero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }
}
