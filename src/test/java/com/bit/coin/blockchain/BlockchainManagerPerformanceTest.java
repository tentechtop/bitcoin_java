package com.bit.coin.blockchain;

import com.bit.coin.structure.block.BlockHeader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static com.bit.coin.utils.SerializeUtils.hexToBytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in performance checks for the header index.
 *
 * <p>The class is deliberately disabled for the normal test suite. Run it with
 * {@code mvn -Dblockchain.performance=true -Dtest=BlockchainManagerPerformanceTest test}.
 * The header count and query counts can be changed with the system properties
 * {@code blockchain.performance.headers}, {@code blockchain.performance.queries},
 * and {@code blockchain.performance.reorg-depth}. The million-header check needs
 * a heap sized for the in-memory snapshot, for example {@code MAVEN_OPTS=-Xmx2g}.</p>
 */
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
        long buildStarted = System.nanoTime();
        ChainDataset dataset = buildChain(count, EASY_BITS);
        long generatedNanos = System.nanoTime() - buildStarted;

        BlockchainManager manager = new BlockchainManager(dataset.genesisHash());
        long indexStarted = System.nanoTime();
        manager.setBlockHeaders(dataset.headers());
        long indexedNanos = System.nanoTime() - indexStarted;

        assertEquals(count, manager.getMainChainHeight());
        assertEquals(count, manager.getMainChainHashes().size());
        runRandomLookups(manager, dataset, queryCount, 0x6d696c6c696f6eL);

        long after = usedMemory();
        System.out.printf("[performance] headers=%d generated=%dms indexed=%dms heapDelta=%dMiB cacheBytes=%d cacheEntries=%d%n",
                count, millis(generatedNanos), millis(indexedNanos), (after - before) / (1024 * 1024),
                manager.getHeaderCacheEstimatedBytes(), manager.getHeaderCacheEntryCount());
    }

    @Test
    void randomHistoricalHeaderQueries() {
        int count = positiveIntProperty("blockchain.performance.history-headers", 100_000);
        int queryCount = positiveIntProperty("blockchain.performance.queries", DEFAULT_QUERY_COUNT);
        ChainDataset dataset = buildChain(count, EASY_BITS);
        BlockchainManager manager = new BlockchainManager(dataset.genesisHash());
        manager.setBlockHeaders(dataset.headers());

        long started = System.nanoTime();
        runRandomLookups(manager, dataset, queryCount, 0x686973746f7279L);
        long elapsed = System.nanoTime() - started;
        System.out.printf("[performance] random-history headers=%d queries=%d elapsed=%dms avg=%dns%n",
                count, queryCount, millis(elapsed), queryCount == 0 ? 0 : elapsed / queryCount);
    }

    @Test
    void deepReorganizationPathBenchmark() {
        int depth = positiveIntProperty("blockchain.performance.reorg-depth", DEFAULT_REORG_DEPTH);
        int ancestorIndex = Math.max(0, depth / 2);
        ChainDataset incumbent = buildChain(depth + 1, EASY_BITS);
        BlockchainManager manager = new BlockchainManager(incumbent.genesisHash());
        manager.setBlockHeaders(incumbent.headers());

        String ancestorHash = incumbent.hashes()[ancestorIndex];
        BlockHeader parentHeader = incumbent.headers().get(ancestorHash);
        BlockchainManager.BlockHeaderWrapper parentWrapper = manager.getBlockHeaderWrappers().get(ancestorHash);
        assertNotNull(parentWrapper);
        String forkTipHash = ancestorHash;
        long started = System.nanoTime();
        for (int index = ancestorIndex + 1; index <= depth; index++) {
            BlockHeader forkHeader = child(parentHeader, HEAVY_BITS);
            forkTipHash = hash(forkHeader);
            BlockchainManager.BlockHeaderWrapper forkWrapper = manager.new BlockHeaderWrapper(forkTipHash, forkHeader);
            forkWrapper.setHeight(parentWrapper.getHeight() + 1);
            forkWrapper.setChainWork(parentWrapper.getChainWork().add(forkHeader.getWork()));
            forkWrapper.setFullChain(true);
            manager.getBlockHeaders().put(forkTipHash, forkHeader);
            manager.getBlockHeaderWrappers().put(forkTipHash, forkWrapper);
            parentHeader = forkHeader;
            parentWrapper = forkWrapper;
        }

        BlockchainManager.ChainReorganizationResult result = manager.checkAndPerformReorganization(forkTipHash);
        long elapsed = System.nanoTime() - started;
        assertNotNull(result);
        assertEquals(ancestorHash, result.getCommonAncestorHash());
        assertEquals(depth - ancestorIndex + 1, result.getNewChainPath().size());
        assertEquals(forkTipHash, manager.getMainChainTip());
        assertTrue(manager.getMainChainWork().compareTo(BigInteger.ZERO) > 0);
        System.out.printf("[performance] reorg-depth=%d ancestor=%d path=%d elapsed=%dms%n",
                depth, ancestorIndex, result.getNewChainPath().size(), millis(elapsed));
    }

    private static void runRandomLookups(BlockchainManager manager, ChainDataset dataset,
            int queryCount, long seed) {
        Random random = new Random(seed);
        for (int query = 0; query < queryCount; query++) {
            int expectedHeight = random.nextInt(dataset.hashes().length);
            int actualHeight = manager.getHeightByHash(hexToBytes(dataset.hashes()[expectedHeight]));
            assertEquals(expectedHeight, actualHeight);
        }
    }

    private static ChainDataset buildChain(int count, int bits) {
        if (count < 1) {
            throw new IllegalArgumentException("Header count must be positive");
        }
        Map<String, BlockHeader> headers = new HashMap<>(mapCapacity(count));
        String[] hashes = new String[count];
        BlockHeader current = child(null, bits);
        hashes[0] = hash(current);
        headers.put(hashes[0], current);
        for (int index = 1; index < count; index++) {
            current = child(current, bits);
            hashes[index] = hash(current);
            headers.put(hashes[index], current);
        }
        return new ChainDataset(hashes[0], hashes, headers);
    }

    private static int mapCapacity(int count) {
        long capacity = (long) (count / 0.70f) + 1;
        return (int) Math.min(Integer.MAX_VALUE - 8L, Math.max(16L, capacity));
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

    private record ChainDataset(String genesisHash, String[] hashes, Map<String, BlockHeader> headers) {
    }
}
