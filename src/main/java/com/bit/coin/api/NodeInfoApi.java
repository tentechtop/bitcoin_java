package com.bit.coin.api;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.blockchain.ConsensusRules;
import com.bit.coin.config.SystemConfig;
import com.bit.coin.utils.SerializeUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class NodeInfoApi {

    private final SystemConfig config;
    private final BlockChainServiceImpl chain;

    public NodeInfoApi(SystemConfig config, BlockChainServiceImpl chain) {
        this.config = config;
        this.chain = chain;
    }

    @GetMapping("/node/info")
    public Map<String, Object> info() {
        var tip = chain.getMainTipSnapshot();
        var params = config.getParams();
        return Map.ofEntries(Map.entry("network", params.getName()),
                Map.entry("consensusRevision", ConsensusRules.REVISION),
                Map.entry("peerId", org.bitcoinj.base.Base58.encode(SystemConfig.SelfPeer.getId())),
                Map.entry("p2pPort", SystemConfig.SelfPeer.getPort()), Map.entry("rpcPort", params.getRpcPort()),
                Map.entry("networkMagic", SerializeUtils.bytesToHex(params.getNetworkMagic())),
                Map.entry("coinbaseMaturity", params.getCoinbaseMaturity()),
                Map.entry("subsidyHalvingInterval", params.getSubsidyReductionInterval()),
                Map.entry("targetSpacingSeconds", params.getTargetTimePerBlock().toSeconds()),
                Map.entry("difficultyAdjustmentInterval", params.getDifficultyAdjustmentInterval()),
                Map.entry("powLimitBits", Long.toHexString(params.getPowLimitBits())),
                Map.entry("height", tip == null ? 0 : tip.height()),
                Map.entry("tip", tip == null ? "" : SerializeUtils.bytesToHex(tip.hash())),
                Map.entry("ready", chain.isStateHealthy()));
    }
}
