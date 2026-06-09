package com.bit.coin.p2p.conn;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertThrows;

class QuicFrameLimitTest {
    @Test
    void deserializeRejectsOversizedFrameBeforePayloadAllocation() {
        byte[] bytes = new byte[QuicFrame.FIXED_HEADER_LENGTH];
        writeLong(bytes, 0, 1L);
        writeLong(bytes, 8, 1L);
        writeInt(bytes, 16, 1);
        bytes[20] = QuicFrameEnum.DATA_FRAME.getCode();
        writeInt(bytes, 21, 0);
        writeInt(bytes, 25, QuicFrame.MAX_FRAME_TOTAL_LENGTH + 1);

        assertThrows(RuntimeException.class, () -> QuicFrame.deserialize(bytes));
    }

    @Test
    void byteBufDecodeRejectsOversizedFrameBeforePayloadAllocation() {
        var buffer = Unpooled.buffer(QuicFrame.FIXED_HEADER_LENGTH);
        buffer.writeLong(1L);
        buffer.writeLong(1L);
        buffer.writeInt(1);
        buffer.writeByte(QuicFrameEnum.DATA_FRAME.getCode());
        buffer.writeInt(0);
        buffer.writeInt(QuicFrame.MAX_FRAME_TOTAL_LENGTH + 1);

        assertThrows(RuntimeException.class, () ->
                QuicFrame.decodeFromByteBuf(buffer, new InetSocketAddress("127.0.0.1", 8334)));
    }

    private void writeLong(byte[] bytes, int offset, long value) {
        for (int index = 7; index >= 0; index--) {
            bytes[offset + 7 - index] = (byte) (value >> (index * 8));
        }
    }

    private void writeInt(byte[] bytes, int offset, int value) {
        for (int index = 3; index >= 0; index--) {
            bytes[offset + 3 - index] = (byte) (value >> (index * 8));
        }
    }
}
