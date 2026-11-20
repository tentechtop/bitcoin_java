package com.bit.coin.api;

import com.bit.coin.estimate.FeeEstimate;
import com.bit.coin.txpool.TxPool;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/txpool")
public class FeeEstimateApi {

    private final TxPool txPool;

    public FeeEstimateApi(TxPool txPool) {
        this.txPool = txPool;
    }

    /** 按目标确认块数与完整交易字节数估算费率（聪/字节）和总费用（聪）。 */
    @GetMapping("/fee-estimate")
    public FeeEstimate.Estimate estimate(@RequestParam(defaultValue = "3") int targetBlocks,
            @RequestParam long txSize) {
        return txPool.estimateFee(targetBlocks, txSize);
    }

    @GetMapping("/fee-estimates")
    public List<FeeEstimate.Estimate> recommended(@RequestParam long txSize) {
        return txPool.recommendedFeeEstimates(txSize);
    }
}
