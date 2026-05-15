package com.bit.coin.p2p.protocol.message;

import com.bit.coin.structure.block.BlockHeader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class BlockHeadersPayload {
    public static final int HEADER_BYTES = 80;
    public static final int MAX_HEADERS_PER_RESPONSE = 512;

    private BlockHeadersPayload() {
    }

    public static byte[] serializeRequest(int startHeight, int stopHeight, int maxCount) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(12);
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeInt(startHeight);
            dos.writeInt(stopHeight);
            dos.writeInt(Math.min(Math.max(maxCount, 1), MAX_HEADERS_PER_RESPONSE));
            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("Serialize block headers request failed", e);
        }
    }

    public static Request deserializeRequest(byte[] bytes) {
        if (bytes == null || bytes.length != 12) {
            throw new IllegalArgumentException("Block headers request must be 12 bytes");
        }
        try {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
            int startHeight = dis.readInt();
            int stopHeight = dis.readInt();
            int maxCount = dis.readInt();
            return new Request(startHeight, stopHeight, Math.min(Math.max(maxCount, 1), MAX_HEADERS_PER_RESPONSE));
        } catch (IOException e) {
            throw new IllegalArgumentException("Deserialize block headers request failed", e);
        }
    }

    public static byte[] serializeResponse(List<Entry> entries) {
        List<Entry> safeEntries = entries == null ? List.of() : entries;
        if (safeEntries.size() > MAX_HEADERS_PER_RESPONSE) {
            throw new IllegalArgumentException("Too many headers in one response");
        }

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4 + safeEntries.size() * (4 + HEADER_BYTES));
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeInt(safeEntries.size());
            for (Entry entry : safeEntries) {
                if (entry == null || entry.header() == null) {
                    throw new IllegalArgumentException("Header response entry cannot be null");
                }
                dos.writeInt(entry.height());
                dos.write(entry.header().serialize());
            }
            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("Serialize block headers response failed", e);
        }
    }

    public static List<Entry> deserializeResponse(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            throw new IllegalArgumentException("Block headers response is empty");
        }

        try {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
            int count = dis.readInt();
            if (count < 0 || count > MAX_HEADERS_PER_RESPONSE) {
                throw new IllegalArgumentException("Invalid block headers count: " + count);
            }
            int expectedLength = 4 + count * (4 + HEADER_BYTES);
            if (bytes.length != expectedLength) {
                throw new IllegalArgumentException("Block headers response length mismatch");
            }

            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int height = dis.readInt();
                byte[] headerBytes = new byte[HEADER_BYTES];
                dis.readFully(headerBytes);
                entries.add(new Entry(height, BlockHeader.deserialize(headerBytes)));
            }
            return entries;
        } catch (IOException e) {
            throw new IllegalArgumentException("Deserialize block headers response failed", e);
        }
    }

    public record Request(int startHeight, int stopHeight, int maxCount) {
    }

    public record Entry(int height, BlockHeader header) {
    }
}
