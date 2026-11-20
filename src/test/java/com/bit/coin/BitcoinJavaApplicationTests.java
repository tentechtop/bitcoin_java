package com.bit.coin;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.observability.NodeHealthIndicator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = { "system.networking-enabled=false", "system.net-params=RegressionNetParams",
        "logging.file.path=target/test-logs" })
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BitcoinJavaApplicationTests {

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry properties) throws IOException {
        Path directory = Files.createTempDirectory(Path.of("target"), "context-test-");
        properties.add("system.path", directory::toString);
    }

    @Autowired
    BlockChainServiceImpl chain;
    @Autowired
    NodeHealthIndicator health;
    @Autowired
    com.bit.coin.config.SystemConfig config;
    @Autowired
    com.bit.coin.mining.MiningServiceImpl mining;

    @Test
    void contextLoadsWithAnIsolatedRegressionDatabase() {
        assertTrue(chain.isStateHealthy());
        assertEquals(0, chain.getMainLatestBlock().getHeight());
        assertEquals("UP", health.health().getStatus().getCode());
        config.setMinerAddressList(java.util.List.of(org.bitcoinj.base.Base58.encode(new byte[32])));
        try {
            assertEquals(1, mining.mineOneBlock().getHeight());
        } catch (Exception failure) {
            throw new AssertionError("Configured isolated node must mine a valid block", failure);
        }
    }
}
