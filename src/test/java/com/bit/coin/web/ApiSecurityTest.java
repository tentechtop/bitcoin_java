package com.bit.coin.web;

import com.bit.coin.api.FeeEstimateApi;
import com.bit.coin.api.P2PAdminApi;
import com.bit.coin.api.SignedTransactionApi;
import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.config.CorsConfig;
import com.bit.coin.p2p.production.P2PDisconnectResult;
import com.bit.coin.p2p.production.P2PNodeStatus;
import com.bit.coin.p2p.production.ProductionP2PService;
import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.structure.tx.TxInput;
import com.bit.coin.structure.tx.TxOutput;
import com.bit.coin.structure.tx.UTXO;
import com.bit.coin.txpool.TxPool;
import com.bit.coin.txpool.TxPoolImpl;
import com.bit.coin.utils.Ed25519Signer;
import com.bit.coin.utils.Sha;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Map;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(ApiSecurityTest.Config.class)
@WebAppConfiguration
@TestPropertySource(properties = {
        "node.api.admin-token=" + ApiSecurityTest.ADMIN_TOKEN,
        "node.api.read-token=" + ApiSecurityTest.READ_TOKEN,
        "node.api.requests-per-second=100", "node.api.max-request-bytes=1024" })
@MockitoBean(types = { BlockChainServiceImpl.class, ProductionP2PService.class })
class ApiSecurityTest {

    static final String ADMIN_TOKEN = "local-http-permission-test-administrator";
    static final String READ_TOKEN = "local-http-permission-test-reader";
    private final String admin = "Bearer " + ADMIN_TOKEN;
    private final String reader = "Bearer " + READ_TOKEN;

    @Configuration
    @EnableWebMvc
    @Import({ ApiSecurityConfig.class, CorsConfig.class, ApiExceptionHandler.class, Endpoints.class,
            FeeEstimateApi.class, SignedTransactionApi.class, P2PAdminApi.class })
    static class Config {

        @Bean
        TxPool feeEstimationPool() {
            TxPool pool = mock(TxPool.class, CALLS_REAL_METHODS);
            TxPoolImpl empty = new TxPoolImpl();
            try {
                when(pool.getStatusSnapshot()).thenReturn(empty.getStatusSnapshot());
            } finally {
                empty.close();
            }
            return pool;
        }
    }

    @RestController
    static class Endpoints {

        @GetMapping("/chain/status")
        String read() {
            return "ready";
        }

        @GetMapping("/actuator/health")
        String health() {
            return "UP";
        }

        @GetMapping("/actuator/prometheus")
        String metrics() {
            return "test_metric 1\n";
        }
    }

    MockMvc mvc;

    @BeforeEach
    void setup(@Autowired WebApplicationContext context) {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Nested
    class AuthenticatedReads extends RolePermissions {

        @Test
        void chainStatusRequiresCredentialsAndHealthIsPublic() throws Exception {
            mvc.perform(get("/chain/status")).andExpect(status().isUnauthorized());
            mvc.perform(get("/chain/status").header("Authorization", reader)).andExpect(status().isOk());
            mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        }

        @Test
        void feeEstimatesUseTheExistingReadPermission() throws Exception {
            mvc.perform(get("/txpool/fee-estimate").param("txSize", "250")).andExpect(status().isUnauthorized());
            mvc.perform(get("/txpool/fee-estimates").param("txSize", "250")).andExpect(status().isUnauthorized());
            for (String credential : new String[] { reader, admin }) {
                mvc.perform(get("/txpool/fee-estimate").param("txSize", "250").header("Authorization", credential))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.txSize").value(250));
                mvc.perform(get("/txpool/fee-estimates").param("txSize", "250").header("Authorization", credential))
                        .andExpect(status().isOk()).andExpect(jsonPath("$[0].txSize").value(250));
            }
        }
    }

    @Nested
    @TestPropertySource(properties = "node.api.public-read=true")
    class PublicReads extends RolePermissions {

        @Test
        void anonymousUsersCanReadFeeEstimates() throws Exception {
            mvc.perform(get("/txpool/fee-estimate").param("txSize", "250").param("targetBlocks", "6"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.targetBlocks").value(6));
            mvc.perform(get("/txpool/fee-estimates").param("txSize", "250"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].txSize").value(250));
        }
    }

    abstract class RolePermissions {

        @Test
        void signedTransactionBroadcastRequiresReaderOrAdmin(@Autowired BlockChainServiceImpl chain,
                @Autowired TxPool pool) throws Exception {
            Transaction tx = signedTransaction();
            String body = "{\"rawTransaction\":\"" + bytesToHex(tx.serialize()) + "\"}";
            when(chain.isStateHealthy()).thenReturn(true);
            when(pool.addTx(any(Transaction.class))).thenReturn(true);

            mvc.perform(post("/tx/broadcast").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
            verify(chain, never()).isStateHealthy();
            for (String credential : new String[] { reader, admin }) {
                mvc.perform(post("/tx/broadcast").header("Authorization", credential)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("accepted"))
                        .andExpect(jsonPath("$.txId").value(bytesToHex(tx.getTxId())));
            }
        }

        @Test
        void disconnectRequiresAdmin(@Autowired ProductionP2PService peers) throws Exception {
            String peerId = "ab".repeat(32);
            when(peers.disconnect(peerId))
                    .thenReturn(new P2PDisconnectResult(true, P2PNodeStatus.DISCONNECTED, peerId, "disconnected"));

            mvc.perform(post("/p2p/disconnect").param("peerId", peerId)).andExpect(status().isUnauthorized());
            mvc.perform(post("/p2p/disconnect").param("peerId", peerId).header("Authorization", reader))
                    .andExpect(status().isForbidden());
            verify(peers, never()).disconnect(anyString());
            mvc.perform(post("/p2p/disconnect").param("peerId", peerId).header("Authorization", admin))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.peerId").value(peerId));
            verify(peers).disconnect(peerId);
        }

        @Test
        void prometheusRequiresReaderOrAdmin() throws Exception {
            mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
            for (String credential : new String[] { reader, admin }) {
                mvc.perform(get("/actuator/prometheus").header("Authorization", credential))
                        .andExpect(status().isOk()).andExpect(content().string("test_metric 1\n"));
            }
        }
    }

    private static Transaction signedTransaction() {
        var owner = Ed25519Signer.generateKeyPair();
        byte[] publicKey = Ed25519Signer.extractPublicKeyCore(owner.getPublic());
        UTXO source = new UTXO();
        source.setTxId(Sha.applySHA256(new byte[] { 1 }));
        source.setScript(Sha.applyRIPEMD160(Sha.applySHA256(publicKey)));
        source.setValue(100_000L);

        TxInput input = new TxInput();
        input.setTxId(source.getTxId());
        TxOutput output = new TxOutput();
        output.setScriptPubKey(source.getScript());
        output.setValue(90_000L);

        Transaction tx = new Transaction();
        tx.setVersion(1);
        tx.getInputs().add(input);
        tx.getOutputs().add(output);
        tx.setUtxoMap(Map.of(bytesToHex(source.getKey()), source));
        tx.sign(Ed25519Signer.extractPrivateKeyCore(owner.getPrivate()), publicKey);
        return tx;
    }

    @Test
    void credentialsAreNeverAcceptedFromQueryParametersOrCookies() throws Exception {
        mvc.perform(get("/chain/status").param("token", ADMIN_TOKEN)).andExpect(status().isUnauthorized());
        mvc.perform(get("/chain/status").cookie(new jakarta.servlet.http.Cookie("token", ADMIN_TOKEN)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/chain/status").header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
    }

    @Test
    void administrationIsNotAvailableThroughGet() throws Exception {
        mvc.perform(get("/p2p/disconnect").header("Authorization", admin)).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void arbitraryOriginsAndOversizedBodiesAreRejected() throws Exception {
        mvc.perform(options("/p2p/disconnect").header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden());
        mvc.perform(post("/tx/broadcast").header("Authorization", reader).contentType("text/plain")
                .content("x".repeat(1025))).andExpect(status().isPayloadTooLarge());
    }

    @Test
    void weakConfigurationFailsClosed() {
        NodeApiProperties properties = new NodeApiProperties();
        properties.setAdminToken("weak");
        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void productionTokenRequirementFailsClosedWhenAdminTokenIsMissing() {
        NodeApiProperties properties = new NodeApiProperties();
        properties.setRequireTokens(true);
        assertThrows(IllegalStateException.class, properties::validate);
    }
}
