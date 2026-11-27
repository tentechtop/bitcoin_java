package com.bit.coin.blockchain;

import com.bit.coin.structure.block.BlockHeader;
import com.bit.coin.utils.UInt256;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BlockchainManagerTest {

    private static final int EASY = 0x207fffff;

    @Test
    void persistentHeaderCacheStaysBoundedAndReloadsHistoricalHeaders() {
        BlockHeader genesis = child(null, EASY);
        PersistentIndex index = new PersistentIndex(genesis);
        index.manager().setHeaderCacheMemoryBytes(512 * 1024);

        BlockHeader current = genesis;
        String historicalHash = null;
        for (int height = 1; height <= 5_000; height++) {
            current = child(current, EASY);
            index.add(current, height);
            if (height == 1_000) {
                historicalHash = hash(current);
            }
        }

        assertEquals(0, index.manager().getBlockHeaders().size());
        assertEquals(0, index.manager().getBlockHeaderWrappers().size());
        assertTrue(index.manager().getHeaderCacheEstimatedBytes()
                <= index.manager().getEffectiveHeaderCacheMemoryBytes());
        assertEquals(1_000, index.manager().getHeightByHash(hex(historicalHash)));
        assertTrue(index.manager().getHeaderCacheEntryCount() > 0);
    }

    @Test
    void missingParentCannotEnterPersistentHeaderIndex() {
        BlockHeader genesis = child(null, EASY);
        PersistentIndex index = new PersistentIndex(genesis);
        BlockHeader missingParent = child(child(genesis, EASY), EASY);

        assertThrows(IllegalStateException.class,
                () -> index.add(missingParent, 2));
        assertFalse(index.manager().hasFullBlock(missingParent.calculateHash()));
    }

    @Test
    void summaryConfigurationCanBeSetBeforeSummariesExist() {
        BlockHeader genesis = child(null, EASY);
        PersistentIndex index = new PersistentIndex(genesis);
        index.manager().setSummaryBlockCount(200);
        assertEquals(200, index.manager().getSummaryBlockCount());
    }

    private static BlockHeader child(BlockHeader parent, int bits) {
        return new BlockHeader(1, parent == null ? new byte[32] : parent.calculateHash(), new byte[32],
                parent == null ? 0 : parent.getTime() + 1, bits, 0);
    }

    private static String hash(BlockHeader header) {
        return bytesToHex(header.calculateHash());
    }

    private static byte[] hex(String hash) {
        return com.bit.coin.utils.SerializeUtils.hexToBytes(hash);
    }

    private static final class PersistentIndex {
        private final Map<String, BlockHeader> headers = new HashMap<>();
        private final Map<String, Integer> heights = new HashMap<>();
        private final Map<String, BigInteger> works = new HashMap<>();
        private final BlockchainManager manager;

        private PersistentIndex(BlockHeader genesis) {
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
            BigInteger work = parentWork == null ? BigInteger.ONE : parentWork.add(header.getWork());
            assertTrue(manager.acceptHeader(header, height, UInt256.fromBigInteger(work)), hash(header));
        }

        private BlockchainManager manager() {
            return manager;
        }
    }
}
