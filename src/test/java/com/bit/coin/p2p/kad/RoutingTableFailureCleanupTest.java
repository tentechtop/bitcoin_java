package com.bit.coin.p2p.kad;

import com.bit.coin.database.DataBase;
import com.bit.coin.database.rocksDb.TableEnum;
import com.bit.coin.p2p.peer.Peer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

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
                .allMatch(peer -> !HexFormat.of().formatHex(peer.getId())
                        .equals(HexFormat.of().formatHex(failedPeerId))));
        verify(dataBase).insert(eq(TableEnum.PEER), eq(ROUTING_TABLE_PEERS_KEY), any(byte[].class));
    }

    @Test
    void scheduledCheckRetriesFailedInsertAndStopsAfterSuccess() {
        DataBase dataBase = mock(DataBase.class);
        RoutingTable routingTable = routingTable(dataBase);
        routingTable.addPeer(peer(peerId(3), 8336));
        doThrow(new IllegalStateException("temporary database failure"))
                .doNothing()
                .when(dataBase).insert(eq(TableEnum.PEER), eq(ROUTING_TABLE_PEERS_KEY), any(byte[].class));

        routingTable.checkAndPersist();
        assertEquals(1, routingTable.getNodeChangeCount().get());
        routingTable.checkAndPersist();

        verify(dataBase, times(2)).insert(eq(TableEnum.PEER), eq(ROUTING_TABLE_PEERS_KEY), any(byte[].class));
        routingTable.checkAndPersist();
        verifyNoMoreInteractions(dataBase);
    }

    @Test
    void scheduledCheckRetriesFailedDeleteAndStopsAfterSuccess() {
        DataBase dataBase = mock(DataBase.class);
        RoutingTable routingTable = routingTable(dataBase);
        Peer removedPeer = peer(peerId(1), 8334);
        routingTable.addPeer(removedPeer);
        routingTable.delete(removedPeer);
        doThrow(new IllegalStateException("temporary database failure"))
                .doNothing()
                .when(dataBase).delete(TableEnum.PEER, ROUTING_TABLE_PEERS_KEY);

        routingTable.checkAndPersist();
        assertEquals(2, routingTable.getNodeChangeCount().get());
        routingTable.checkAndPersist();

        verify(dataBase, times(2)).delete(TableEnum.PEER, ROUTING_TABLE_PEERS_KEY);
        routingTable.checkAndPersist();
        verifyNoMoreInteractions(dataBase);
    }

    @Test
    void scheduledCheckPersistsPeerAddedToAnAlreadyScannedBucketOnNextCheck() throws Exception {
        DataBase dataBase = mock(DataBase.class);
        RoutingTable routingTable = routingTable(dataBase);
        Peer existingPeer = peer(peerId(1), 8334);
        Peer latePeer = peer(peerId(2), 8335);
        routingTable.addPeer(existingPeer);

        int nextBucketIndex = routingTable.getBucketIndex(latePeer.getId()) + 1;
        Bucket nextBucket = spy(routingTable.getBuckets().get(nextBucketIndex));
        routingTable.getBuckets().set(nextBucketIndex, nextBucket);
        doAnswer(invocation -> {
            CompletableFuture.runAsync(() -> routingTable.addPeer(latePeer)).get(5, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).doCallRealMethod().when(nextBucket).getNodesArrayList();

        routingTable.checkAndPersist();
        assertEquals(1, routingTable.getNodeChangeCount().get());
        routingTable.checkAndPersist();

        ArgumentCaptor<byte[]> snapshots = ArgumentCaptor.forClass(byte[].class);
        verify(dataBase, times(2)).insert(eq(TableEnum.PEER), eq(ROUTING_TABLE_PEERS_KEY), snapshots.capture());
        assertEquals(List.of(8334), snapshotPorts(routingTable, snapshots.getAllValues().get(0)));
        assertEquals(List.of(8334, 8335), snapshotPorts(routingTable, snapshots.getAllValues().get(1)));
        routingTable.checkAndPersist();
        verifyNoMoreInteractions(dataBase);
    }

    private static List<Integer> snapshotPorts(RoutingTable routingTable, byte[] snapshot) throws Exception {
        return routingTable.deserializePeers(snapshot).stream().map(Peer::getPort).sorted().toList();
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
