package com.bit.coin.net;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.blockchain.ConsensusRules;
import com.bit.coin.blockchain.Landmark;
import com.bit.coin.cache.UTXOCache;
import com.bit.coin.config.ParamsConfig;
import com.bit.coin.config.SystemConfig;
import com.bit.coin.database.rocksDb.RocksDb;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @TempDir Path directory;
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
    @CsvSource({"2,true", "3,true", "3,false"})
    void reorganizesToTheHigherWorkBranch(int localHeight, boolean automatic) throws Exception {
        ParamsConfig.Params regtest = ParamsConfig.RegressionNetParams;
        ParamsConfig.Params params = new ParamsConfig.Params(regtest.getName(), regtest.getNet(), regtest.getDefaultPort(),
                regtest.getGenesisHash(), regtest.getPowLimit(), regtest.getPowLimitBits(), false, 10, 150,
                Duration.ofSeconds(8), Duration.ofSeconds(4), 4, false, Duration.ZERO);
        Block previousTip = BlockChainServiceImpl.mainTipBlock;
        try (RocksDb database = new RocksDb()) {
            BlockChainServiceImpl chain = initChain(database, params);
            Block genesis = chain.getMainLatestBlock();
            List<Block> local = branch(genesis, params, 8, localHeight);
            for (Block block : local.subList(1, local.size())) assertTrue(chain.verifyBlock(block));
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
            when(routingTable.getOnlineNodes()).thenReturn(automatic ? List.of(taller, overstated, remote) : List.of(taller, remote));
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
                        protocol, syncResponse(protocol, request, blocks)).serialize();
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
                if (automatic) {
                    assertTrue(progress.recentErrors().stream().anyMatch(error -> error.contains("do not exceed local cumulative work")));
                    verify(net, never()).sendSyncRequest(eq(overstatedId), eq(ProtocolEnum.P2P_Query_Block_By_Hash), any(), anyInt());
                }
            } finally {
                trigger.stop();
                net.shutdown();
            }
        } finally {
            BlockChainServiceImpl.mainTipBlock = previousTip;
        }
    }

    @ParameterizedTest
    @EnumSource(InvalidHeader.class)
    void retriesRejectedBatchFromLastAcceptedForkHeader(InvalidHeader invalid) throws Exception {
        ParamsConfig.Params params = ParamsConfig.RegressionNetParams;
        Block previousTip = BlockChainServiceImpl.mainTipBlock;
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
                        protocol, payload).serialize();
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
        } finally {
            BlockChainServiceImpl.mainTipBlock = previousTip;
        }
    }

    private enum InvalidHeader { WRONG_TIP, WRONG_DIFFICULTY, MEDIAN_TIME, FUTURE_TIME }

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
        while (!header.verifyProofOfWork(false)) header.setNonce(header.getNonce() + 1);
        entries.add(new BlockHeadersPayload.Entry(height, header));
        return BlockHeadersPayload.serializeResponse(entries);
    }

    private BlockChainServiceImpl initChain(RocksDb database, ParamsConfig.Params params) {
        SystemConfig config = spy(new SystemConfig());
        config.setPath(directory.resolve("chain").toString());
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
            block.setTransactions(List.of(BlockChainServiceImpl.createCoinBaseTransaction("1".repeat(32), i, 0, params)));
            block.setTxCount(1);
            BlockHeader header = new BlockHeader(1, parent.getHash(),
                    BlockChainServiceImpl.calculateMerkleRoot(block.getTransactions()), time, bits, 0);
            while (!header.verifyProofOfWork(false)) header.setNonce(header.getNonce() + 1);
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
                        .stream().map(block -> new BlockHeadersPayload.Entry(block.getHeight(), block.getBlockHeader())).toList());
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