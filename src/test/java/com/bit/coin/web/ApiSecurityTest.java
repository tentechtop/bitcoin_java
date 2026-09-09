package com.bit.coin.web;

import com.bit.coin.api.FeeEstimateApi;
import com.bit.coin.config.CorsConfig;
import com.bit.coin.txpool.TxPool;
import com.bit.coin.txpool.TxPoolImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(ApiSecurityTest.Config.class)
@WebAppConfiguration
@TestPropertySource(properties = {
        "node.api.admin-token=admin-test-token-with-32-characters-minimum",
        "node.api.read-token=reader-test-token-with-32-characters-minimum",
        "node.api.requests-per-second=100", "node.api.max-request-bytes=1024"})
class ApiSecurityTest {
    @Configuration @EnableWebMvc
    @Import({ApiSecurityConfig.class, CorsConfig.class, ApiExceptionHandler.class, Endpoints.class, FeeEstimateApi.class})
    static class Config {
        @Bean TxPool feeEstimationPool() {
            TxPool pool = mock(TxPool.class, CALLS_REAL_METHODS);
            TxPoolImpl empty = new TxPoolImpl();
            try { when(pool.getStatusSnapshot()).thenReturn(empty.getStatusSnapshot()); }
            finally { empty.close(); }
            return pool;
        }
    }
    @RestController static class Endpoints {
        @GetMapping("/chain/status") String read() { return "ready"; }
        @PostMapping("/p2p/disconnect") String write() { return "disconnected"; }
        @PostMapping("/tx/broadcast") String body(@RequestBody String body) { return body; }
        @GetMapping("/actuator/health") String health() { return "UP"; }
    }
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    private final String admin = "Bearer admin-test-token-with-32-characters-minimum";
    private final String reader = "Bearer reader-test-token-with-32-characters-minimum";
    @BeforeEach void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test void anonymousRequestsCannotReadOrMutateState() throws Exception {
        mvc.perform(get("/chain/status")).andExpect(status().isUnauthorized());
        mvc.perform(post("/p2p/disconnect")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
    @Test void readCredentialsCannotAdministerTheNode() throws Exception {
        mvc.perform(get("/chain/status").header("Authorization", reader)).andExpect(status().isOk());
        mvc.perform(post("/p2p/disconnect").header("Authorization", reader)).andExpect(status().isForbidden());
        mvc.perform(post("/p2p/disconnect").header("Authorization", admin)).andExpect(status().isOk());
    }
    @Test void feeEstimatesUseTheExistingReadPermission() throws Exception {
        for (String path : new String[]{"/txpool/fee-estimate", "/txpool/fee-estimates"}) {
            mvc.perform(get(path).param("txSize", "250")).andExpect(status().isUnauthorized());
            mvc.perform(get(path).param("txSize", "250").header("Authorization", reader)).andExpect(status().isOk());
        }
    }
    @Test void credentialsAreNeverAcceptedFromQueryParametersOrCookies() throws Exception {
        mvc.perform(get("/chain/status").param("token", admin.substring(7))).andExpect(status().isUnauthorized());
        mvc.perform(get("/chain/status").cookie(new jakarta.servlet.http.Cookie("token", admin.substring(7)))).andExpect(status().isUnauthorized());
        mvc.perform(get("/chain/status").header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
    }
    @Test void administrationIsNotAvailableThroughGet() throws Exception {
        mvc.perform(get("/p2p/disconnect").header("Authorization", admin)).andExpect(status().isMethodNotAllowed());
    }
    @Test void arbitraryOriginsAndOversizedBodiesAreRejected() throws Exception {
        mvc.perform(options("/p2p/disconnect").header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden());
        mvc.perform(post("/tx/broadcast").header("Authorization", reader).contentType("text/plain")
                .content("x".repeat(1025))).andExpect(status().isPayloadTooLarge());
    }
    @Test void weakConfigurationFailsClosed() {
        NodeApiProperties properties = new NodeApiProperties();
        properties.setAdminToken("weak");
        assertThrows(IllegalStateException.class, properties::validate);
    }
}
