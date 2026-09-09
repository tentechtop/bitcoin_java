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
    public NodeInfoApi(SystemConfig config, BlockChainServiceImpl chain) { this.config = config; this.chain = chain; }
    @GetMapping("/node/info")
    public Map<String, Object> info() {
        return Map.of("network", config.getParams().getName(), "consensusRevision", ConsensusRules.REVISION,
                "peerId", org.bitcoinj.base.Base58.encode(SystemConfig.SelfPeer.getId()), "p2pPort", SystemConfig.SelfPeer.getPort(),
                "height", chain.getMainLatestBlock().getHeight(), "tip", SerializeUtils.bytesToHex(chain.getMainLatestBlock().getHash()),
                "ready", chain.isStateHealthy());
    }
}
