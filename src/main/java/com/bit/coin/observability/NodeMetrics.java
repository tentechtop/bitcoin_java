package com.bit.coin.observability;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.config.SystemConfig;
import com.bit.coin.net.NetService;
import com.bit.coin.p2p.conn.QuicConnectionManager;
import com.bit.coin.txpool.TxPool;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
public class NodeMetrics implements MeterBinder {

    private final BlockChainServiceImpl chain;
    private final TxPool pool;
    private final NetService network;
    private final SystemConfig config;
    private final com.bit.coin.database.rocksDb.RocksDb database;

    public NodeMetrics(BlockChainServiceImpl chain, TxPool pool, NetService network, SystemConfig config,
            com.bit.coin.database.rocksDb.RocksDb database) {
        this.chain = chain;
        this.pool = pool;
        this.network = network;
        this.config = config;
        this.database = database;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        FunctionCounter.builder("node.blocks.accepted", chain, BlockChainServiceImpl::acceptedBlocks)
                .register(registry);
        FunctionCounter.builder("node.blocks.rejected", chain, BlockChainServiceImpl::rejectedBlocks)
                .register(registry);
        FunctionCounter.builder("node.chain.reorganizations", chain, BlockChainServiceImpl::reorganizations)
                .register(registry);
        FunctionCounter
                .builder("node.storage.commits", database, com.bit.coin.database.rocksDb.RocksDb::committedBatches)
                .register(registry);
        FunctionCounter.builder("node.storage.failures", database, com.bit.coin.database.rocksDb.RocksDb::failedBatches)
                .register(registry);
        FunctionCounter.builder("node.storage.commit.seconds", database, db -> db.commitNanos() / 1e9)
                .register(registry);
        Gauge.builder("node.storage.writable", database, db -> db.hasWriteSpace() ? 1 : 0).register(registry);
        Gauge.builder("node.mempool.memory.bytes", pool, TxPool::estimatedMemoryBytes).register(registry);
        Gauge.builder("node.p2p.inflight.bytes", chain,
                ignored -> com.bit.coin.p2p.conn.TransportMemoryBudget.reservedBytes()).register(registry);
        Gauge.builder("node.p2p.queue.bytes", chain, ignored -> com.bit.coin.p2p.conn.QuicConstants.queuedBytes())
                .register(registry);
        Gauge.builder("node.p2p.queue.messages", chain, ignored -> com.bit.coin.p2p.conn.QuicConstants.getQueueSize())
                .register(registry);
        Gauge.builder("node.chain.height", chain, c -> {
            var tip = c.getMainTipSnapshot();
            return tip == null ? 0 : tip.height();
        }).register(registry);
        Gauge.builder("node.chain.healthy", chain, c -> c.isStateHealthy() ? 1 : 0).register(registry);
        Gauge.builder("node.sync.lag", network, n -> Math.max(0, n.getBestNetworkHeight() - n.getCurrentLocalHeight()))
                .register(registry);
        Gauge.builder("node.mempool.transactions", pool, TxPool::transactionCount).register(registry);
        Gauge.builder("node.p2p.connections", chain, ignored -> QuicConnectionManager.getConnectionCount())
                .register(registry);
        Gauge.builder("node.storage.free.bytes", config, c -> new File(c.getPath()).getUsableSpace())
                .register(registry);
    }
}
