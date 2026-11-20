package com.bit.coin.p2p.conn;

import com.bit.coin.config.SystemConfig;
import com.bit.coin.p2p.production.P2PAddressValidator;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/** 按配置建立的出站中继会话维持 NAT 映射，并在中继重启后自动恢复。 */
@Data
@Slf4j
@Component
@ConfigurationProperties(prefix = "system.relay")
public class RelayService {

    private boolean serverEnabled;
    private List<String> servers = new ArrayList<>();
    private List<String> peers = new ArrayList<>();
    private long bytesPerSecond = 2 * 1024 * 1024;
    private final SystemConfig systemConfig;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "relay-reconnect");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    void configure() {
        if (servers.size() > 8 || peers.size() > 32) {
            throw new IllegalArgumentException("At most 8 relay servers and 32 relay peers");
        }
        Set<String> trusted = new HashSet<>();
        for (String server : servers) {
            var address = P2PAddressValidator.parseQuicAddress(server);
            if (address.isRelayed()) {
                throw new IllegalArgumentException("Relay servers must be direct addresses");
            }
            trusted.add(address.peerIdHex());
        }
        peers = peers.stream().map(P2PAddressValidator::normalizePeerId).distinct().toList();
        if (!peers.isEmpty() && servers.isEmpty()) {
            throw new IllegalArgumentException("relay peers require relay servers");
        }
        RelayTransport.configure(serverEnabled, trusted, bytesPerSecond);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (systemConfig.isNetworkingEnabled() && !servers.isEmpty()) {
            executor.scheduleWithFixedDelay(this::reconnect, 0, 5, TimeUnit.SECONDS);
        }
    }

    private void reconnect() {
        for (String server : servers) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                QuicConnection hop = QuicConnectionManager.connectRemoteByAddr(server);
                if (hop == null || hop.getRelayPath() != null) {
                    continue;
                }
                for (String peer : peers) {
                    if (!peer.equals(hop.getPeerId()) && QuicConnectionManager.getConnectionByPeerId(peer) == null) {
                        QuicConnectionManager.connectRemoteByAddr(server + "/p2p-circuit/p2p/" + peer);
                    }
                }
            } catch (RuntimeException failure) {
                log.debug("Relay reconnect failed: {}", failure.toString());
            }
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
        RelayTransport.configure(false, Set.of(), bytesPerSecond);
    }
}
