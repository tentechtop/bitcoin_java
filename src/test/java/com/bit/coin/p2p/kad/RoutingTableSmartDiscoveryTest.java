package com.bit.coin.p2p.kad;

import com.bit.coin.database.DataBase;
import com.bit.coin.p2p.peer.Peer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RoutingTableSmartDiscoveryTest {
    @Test
    void peerHintsGoToCandidatePoolBeforeRoutingTable() {
        RoutingTable routingTable = routingTable(20);
        Peer hintedPeer = peer(peerId((byte) 0x80, (byte) 1), 8334);

        routingTable.receivePeerHints(List.of(hintedPeer), "source");

        assertTrue(routingTable.getAll().isEmpty());
        assertEquals(1, routingTable.getCandidatePeerSnapshots().size());
        assertEquals(hex(hintedPeer.getId()), routingTable.getCandidatePeerSnapshots().getFirst().peerId());
    }

    @Test
    void fullBucketDefersLowerScoreCandidate() {
        RoutingTable routingTable = routingTable(1);
        Peer stablePeer = peer(peerId((byte) 0x80, (byte) 1), 8334);
        Peer candidatePeer = peer(peerId((byte) 0x80, (byte) 2), 8335);

        routingTable.addPeer(stablePeer);
        routingTable.addPeer(stablePeer);
        routingTable.addPeer(candidatePeer);

        assertEquals(1, routingTable.getAll().size());
        assertEquals(hex(stablePeer.getId()), hex(routingTable.getAll().getFirst().getId()));
        assertEquals(1, routingTable.getCandidatePeerSnapshots().size());
        assertEquals(hex(candidatePeer.getId()), routingTable.getCandidatePeerSnapshots().getFirst().peerId());
    }

    private static RoutingTable routingTable(int bucketSize) {
        RoutingTable routingTable = new RoutingTable();
        routingTable.setDataBase(mock(DataBase.class));
        routingTable.setLocalNodeId(new byte[32]);
        routingTable.setBucketSize(bucketSize);
        CopyOnWriteArrayList<Bucket> buckets = new CopyOnWriteArrayList<>();
        for (int i = 0; i <= 256; i++) {
            buckets.add(new Bucket(i));
        }
        routingTable.setBuckets(buckets);
        return routingTable;
    }

    private static Peer peer(byte[] id, int port) {
        Peer peer = new Peer();
        peer.setId(id);
        peer.setAddress("127.0.0.1");
        peer.setPort(port);
        return peer;
    }

    private static byte[] peerId(byte firstByte, byte lastByte) {
        byte[] bytes = new byte[32];
        bytes[0] = firstByte;
        bytes[31] = lastByte;
        return bytes;
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
