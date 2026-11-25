package com.bit.coin.config;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.structure.block.Block;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ParamsConfigTest {

    @Test
    void mainnetMatchesBitcoinConsensusAndTransportDefaults() {
        ParamsConfig.Params params = ParamsConfig.MainNetParams;

        assertEquals("mainnet", params.getName());
        assertArrayEquals(hex("f9beb4d9"), params.getNetworkMagic());
        assertEquals(8333, Integer.parseInt(params.getDefaultPort()));
        assertEquals(8332, params.getRpcPort());
        assertEquals(100, params.getCoinbaseMaturity());
        assertEquals(210_000, params.getSubsidyReductionInterval());
        assertEquals(Duration.ofMinutes(10), params.getTargetTimePerBlock());
        assertEquals(Duration.ofDays(14), params.getTargetTimespan());
        assertEquals(2_016, params.getDifficultyAdjustmentInterval());
        assertEquals(0x1d00ffffL, params.getPowLimitBits());
        assertEquals(new BigInteger("00000000ffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 16),
                params.getPowLimit());
        assertFalse(params.isPowNoRetargeting());
        assertFalse(params.isReduceMinDifficulty());
        assertGenesisMatches(params);
    }

    @Test
    void testnet3MatchesBitcoinConsensusAndTransportDefaults() {
        ParamsConfig.Params params = ParamsConfig.TestNet3Params;

        assertEquals("testnet3", params.getName());
        assertArrayEquals(hex("0b110907"), params.getNetworkMagic());
        assertEquals(18333, Integer.parseInt(params.getDefaultPort()));
        assertEquals(18332, params.getRpcPort());
        assertEquals(100, params.getCoinbaseMaturity());
        assertEquals(210_000, params.getSubsidyReductionInterval());
        assertEquals(Duration.ofMinutes(10), params.getTargetTimePerBlock());
        assertEquals(Duration.ofDays(14), params.getTargetTimespan());
        assertEquals(2_016, params.getDifficultyAdjustmentInterval());
        assertEquals(0x1d00ffffL, params.getPowLimitBits());
        assertTrue(params.isReduceMinDifficulty());
        assertEquals(Duration.ofMinutes(20), params.getMinDiffReductionTime());
        assertGenesisMatches(params);
    }

    @Test
    void regtestMatchesBitcoinConsensusAndTransportDefaults() {
        ParamsConfig.Params params = ParamsConfig.RegressionNetParams;

        assertEquals("regtest", params.getName());
        assertArrayEquals(hex("fabfb5da"), params.getNetworkMagic());
        assertEquals(18444, Integer.parseInt(params.getDefaultPort()));
        assertEquals(18443, params.getRpcPort());
        assertEquals(100, params.getCoinbaseMaturity());
        assertEquals(150, params.getSubsidyReductionInterval());
        assertEquals(Duration.ofMinutes(10), params.getTargetTimePerBlock());
        assertEquals(Duration.ofDays(1), params.getTargetTimespan());
        assertEquals(144, params.getDifficultyAdjustmentInterval());
        assertEquals(0x207fffffL, params.getPowLimitBits());
        assertTrue(params.isPowNoRetargeting());
        assertTrue(params.isReduceMinDifficulty());
        assertGenesisMatches(params);
    }

    private static void assertGenesisMatches(ParamsConfig.Params params) {
        Block genesis = BlockChainServiceImpl.createGenesisBlock(params);
        assertArrayEquals(params.getGenesisHash(), genesis.getHash());
        assertEquals(params.getGenesisTime(), genesis.getBlockHeader().getTime());
        assertEquals(params.getGenesisNonce(), Integer.toUnsignedLong(genesis.getBlockHeader().getNonce()));
        assertEquals(params.getPowLimitBits(), Integer.toUnsignedLong(genesis.getBlockHeader().getBits()));
        assertTrue(genesis.getBlockHeader().verifyProofOfWork(false));
        assertArrayEquals(genesis.getBlockHeader().getMerkleRoot(),
                BlockChainServiceImpl.calculateMerkleRoot(genesis.getTransactions()));
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }
}
