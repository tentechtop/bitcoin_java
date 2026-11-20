package com.bit.coin.api;

import com.bit.coin.txpool.TxPoolImpl;
import com.bit.coin.web.ApiExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FeeEstimateApiTest {

    private final TxPoolImpl pool = new TxPoolImpl();
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new FeeEstimateApi(pool))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @AfterEach
    void close() {
        pool.close();
    }

    @Test
    void returnsAQuoteForTheSuppliedTransactionSizeAndDefaultTarget() throws Exception {
        mvc.perform(get("/txpool/fee-estimate").param("txSize", "250"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetBlocks").value(3))
                .andExpect(jsonPath("$.txSize").value(250))
                .andExpect(jsonPath("$.feeRate").value(1))
                .andExpect(jsonPath("$.fee").value(250))
                .andExpect(jsonPath("$.source").value("minimum-relay"))
                .andExpect(jsonPath("$.feeRateUnit").value("sat/byte"))
                .andExpect(jsonPath("$.feeUnit").value("sat"));
        mvc.perform(get("/txpool/fee-estimate").param("txSize", "1000").param("targetBlocks", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetBlocks").value(1))
                .andExpect(jsonPath("$.fee").value(1000));
    }

    @Test
    void returnsThreeRecommendations() throws Exception {
        mvc.perform(get("/txpool/fee-estimates").param("txSize", "250"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].targetBlocks").value(1))
                .andExpect(jsonPath("$[1].targetBlocks").value(3))
                .andExpect(jsonPath("$[2].targetBlocks").value(6));
    }

    @Test
    void invalidAndMissingParametersReturnBadRequest() throws Exception {
        for (String path : new String[] { "/txpool/fee-estimate", "/txpool/fee-estimates" }) {
            mvc.perform(get(path)).andExpect(status().isBadRequest());
            for (String size : new String[] { "0", "-1", "1000001", "9223372036854775807", "abc" }) {
                mvc.perform(get(path).param("txSize", size)).andExpect(status().isBadRequest());
            }
        }
        for (String target : new String[] { "0", "145", "abc" }) {
            mvc.perform(get("/txpool/fee-estimate").param("txSize", "250").param("targetBlocks", target))
                    .andExpect(status().isBadRequest());
        }
    }
}
