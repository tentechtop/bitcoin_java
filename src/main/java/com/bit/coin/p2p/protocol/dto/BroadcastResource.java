package com.bit.coin.p2p.protocol.dto;

import com.bit.coin.utils.UInt256;
import lombok.Data;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

@Data
public class BroadcastResource {

    public static final int TYPE_BLOCK = 0;
    public static final int TYPE_TRANSACTION = 1;
    public static final int UNKNOWN_HEIGHT = -1;

    private static final int METADATA_VERSION = 1;
    private static final int MAX_HASH_LENGTH = 128;
    private static final int METADATA_REMAINING_BYTES = 4 + 32 + 8 + 4;

    private byte[] hash;
    private int type;

    // Optional metadata. Older peers only send hash + type; keep defaults compatible.
    private int height = UNKNOWN_HEIGHT;
    private UInt256 chainWork = UInt256.ZERO;
    private long timestampMillis;
    private int flags;

    public byte[] toBytes() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            if (hash == null) {
                dos.writeInt(0);
            } else {
                dos.writeInt(hash.length);
                dos.write(hash);
            }

            dos.writeInt(type);

            // Metadata is appended after the original payload, so older readers can ignore it.
            dos.writeInt(METADATA_VERSION);
            dos.writeInt(height);
            dos.write(chainWork == null ? UInt256.ZERO.toBytes() : chainWork.toBytes());
            dos.writeLong(timestampMillis > 0 ? timestampMillis : System.currentTimeMillis());
            dos.writeInt(flags);

            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    public static BroadcastResource fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length < 8) {
            return null;
        }

        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             DataInputStream dis = new DataInputStream(bais)) {

            BroadcastResource resource = new BroadcastResource();

            int hashLength = dis.readInt();
            if (hashLength < 0 || hashLength > MAX_HASH_LENGTH || dis.available() < hashLength + 4) {
                return null;
            }
            if (hashLength > 0) {
                byte[] hash = new byte[hashLength];
                dis.readFully(hash);
                resource.setHash(hash);
            }

            resource.setType(dis.readInt());

            if (dis.available() >= 4) {
                int metadataVersion = dis.readInt();
                if (metadataVersion == METADATA_VERSION && dis.available() >= METADATA_REMAINING_BYTES) {
                    resource.setHeight(dis.readInt());
                    byte[] chainWorkBytes = new byte[32];
                    dis.readFully(chainWorkBytes);
                    resource.setChainWork(UInt256.fromBytes(chainWorkBytes));
                    resource.setTimestampMillis(dis.readLong());
                    resource.setFlags(dis.readInt());
                }
            }

            return resource;
        } catch (Exception e) {
            return null;
        }
    }

    public boolean hasBlockState() {
        return type == TYPE_BLOCK && hash != null && hash.length == 32 && height >= 0;
    }
}
