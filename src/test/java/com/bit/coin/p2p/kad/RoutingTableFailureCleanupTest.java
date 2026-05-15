package com.bit.coin.p2p.kad;

import com.bit.coin.database.DataBase;
import com.bit.coin.database.rocksDb.TableEnum;
import com.bit.coin.p2p.peer.Peer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RoutingTableFailureCleanupTest {
    private static final byte[] ROUTING_TABLE_PEERS_KEY = "ROUTING_TABLE_PEERS".getBytes(StandardCharsets.UTF_8);

    @Test
    void failedPeerIsRemovedAndEmptyRoutingSnapshotIsDeleted() {
        DataBase dataBase = mock(DataBase.class);
        RoutingTable routingTable = routingTable(dataBase);
        byte[] failedPeerId = peerId(1);
        routingTable.addPeer(peer(failedPeerId, 8334));

        routingTable.removeFailedPeer(HexFormat.of().formatHex(failedPeerId), "timeout");

        assertTrue(routingTable.getAll().isEmpty());
        verify(dataBase).delete(TableEnum.PEER, ROUTING_TABLE_PEERS_KEY);
    }

    @Test
    void failedPeerRemovalPersistsRemainingPeers() {
        DataBase dataBase = mock(DataBase.class);
        RoutingTable routingTable = routingTable(dataBase);
        byte[] failedPeerId = peerId(1);
        byte[] healthyPeerId = peerId(2);
        routingTable.addPeer(peer(failedPeerId, 8334));
        routingTable.addPeer(peer(healthyPeerId, 8335));

        routingTable.removeFailedPeer(HexFormat.of().formatHex(failedPeerId), "timeout");

        assertTrue(routingTable.getAll().stream()
                .allMatch(peer -> !HexFormat.of().formatHex(peer.getId()).equals(HexFormat.of().formatHex(failedPeerId))));
        verify(dataBase).insert(eq(TableEnum.PEER), eq(ROUTING_TABLE_PEERS_KEY), any(byte[].class));
    }

    private static RoutingTable routingTable(DataBase dataBase) {
        RoutingTable routingTable = new RoutingTable();
        routingTable.setDataBase(dataBase);
        routingTable.setLocalNodeId(new byte[32]);
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

    private static byte[] peerId(int seed) {
        byte[] bytes = new byte[32];
        bytes[31] = (byte) seed;
        return bytes;
    }
}
