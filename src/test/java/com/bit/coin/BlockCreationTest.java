package com.bit.coin;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.config.ParamsConfig;
import com.bit.coin.structure.block.Block;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class BlockCreationTest {
    @Test void roundTripsTheActualNodeWireFormat() throws Exception {
        Block genesis = BlockChainServiceImpl.createGenesisBlock(ParamsConfig.RegressionNetParams);
        byte[] wire = genesis.serialize();
        Block decoded = Block.deserialize(wire);
        assertArrayEquals(wire, decoded.serialize());
        assertArrayEquals(genesis.getHash(), decoded.getHash());
        assertTrue(decoded.getBlockHeader().verifyProofOfWork(false));
        assertEquals(decoded.getTransactions().size(), decoded.getTxCount());
        assertArrayEquals(decoded.getBlockHeader().getMerkleRoot(), BlockChainServiceImpl.calculateMerkleRoot(decoded.getTransactions()));
    }
    @Test void rejectsTruncatedAndTrailingBlockData() throws Exception {
        byte[] wire = BlockChainServiceImpl.createGenesisBlock(ParamsConfig.RegressionNetParams).serialize();
        assertThrows(Exception.class, () -> Block.deserialize(Arrays.copyOf(wire, wire.length - 1)));
        assertThrows(Exception.class, () -> Block.deserialize(Arrays.copyOf(wire, wire.length + 1)));
    }
}
