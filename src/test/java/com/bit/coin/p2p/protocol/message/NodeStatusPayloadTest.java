package com.bit.coin.p2p.protocol.message;

import com.bit.coin.utils.UInt256;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeStatusPayloadTest {

    @Test
    void roundTripNodeStatusPayload() {
        byte[] tipHash = new byte[32];
        Arrays.fill(tipHash, (byte) 11);
        NodeStatusPayload payload = new NodeStatusPayload(
                1,
                NodeStatusPayload.SERVICE_BLOCKS | NodeStatusPayload.SERVICE_TX_RELAY,
                42,
                tipHash,
                UInt256.fromLong(1000),
                987654321L,
                NodeStatusPayload.REASON_TIP_CHANGED
        );

        NodeStatusPayload parsed = NodeStatusPayload.deserialize(payload.serialize());

        assertEquals(1, parsed.protocolVersion());
        assertEquals(NodeStatusPayload.SERVICE_BLOCKS | NodeStatusPayload.SERVICE_TX_RELAY, parsed.services());
        assertEquals(42, parsed.height());
        assertArrayEquals(tipHash, parsed.tipHash());
        assertEquals(UInt256.fromLong(1000), parsed.chainWork());
        assertEquals(987654321L, parsed.timestampMillis());
        assertEquals(NodeStatusPayload.REASON_TIP_CHANGED, parsed.reason());
        assertTrue(parsed.hasUsableTip());
    }
}
