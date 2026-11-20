package com.bit.coin.blockchain;

import com.bit.coin.structure.block.BlockHeader;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static org.junit.jupiter.api.Assertions.*;

class BlockchainManagerTest {

    private static final int EASY = 0x207fffff; // 每块工作量 2
    private final BlockHeader genesis = child(null, EASY);
    private final BlockchainManager manager = new BlockchainManager(hash(genesis));

    @Test
    void shorterHeavierForkWinsUntilRemovedFromTheSnapshot() {
        BlockHeader first = child(genesis, EASY);
        BlockHeader second = child(first, EASY);
        BlockHeader heavy = child(genesis, 0x201fffff); // 工作量 8
        manager.setBlockHeaders(headers(second, heavy, genesis, first));
        assertEquals(List.of(hash(genesis), hash(heavy)), manager.getMainChainHashes());
        assertEquals(BigInteger.TEN, manager.getMainChainWork());
        assertEquals(2, manager.getMainChainHeight());

        manager.setBlockHeaders(headers(first, genesis, second));
        assertEquals(List.of(hash(genesis), hash(first), hash(second)), manager.getMainChainHashes());
        assertEquals(BigInteger.valueOf(6), manager.getMainChainWork());
        assertEquals(3, manager.getMainChainHeight());
        assertFalse(manager.getBlockHeaderWrappers().containsKey(hash(heavy)));
    }

    @Test
    void detachedBranchOnlyCompetesOnceItsAncestorIsPresent() {
        BlockHeader missing = child(genesis, EASY);
        BlockHeader orphan = child(missing, EASY);
        BlockHeader descendant = child(orphan, 0x201fffff);
        manager.setBlockHeaders(headers(genesis, orphan, descendant));
        assertEquals(hash(genesis), manager.getMainChainTip());
        assertTrue(manager.isOrphanBlock(hash(descendant)));
        assertThrows(IllegalArgumentException.class, () -> manager.checkAndPerformReorganization(hash(descendant)));

        manager.setBlockHeaders(headers(descendant, genesis, orphan, missing));
        assertEquals(hash(descendant), manager.getMainChainTip());
        assertFalse(manager.isOrphanBlock(hash(descendant)));
        assertTrue(manager.getOrphanBlocks().isEmpty());
    }

    @Test
    void equalCumulativeWorkKeepsTheCurrentTipEvenWhenTheForkIsTaller() {
        BlockHeader incumbent = child(genesis, 0x203fffff); // 工作量 4
        BlockHeader first = child(genesis, EASY);
        BlockHeader second = child(first, EASY);
        manager.setBlockHeaders(headers(genesis, incumbent));
        manager.setBlockHeaders(headers(second, first, incumbent, genesis));
        assertEquals(hash(incumbent), manager.getMainChainTip());

        BlockHeader third = child(second, EASY);
        manager.setBlockHeaders(headers(genesis, incumbent, first, second, third));
        assertEquals(hash(third), manager.getMainChainTip());
    }

    @Test
    void rebuildsDeepChainsWithoutRecursing() {
        Map<String, BlockHeader> chain = headers(genesis);
        BlockHeader tip = genesis;
        for (int i = 1; i < 10_000; i++) {
            tip = child(tip, EASY);
            chain.put(hash(tip), tip);
        }
        manager.setBlockHeaders(chain);
        assertEquals(hash(tip), manager.getMainChainTip());
        assertEquals(BigInteger.valueOf(20_000), manager.getMainChainWork());
    }

    private static BlockHeader child(BlockHeader parent, int bits) {
        return new BlockHeader(1, parent == null ? new byte[32] : parent.calculateHash(), new byte[32],
                parent == null ? 0 : parent.getTime() + 1, bits, 0);
    }

    private static String hash(BlockHeader header) {
        return bytesToHex(header.calculateHash());
    }

    private static Map<String, BlockHeader> headers(BlockHeader... headers) {
        Map<String, BlockHeader> result = new HashMap<>();
        for (BlockHeader header : headers) {
            result.put(hash(header), header);
        }
        return result;
    }
}
