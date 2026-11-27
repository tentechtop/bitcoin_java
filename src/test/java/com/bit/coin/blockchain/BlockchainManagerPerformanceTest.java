package com.bit.coin.blockchain;

import com.bit.coin.structure.block.BlockHeader;
import com.bit.coin.utils.UInt256;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static com.bit.coin.utils.SerializeUtils.hexToBytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Opt-in benchmarks for the persistent, bounded header index. */
@Tag("performance")
@EnabledIfSystemProperty(named = "blockchain.performance", matches = "true")
class BlockchainManagerPerformanceTest {

    private static final int EASY_BITS = 0x207fffff;
    private static final int HEAVY_BITS = 0x201fffff;
    private static final int DEFAULT_HEADER_COUNT = 1_000_000;
    private static final int DEFAULT_QUERY_COUNT = 10_000;
    private static final int DEFAULT_REORG_DEPTH = 20_000;

    @Test
    void millionHeaderIndexBuildAndLookups() {
        int count = positiveIntProperty("blockchain.performance.headers", DEFAULT_HEADER_COUNT);
        int queryCount = positiveIntProperty("blockchain.performance.queries", DEFAULT_QUERY_COUNT);
        long before = usedMemory();
        PersistentIndex index = new PersistentIndex(child(null, EASY_BITS));
        index.manager().setHeaderCacheMemoryBytes(32L * 1024 * 1024);
        String[] hashes = new String[count];
        BlockHeader current = index.genesis();
        long buildStarted = System.nanoTime();
        hashes[0] = hash(current);
        for (int height = 1; height < count; height++) {
            current = child(current, EASY_BITS);
            hashes[height] = hash(current);
            index.add(current, height);
        }
        long indexedNanos = System.nanoTime() - buildStarted;
        runRandomLookups(index.manager(), hashes, queryCount, 0x6d696c6c696f6eL);

        long after = usedMemory();
        assertTrue(index.manager().getHeaderCacheEstimatedBytes()
                <= index.manager().getEffectiveHeaderCacheMemoryBytes());
        assertEquals(0, index.manager().getBlockHeaders().size());
        System.out.printf("[performance] headers=%d indexed=%dms heapDelta=%dMiB cacheBytes=%d cacheEntries=%d%n",
                count, millis(indexedNanos), (after - before) / (1024 * 1024),
                index.manager().getHeaderCacheEstimatedBytes(), index.manager().getHeaderCacheEntryCount());
    }

    @Test
    void randomHistoricalHeaderQueries() {
        int count = positiveIntProperty("blockchain.performance.history-headers", 100_000);
        int queryCount = positiveIntProperty("blockchain.performance.queries", DEFAULT_QUERY_COUNT);
        PersistentIndex index = new PersistentIndex(child(null, EASY_BITS));
        String[] hashes = appendChain(index, count, EASY_BITS);

        long started = System.nanoTime();
        runRandomLookups(index.manager(), hashes, queryCount, 0x686973746f7279L);
        long elapsed = System.nanoTime() - started;
        System.out.printf("[performance] random-history headers=%d queries=%d elapsed=%dms avg=%dns cacheBytes=%d%n",
                count, queryCount, millis(elapsed), queryCount == 0 ? 0 : elapsed / queryCount,
                index.manager().getHeaderCacheEstimatedBytes());
    }

    @Test
    void deepReorganizationPathBenchmark() throws Exception {
        int depth = positiveIntProperty("blockchain.performance.reorg-depth", DEFAULT_REORG_DEPTH);
        int ancestorIndex = Math.max(0, depth / 2);
        PersistentIndex index = new PersistentIndex(child(null, EASY_BITS));
        String[] incumbentHashes = appendChain(index, depth + 1, EASY_BITS);
        String ancestorHash = incumbentHashes[ancestorIndex];
        BlockHeader parentHeader = index.headers().get(ancestorHash);
        String forkTipHash = ancestorHash;
        long started = System.nanoTime();
        for (int height = ancestorIndex + 1; height <= depth; height++) {
            BlockHeader forkHeader = child(parentHeader, HEAVY_BITS);
            forkTipHash = hash(forkHeader);
            index.add(forkHeader, height);
            parentHeader = forkHeader;
        }
        setMainTip(index.manager(), incumbentHashes[depth]);
        String commonAncestor = invokeCommonAncestor(index.manager(), forkTipHash, incumbentHashes[depth]);
        List<String> newPath = invokeChainPath(index.manager(), commonAncestor, forkTipHash);
        long elapsed = System.nanoTime() - started;
        assertEquals(ancestorHash, commonAncestor);
        assertEquals(depth - ancestorIndex + 1, newPath.size());
        System.out.printf("[performance] reorg-depth=%d ancestor=%d path=%d elapsed=%dms%n",
                depth, ancestorIndex, newPath.size(), millis(elapsed));
    }

    private static String[] appendChain(PersistentIndex index, int count, int bits) {
        String[] hashes = new String[count];
        BlockHeader current = index.genesis();
        hashes[0] = hash(current);
        for (int height = 1; height < count; height++) {
            current = child(current, bits);
            hashes[height] = hash(current);
            index.add(current, height);
        }
        return hashes;
    }

    private static void runRandomLookups(BlockchainManager manager, String[] hashes,
            int queryCount, long seed) {
        Random random = new Random(seed);
        for (int query = 0; query < queryCount; query++) {
            int expectedHeight = random.nextInt(hashes.length);
            int actualHeight = manager.getHeightByHash(hexToBytes(hashes[expectedHeight]));
            assertEquals(expectedHeight, actualHeight);
        }
    }

    private static void setMainTip(BlockchainManager manager, String hash) throws Exception {
        Method wrapperFor = BlockchainManager.class.getDeclaredMethod("wrapperFor", String.class);
        wrapperFor.setAccessible(true);
        Object wrapper = wrapperFor.invoke(manager, hash);
        Field mainTip = BlockchainManager.class.getDeclaredField("mainTip");
        mainTip.setAccessible(true);
        mainTip.set(manager, wrapper);
    }

    @SuppressWarnings("unchecked")
    private static String invokeCommonAncestor(BlockchainManager manager, String left, String right) throws Exception {
        Method method = BlockchainManager.class.getDeclaredMethod("findCommonAncestor", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(manager, left, right);
    }

    @SuppressWarnings("unchecked")
    private static List<String> invokeChainPath(BlockchainManager manager, String ancestor, String tip)
            throws Exception {
        Method method = BlockchainManager.class.getDeclaredMethod("buildChainPath", String.class, String.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(manager, ancestor, tip);
    }

    private static BlockHeader child(BlockHeader parent, int bits) {
        return new BlockHeader(1, parent == null ? new byte[32] : parent.calculateHash(), new byte[32],
                parent == null ? 0 : parent.getTime() + 1, bits, 0);
    }

    private static String hash(BlockHeader header) {
        return bytesToHex(header.calculateHash());
    }

    private static int positiveIntProperty(String name, int defaultValue) {
        int value = Integer.getInteger(name, defaultValue);
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long usedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000L;
    }

    private static final class PersistentIndex {
        private final Map<String, BlockHeader> headers = new HashMap<>();
        private final Map<String, Integer> heights = new HashMap<>();
        private final Map<String, BigInteger> works = new HashMap<>();
        private final BlockHeader genesis;
        private final BlockchainManager manager;

        private PersistentIndex(BlockHeader genesis) {
            this.genesis = genesis;
            ChainStateStore store = mock(ChainStateStore.class);
            String genesisHash = hash(genesis);
            headers.put(genesisHash, genesis);
            heights.put(genesisHash, 0);
            works.put(genesisHash, genesis.getWork());
            when(store.loadHeaderSummaries()).thenReturn(List.of());
            when(store.storedHeader(any(byte[].class)))
                    .thenAnswer(invocation -> headers.get(bytesToHex(invocation.getArgument(0))));
            when(store.storedHeight(any(byte[].class)))
                    .thenAnswer(invocation -> heights.getOrDefault(bytesToHex(invocation.getArgument(0)), -1));
            when(store.storedChainWork(any(byte[].class)))
                    .thenAnswer(invocation -> works.getOrDefault(bytesToHex(invocation.getArgument(0)), BigInteger.ZERO));
            when(store.storedBodyExists(any(byte[].class))).thenReturn(true);
            doAnswer(invocation -> {
                byte[] hash = invocation.getArgument(0);
                BlockHeader header = invocation.getArgument(1);
                int height = invocation.getArgument(2);
                UInt256 work = invocation.getArgument(3);
                String key = bytesToHex(hash);
                headers.put(key, BlockHeader.deserialize(header.serialize()));
                heights.put(key, height);
                works.put(key, work.toBigInteger());
                return null;
            }).when(store).commitHeader(any(byte[].class), any(BlockHeader.class), anyInt(), any(UInt256.class));
            manager = new BlockchainManager(genesisHash, store, null);
            manager.loadStoredIndex(genesisHash);
        }

        private void add(BlockHeader header, int height) {
            BigInteger parentWork = works.get(bytesToHex(header.getPreviousHash()));
            BigInteger work = parentWork.add(header.getWork());
            assertTrue(manager.acceptHeader(header, height, UInt256.fromBigInteger(work)));
        }

        private BlockHeader genesis() {
            return genesis;
        }

        private Map<String, BlockHeader> headers() {
            return headers;
        }

        private BlockchainManager manager() {
            return manager;
        }
    }
}
