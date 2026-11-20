package com.bit.coin.observability;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.config.SystemConfig;
import com.bit.coin.database.DataBase;
import com.bit.coin.p2p.conn.QuicConnectionManager;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("nodeHealthIndicator")
public class NodeHealthIndicator implements HealthIndicator {

    private final BlockChainServiceImpl chain;
    private final DataBase database;
    private final SystemConfig config;
    private final com.bit.coin.net.NetService network;

    public NodeHealthIndicator(BlockChainServiceImpl chain, DataBase database, SystemConfig config,
            com.bit.coin.net.NetService network) {
        this.chain = chain;
        this.database = database;
        this.config = config;
        this.network = network;
    }

    /** 检查链状态和数据库；启用网络时还检查传输通道、连接数与同步高度差。 */
    @Override
    public Health health() {
        try {
            boolean transport = !config.isNetworkingEnabled() || QuicConnectionManager.Global_Channel != null
                    && QuicConnectionManager.Global_Channel.isActive();
            boolean synchronizedEnough = !config.isNetworkingEnabled() ||
                    QuicConnectionManager.getConnectionCount() >= config.getReadinessMinPeers()
                            && (long) network.getBestNetworkHeight() - network.getCurrentLocalHeight() <= config
                                    .getReadinessMaxSyncLag();
            return chain.isStateHealthy() && database.checkHealth() && transport && synchronizedEnough
                    ? Health.up().build()
                    : Health.down().build();
        } catch (Exception failure) {
            return Health.down().build();
        }
    }
}
