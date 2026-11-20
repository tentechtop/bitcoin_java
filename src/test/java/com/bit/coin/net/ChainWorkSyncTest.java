package com.bit.coin.net;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.blockchain.ConsensusRules;
import com.bit.coin.blockchain.Landmark;
import com.bit.coin.cache.UTXOCache;
import com.bit.coin.config.ParamsConfig;
import com.bit.coin.config.SystemConfig;
import com.bit.coin.database.rocksDb.RocksDb;
import com.bit.coin.database.rocksDb.TableEnum;
import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.p2p.peer.Peer;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolEnum;
import com.bit.coin.p2p.protocol.impl.P2PNodeStatusHandle;
import com.bit.coin.p2p.protocol.message.BlockHeadersPayload;
import com.bit.coin.p2p.protocol.message.NodeStatusPayload;
import com.bit.coin.structure.block.Block;
import com.bit.coin.structure.block.BlockHeader;
import com.bit.coin.txpool.TxPool;
import com.bit.coin.utils.UInt256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChainWorkSyncTest {

    @TempDir
    Path directory;

    @Test
    void prefersShorterPeerWithMoreWork() {
        Peer taller = peer(1, 12, 90);
        Peer heavier = peer(2, 9, 110);
        RoutingTable routingTable = mock(RoutingTable.class);
        when(routingTable.getOnlineNodes()).thenReturn(List.of(taller, heavier));
        NetServiceImpl net = new NetServiceImpl();
        ReflectionTestUtils.setField(net, "routingTable", routingTable);

        assertSame(heavier, net.getBestPeer());
        assertEquals(12, net.getBestNetworkHeight());
    }

    @Test
    void retainsShorterHeavierTipWhenAnnouncementsArriveOutOfOrder() {
        Peer peer = peer(1, 12, 90);
        RoutingTable routingTable = spy(new RoutingTable());
        String peerId = "01".repeat(32);
        String heavierHash = "03".repeat(32);
        doReturn(peer).when(routingTable).getNodeByHexId(peerId);
        doNothing().when(routingTable).addPeer(peer);

        routingTable.updateLatest(peerId, heavierHash, 9, UInt256.fromLong(110));
        routingTable.updateLatest(peerId, "04".repeat(32), 13, UInt256.fromLong(100));

        assertEquals(9, peer.getHeight());
        assertEquals(heavierHash, peer.getHashHex());
        assertEquals(UInt256.fromLong(110), peer.getChainWork());
    }

    @ParameterizedTest
    @CsvSource({ "2,true", "3,true", "3,false" })
    void reorganizesToTheHigherWorkBranch(int localHeight, boolean automatic) throws Exception {
        ParamsConfig.Params params = retargetParams();
        try (RocksDb database = new RocksDb()) {
            BlockChainServiceImpl chain = initChain(database, params);
            Block genesis = chain.getMainLatestBlock();
            List<Block> local = branch(genesis, params, 8, localHeight);
            for (Block block : local.subList(1, local.size())) {
                assertTrue(chain.verifyBlock(block));
            }
            List<Block> heavier = branch(genesis, params, 1, 2);
            Block target = heavier.getLast();
            assertTrue(target.getChainWork().compareTo(chain.getMainLatestBlock().getChainWork()) > 0);

            Peer remote = peer(1, target.getHeight(), target.getChainWork().toBigInteger().longValueExact());
            remote.setHashHex(bytesToHex(target.getHash()));
            String remoteId = bytesToHex(remote.getId());
            List<Block> lighter = branch(genesis, params, 9, 2);
            Peer overstated = peer(2, lighter.getLast().getHeight(), 100);
            overstated.setHashHex(bytesToHex(lighter.getLast().getHash()));
            String overstatedId = bytesToHex(overstated.getId());
            Peer taller = peer(3, 12, 1);
            RoutingTable routingTable = mock(RoutingTable.class);
            when(routingTable.getOnlineNodes())
                    .thenReturn(automatic ? List.of(taller, overstated, remote) : List.of(taller, remote));
            NetServiceImpl net = spy(new NetServiceImpl());
            ReflectionTestUtils.setField(net, "routingTable", routingTable);
            ReflectionTestUtils.setField(net, "blockChainService", chain);
            doAnswer(call -> {
                String id = call.getArgument(0);
                ProtocolEnum protocol = call.getArgument(1);
                byte[] request = call.getArgument(2);
                assertTrue(id.equals(remoteId) || id.equals(overstatedId), "Unexpected sync peer " + id);
                List<Block> blocks = id.equals(remoteId) ? heavier : lighter;
                return P2PMessage.newNormalMessage(id.equals(remoteId) ? remote.getId() : overstated.getId(),
                        protocol, syncResponse(protocol, request, blocks));
            }).when(net).sendSyncRequest(anyString(), any(), any(), anyInt());

            SmartSyncTrigger trigger = new SmartSyncTrigger(net, () -> 100_000L);
            try {
                if (automatic) {
                    NodeStatusPayload status = NodeStatusPayload.fromTip(target, NodeStatusPayload.REASON_HANDSHAKE);
                    new P2PNodeStatusHandle(routingTable, trigger).handleVoid(P2PMessage.newNormalMessage(
                            remote.getId(), ProtocolEnum.P2P_Node_Status, status.serialize()));
                } else {
                    net.startSyncBlock();
                }
                Future<?> sync = (Future<?>) ReflectionTestUtils.getField(net, "currentSyncTask");
                assertTrue(sync != null, "Sync was not scheduled");
                sync.get(10, TimeUnit.SECONDS);
                SyncProgress progress = net.getSyncProgress();
                assertEquals("COMPLETED", progress.status(), progress.message());
                assertEquals(localHeight, progress.localHeightAtStart());
                assertEquals(2, progress.targetHeight());
                assertEquals(2, progress.verifiedBlocks());
                assertArrayEquals(target.getHash(), chain.getMainLatestBlock().getHash());
                assertArrayEquals(target.getHash(),
                        database.get(TableEnum.CHAIN, BlockChainServiceImpl.MAIN_CHAIN_TIP_HASH_KEY));
                assertArrayEquals(target.getHash(), database.get(TableEnum.HEIGHT_TO_HASH, target.getHeight()));
                if (localHeight > target.getHeight()) {
                    assertNull(database.get(TableEnum.HEIGHT_TO_HASH, localHeight));
                }
                chain.init();
                var restored = chain.getMainTipSnapshot();
                assertArrayEquals(target.getHash(), restored.hash());
                assertEquals(target.getHeight(), restored.height());
                assertEquals(target.getChainWork(), restored.chainWork());
                if (automatic) {
                    assertTrue(progress.recentErrors().stream()
                            .anyMatch(error -> error.contains("do not exceed local cumulative work")));
                    verify(net, never()).sendSyncRequest(eq(overstatedId), eq(ProtocolEnum.P2P_Query_Block_By_Hash),
                            any(), anyInt());
                }
            } finally {
                trigger.stop();
                net.shutdown();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(InvalidHeader.class)
    void retriesRejectedBatchFromLastAcceptedForkHeader(InvalidHeader invalid) throws Exception {
        ParamsConfig.Params params = ParamsConfig.RegressionNetParams;
        try (RocksDb database = new RocksDb()) {
            BlockChainServiceImpl chain = initChain(database, params);
            Block genesis = chain.getMainLatestBlock();
            assertTrue(chain.verifyBlock(branch(genesis, params, 8, 1).getLast()));
            List<Block> fork = branch(genesis, params, 1, 4);
            Block tip = fork.getLast();
            Peer healthy = peer(1, tip.getHeight(), tip.getChainWork().toBigInteger().longValueExact());
            healthy.setHashHex(bytesToHex(tip.getHash()));
            healthy.setLastSeen(2);
            Peer faulty = peer(2, tip.getHeight(), tip.getChainWork().toBigInteger().longValueExact());
            faulty.setChainTip(healthy.chainTip());
            faulty.setLastSeen(1);
            String healthyId = bytesToHex(healthy.getId());
            String faultyId = bytesToHex(faulty.getId());
            byte[] rejectedBatch = rejectedBatch(fork, invalid);

            RoutingTable routingTable = mock(RoutingTable.class);
            when(routingTable.getOnlineNodes()).thenReturn(List.of(healthy, faulty));
            NetServiceImpl net = spy(new NetServiceImpl());
            ReflectionTestUtils.setField(net, "routingTable", routingTable);
            ReflectionTestUtils.setField(net, "blockChainService", chain);
            record Request(String peerId, int startHeight, int downloadedHeaders, int verifiedHeaders) {}
            List<Request> requests = new ArrayList<>();
            doAnswer(call -> {
                String id = call.getArgument(0);
                ProtocolEnum protocol = call.getArgument(1);
                byte[] request = call.getArgument(2);
                byte[] payload;
                if (protocol == ProtocolEnum.P2P_Query_Block_Headers) {
                    int start = BlockHeadersPayload.deserializeRequest(request).startHeight();
                    SyncProgress progress = net.getSyncProgress();
                    requests.add(new Request(id, start, progress.downloadedHeaders(), progress.verifiedHeaders()));
                    if (id.equals(faultyId)) {
                        payload = rejectedBatch;
                    } else if (start == 1) {
                        payload = BlockHeadersPayload.serializeResponse(List.of(new BlockHeadersPayload.Entry(1,
                                fork.get(1).getBlockHeader())));
                    } else {
                        payload = syncResponse(protocol, request, fork);
                    }
                } else {
                    payload = syncResponse(protocol, request, fork);
                }
                return P2PMessage.newNormalMessage(id.equals(healthyId) ? healthy.getId() : faulty.getId(),
                        protocol, payload);
            }).when(net).sendSyncRequest(anyString(), any(), any(), anyInt());

            try {
                net.startSyncBlockHeadersFirst();
                Future<?> sync = (Future<?>) ReflectionTestUtils.getField(net, "currentSyncTask");
                sync.get(10, TimeUnit.SECONDS);
                SyncProgress progress = net.getSyncProgress();
                assertEquals(List.of(new Request(healthyId, 1, 0, 0), new Request(faultyId, 2, 1, 1),
                        new Request(healthyId, 2, 1, 1)), requests);
                assertEquals("COMPLETED", progress.status(), progress.message());
                assertEquals(tip.getHeight(), progress.downloadedHeaders());
                assertEquals(tip.getHeight(), progress.verifiedHeaders());
                assertEquals(tip.getHeight(), progress.verifiedBlocks());
                assertArrayEquals(tip.getHash(), chain.getMainLatestBlock().getHash());
                assertEquals(1, progress.peers().stream().filter(peer -> faultyId.equals(peer.peerId()))
                        .findFirst().orElseThrow().failedRequests());
            } finally {
                net.shutdown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void limitsUnverifiedDownloadsUntilDelayedBlockArrives(boolean headersFirst) throws Exception {
        int window = 128;
        try (RocksDb database = new RocksDb()) {
            ParamsConfig.Params params = ParamsConfig.RegressionNetParams;
            BlockChainServiceImpl chain = initChain(database, params);
            List<Block> blocks = branch(chain.getMainLatestBlock(), params, 1, window + 2);
            Block tip = blocks.getLast();
            Peer remote = peer(1, tip.getHeight(), tip.getChainWork().toBigInteger().longValueExact());
            remote.setHashHex(bytesToHex(tip.getHash()));
            NetServiceImpl net = syncService(chain, remote);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            doAnswer(call -> {
                ProtocolEnum protocol = call.getArgument(1);
                byte[] request = call.getArgument(2);
                if ((protocol == ProtocolEnum.P2P_Query_Block_By_Hash
                        && Arrays.equals(request, blocks.get(1).getHash()))
                        || (protocol == ProtocolEnum.P2P_Query_Block_By_Height
                                && ByteBuffer.wrap(request).getInt() == 1)) {
                    assertTrue(releaseFirst.await(30, TimeUnit.SECONDS));
                }
                return P2PMessage.newNormalMessage(remote.getId(), protocol, syncResponse(protocol, request, blocks));
            }).when(net).sendSyncRequest(anyString(), any(), any(), anyInt());

            try {
                Future<?> sync = startSync(net, headersFirst);
                await().atMost(Duration.ofSeconds(20)).until(() -> {
                    SyncProgress progress = net.getSyncProgress();
                    return progress.downloadedBlocks() >= window - 1 && progress.inFlightBlocks() == 1;
                });
                SyncProgress waiting = net.getSyncProgress();
                assertEquals(0, waiting.verifiedBlocks());
                assertEquals(window - 1, waiting.downloadedBlocks());

                releaseFirst.countDown();
                sync.get(30, TimeUnit.SECONDS);
                SyncProgress completed = net.getSyncProgress();
                assertEquals("COMPLETED", completed.status(), completed.message());
                assertEquals(tip.getHeight(), completed.verifiedBlocks());
                assertArrayEquals(tip.getHash(), chain.getMainLatestBlock().getHash());
            } finally {
                releaseFirst.countDown();
                net.shutdown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void nextPeerSuppliesBlockWhenFirstPeerStopsResponding(boolean headersFirst) throws Exception {
        try (RocksDb database = new RocksDb()) {
            ParamsConfig.Params params = ParamsConfig.RegressionNetParams;
            BlockChainServiceImpl chain = initChain(database, params);
            List<Block> blocks = branch(chain.getMainLatestBlock(), params, 1, 1);
            Block tip = blocks.getLast();
            Peer first = peer(1, 1, tip.getChainWork().toBigInteger().longValueExact());
            first.setHashHex(bytesToHex(tip.getHash()));
            Peer second = peer(2, 1, tip.getChainWork().toBigInteger().longValueExact());
            second.setChainTip(first.chainTip());
            NetServiceImpl net = syncService(chain, first, second);
            ProtocolEnum bodyRequest = headersFirst
                    ? ProtocolEnum.P2P_Query_Block_By_Hash
                    : ProtocolEnum.P2P_Query_Block_By_Height;
            List<String> attempted = new ArrayList<>();
            doAnswer(call -> {
                String id = call.getArgument(0);
                ProtocolEnum protocol = call.getArgument(1);
                if (protocol == bodyRequest) {
                    attempted.add(id);
                    if (attempted.size() == 1) {
                        return null;
                    }
                }
                Peer sender = id.equals(bytesToHex(first.getId())) ? first : second;
                return P2PMessage.newNormalMessage(sender.getId(), protocol,
                        syncResponse(protocol, call.getArgument(2), blocks));
            }).when(net).sendSyncRequest(anyString(), any(), any(), anyInt());

            try {
                startSync(net, headersFirst).get(10, TimeUnit.SECONDS);
                SyncProgress progress = net.getSyncProgress();
                assertEquals("COMPLETED", progress.status(), progress.message());
                assertEquals(2, attempted.size());
                assertNotEquals(attempted.getFirst(), attempted.getLast());
                assertEquals(1, progress.verifiedBlocks());
                assertEquals(1, progress.peers().stream().filter(p -> p.peerId().equals(attempted.getFirst()))
                        .findFirst().orElseThrow().failedRequests());
                assertArrayEquals(tip.getHash(), chain.getMainLatestBlock().getHash());
            } finally {
                net.shutdown();
            }
        }
    }

    @Test
    void chainTipSnapshotIsStableAndDefensivelyCopied() {
        try (RocksDb database = new RocksDb()) {
            BlockChainServiceImpl chain = initChain(database, ParamsConfig.RegressionNetParams);
            var previous = chain.getMainTipSnapshot();
            byte[] exposedHash = previous.hash();
            exposedHash[0] ^= 1;
            Block exposedBlock = chain.getMainLatestBlock();
            exposedBlock.setHeight(99);
            exposedBlock.getHash()[0] ^= 1;

            assertEquals(0, chain.getMainTipSnapshot().height());
            assertArrayEquals(chain.getMainLatestBlock().getHash(), chain.getMainTipSnapshot().hash());

            Block candidate = branch(chain.getMainLatestBlock(), ParamsConfig.RegressionNetParams, 1, 1).getLast();
            byte[] expectedHash = candidate.getHash().clone();
            assertTrue(chain.verifyBlock(candidate));
            candidate.setHeight(99);
            candidate.getHash()[0] ^= 1;
            assertEquals(1, chain.getMainTipSnapshot().height());
            assertArrayEquals(expectedHash, chain.getMainTipSnapshot().hash());
            assertEquals(0, previous.height());
            assertArrayEquals(BlockChainServiceImpl.createGenesisBlock(ParamsConfig.RegressionNetParams).getHash(),
                    previous.hash());
        }
    }

    private static NetServiceImpl syncService(BlockChainServiceImpl chain, Peer... peers) {
        RoutingTable routing = mock(RoutingTable.class);
        when(routing.getOnlineNodes()).thenReturn(List.of(peers));
        NetServiceImpl net = spy(new NetServiceImpl());
        ReflectionTestUtils.setField(net, "routingTable", routing);
        ReflectionTestUtils.setField(net, "blockChainService", chain);
        return net;
    }

    private static Future<?> startSync(NetServiceImpl net, boolean headersFirst) {
        if (headersFirst) {
            net.startSyncBlockHeadersFirst();
        } else {
            net.startSyncBlock();
        }
        return (Future<?>) ReflectionTestUtils.getField(net, "currentSyncTask");
    }

    private enum InvalidHeader {
        WRONG_TIP, WRONG_DIFFICULTY, MEDIAN_TIME, FUTURE_TIME
    }

    private static byte[] rejectedBatch(List<Block> fork, InvalidHeader invalid) {
        int height = invalid == InvalidHeader.WRONG_TIP ? fork.getLast().getHeight() : 3;
        List<BlockHeadersPayload.Entry> entries = new ArrayList<>();
        for (int i = 2; i < height; i++) {
            entries.add(new BlockHeadersPayload.Entry(i, fork.get(i).getBlockHeader()));
        }
        BlockHeader header = BlockHeader.deserialize(fork.get(height).getBlockHeader().serialize());
        switch (invalid) {
            case WRONG_TIP -> header.setNonce(header.getNonce() + 1);
            case WRONG_DIFFICULTY -> header.setBits(0x203fffff);
            case MEDIAN_TIME -> header.setTime(fork.get(1).getBlockHeader().getTime());
            case FUTURE_TIME -> header.setTime(System.currentTimeMillis() / 1000 + 86400);
        }
        while (!header.verifyProofOfWork(false)) {
            header.setNonce(header.getNonce() + 1);
        }
        entries.add(new BlockHeadersPayload.Entry(height, header));
        return BlockHeadersPayload.serializeResponse(entries);
    }

    @Test
    void restartKeepsEqualWorkTipAndWaitingBlocksReenterForkChoiceWhenTheirParentArrives() {
        var params = ParamsConfig.RegressionNetParams;
        try (var database = new RocksDb()) {
            BlockChainServiceImpl chain = initChain(database, params);
            Block genesis = chain.getMainLatestBlock();
            Block incumbent = branch(genesis, params, 8, 1).getLast();
            List<Block> fork = branch(genesis, params, 1, 3);
            assertTrue(chain.verifyBlock(incumbent));
            fork.get(1).setChainWork(UInt256.fromLong(1_000_000));
            assertTrue(chain.verifyBlock(fork.get(1)));
            assertArrayEquals(incumbent.getHash(), chain.getMainTipSnapshot().hash());
            chain.init();
            assertArrayEquals(incumbent.getHash(), chain.getMainTipSnapshot().hash());

            assertFalse(chain.verifyBlock(fork.get(3)));
            assertFalse(chain.hasFullBlock(fork.get(3).getHash()));
            assertArrayEquals(incumbent.getHash(), chain.getMainTipSnapshot().hash());
            assertTrue(chain.verifyBlock(fork.get(2)));
            assertArrayEquals(fork.get(3).getHash(), chain.getMainTipSnapshot().hash());
            assertEquals(fork.get(3).getChainWork(), chain.getMainTipSnapshot().chainWork());
            assertTrue(chain.getOrphanBlocks().isEmpty());
        }
    }

    @Test
    void startupActivatesStoredHeavierForkThenAcceptsItsHeaderOnlyChild() {
        var params = retargetParams();
        try (var database = new RocksDb(); var forkDatabase = new RocksDb()) {
            BlockChainServiceImpl chain = initChain(database, params);
            BlockChainServiceImpl fork = initChain(forkDatabase, params, directory.resolve("fork"));
            Block genesis = chain.getMainLatestBlock();
            List<Block> local = branch(genesis, params, 8, 3);
            for (Block block : local.subList(1, local.size())) {
                assertTrue(chain.verifyBlock(block));
            }
            List<Block> heavier = branch(genesis, params, 1, 3);
            for (Block block : heavier.subList(1, 3)) {
                assertTrue(fork.verifyBlock(block));
                // 模拟旧库已验证并保存分支，而 UTXO 和高度索引仍属于较轻链。
                for (TableEnum table : List.of(TableEnum.TBLOCK_HEADER, TableEnum.TBLOCK_BODY, TableEnum.BLOCK_UNDO,
                        TableEnum.HASH_TO_HEIGHT, TableEnum.HASH_TO_CHAIN_WORK)) {
                    database.insert(table, block.getHash(), forkDatabase.get(table, block.getHash()));
                }
            }
            Block winner = heavier.get(2);
            Block headerOnly = heavier.get(3);
            assertTrue(fork.addBlockHeaderToStore(headerOnly.getBlockHeader(), 3, headerOnly.getChainWork()));
            for (TableEnum table : List.of(TableEnum.TBLOCK_HEADER, TableEnum.HASH_TO_HEIGHT, TableEnum.HASH_TO_CHAIN_WORK)) {
                database.insert(table, headerOnly.getHash(), forkDatabase.get(table, headerOnly.getHash()));
            }
            assertArrayEquals(local.getLast().getHash(), chain.getMainTipSnapshot().hash());
            database.close();
            chain = initChain(database, params);

            assertArrayEquals(winner.getHash(), chain.getMainLatestBlock().getHash());
            assertEquals(2, chain.getMainTipSnapshot().height());
            assertEquals(winner.getChainWork(), chain.getMainTipSnapshot().chainWork());
            assertArrayEquals(winner.getHash(), database.get(TableEnum.CHAIN, BlockChainServiceImpl.MAIN_CHAIN_TIP_HASH_KEY));
            assertNull(database.get(TableEnum.HEIGHT_TO_HASH, 3));
            assertNull(database.get(TableEnum.TUTXO, local.getLast().getUTXOs().getFirst().getKey()));
            assertNotNull(database.get(TableEnum.TUTXO, winner.getUTXOs().getFirst().getKey()));
            assertNull(database.get(TableEnum.TBLOCK_BODY, headerOnly.getHash()));

            assertTrue(chain.verifyBlock(headerOnly));
            assertArrayEquals(headerOnly.getHash(), chain.getMainTipSnapshot().hash());
            assertArrayEquals(headerOnly.getHash(), database.get(TableEnum.HEIGHT_TO_HASH, 3));
        }
    }

    private static ParamsConfig.Params retargetParams() {
        ParamsConfig.Params regtest = ParamsConfig.RegressionNetParams;
        return new ParamsConfig.Params(regtest.getName(), regtest.getNet(), regtest.getDefaultPort(),
                regtest.getGenesisHash(), regtest.getPowLimit(), regtest.getPowLimitBits(), false, 10, 150,
                Duration.ofSeconds(8), Duration.ofSeconds(4), 4, false, Duration.ZERO);
    }

    private BlockChainServiceImpl initChain(RocksDb database, ParamsConfig.Params params) {
        return initChain(database, params, directory.resolve("chain"));
    }

    private BlockChainServiceImpl initChain(RocksDb database, ParamsConfig.Params params, Path path) {
        SystemConfig config = spy(new SystemConfig());
        config.setPath(path.toString());
        doReturn(params).when(config).getParams();
        assertTrue(database.createDatabase(config));
        BlockChainServiceImpl chain = new BlockChainServiceImpl();
        ReflectionTestUtils.setField(chain, "config", config);
        ReflectionTestUtils.setField(chain, "dataBase", database);
        ReflectionTestUtils.setField(chain, "txPool", mock(TxPool.class));
        ReflectionTestUtils.setField(chain, "utxoCache", mock(UTXOCache.class));
        ReflectionTestUtils.setField(chain, "routingTable", mock(RoutingTable.class));
        chain.init();
        return chain;
    }

    private static List<Block> branch(Block genesis, ParamsConfig.Params params, int spacing, int height) {
        List<Block> blocks = new ArrayList<>();
        blocks.add(genesis);
        for (int i = 1; i <= height; i++) {
            Block parent = blocks.getLast();
            long time = parent.getBlockHeader().getTime() + spacing;
            int bits = ConsensusRules.expectedBits(parent, time, params, hash -> blocks.stream()
                    .filter(block -> Arrays.equals(block.getHash(), hash)).findFirst().orElseThrow());
            Block block = new Block();
            block.setHeight(i);
            block.setTransactions(
                    List.of(BlockChainServiceImpl.createCoinBaseTransaction("1".repeat(32), i, 0, params)));
            block.setTxCount(1);
            BlockHeader header = new BlockHeader(1, parent.getHash(),
                    BlockChainServiceImpl.calculateMerkleRoot(block.getTransactions()), time, bits, 0);
            while (!header.verifyProofOfWork(false)) {
                header.setNonce(header.getNonce() + 1);
            }
            block.setBlockHeader(header);
            block.setChainWork(UInt256.fromBigInteger(parent.getChainWork().toBigInteger().add(header.getWork())));
            blocks.add(block);
        }
        return blocks;
    }

    private static byte[] syncResponse(ProtocolEnum protocol, byte[] request, List<Block> blocks) {
        return switch (protocol) {
            case P2P_Query_Common_Ancestor -> Landmark.deserializeList(request).stream()
                    .filter(landmark -> Arrays.equals(landmark.getHash(), blocks.get(landmark.getHeight()).getHash()))
                    .findFirst().orElseThrow().serialize();
            case P2P_Query_Block_Headers -> {
                BlockHeadersPayload.Request range = BlockHeadersPayload.deserializeRequest(request);
                yield BlockHeadersPayload.serializeResponse(blocks.subList(range.startHeight(), range.stopHeight() + 1)
                        .stream().map(block -> new BlockHeadersPayload.Entry(block.getHeight(), block.getBlockHeader()))
                        .toList());
            }
            case P2P_Query_Block_By_Hash -> blocks.stream().filter(block -> Arrays.equals(block.getHash(), request))
                    .findFirst().orElseThrow().serialize();
            case P2P_Query_Block_By_Height -> blocks.get(ByteBuffer.wrap(request).getInt()).serialize();
            default -> throw new AssertionError(protocol);
        };
    }

    private static Peer peer(int id, int height, long work) {
        byte[] peerId = new byte[32];
        Arrays.fill(peerId, (byte) id);
        return Peer.builder().id(peerId).isOnline(true)
                .height(height).chainWork(UInt256.fromLong(work)).build();
    }
}
