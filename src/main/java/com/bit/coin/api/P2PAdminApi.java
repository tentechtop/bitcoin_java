package com.bit.coin.api;

import com.bit.coin.p2p.production.FlowControlSnapshot;
import com.bit.coin.p2p.production.P2PBlacklistEntry;
import com.bit.coin.p2p.production.P2PConnectResult;
import com.bit.coin.p2p.production.P2PDisconnectResult;
import com.bit.coin.p2p.production.P2PNodeSnapshot;
import com.bit.coin.p2p.production.P2PTransferErrorRecord;
import com.bit.coin.p2p.production.ProductionP2PService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/p2p")
public class P2PAdminApi {
    private final ProductionP2PService p2pService;

    public P2PAdminApi(ProductionP2PService p2pService) {
        this.p2pService = p2pService;
    }

    @GetMapping("/connect")
    public P2PConnectResult connect(@RequestParam String addr) {
        return p2pService.connect(addr);
    }

    @GetMapping("/disconnect")
    public P2PDisconnectResult disconnect(@RequestParam String peerId) {
        return p2pService.disconnect(peerId);
    }

    @GetMapping("/nodes")
    public List<P2PNodeSnapshot> nodes() {
        return p2pService.nodeSnapshots();
    }

    @GetMapping("/node")
    public P2PNodeSnapshot node(@RequestParam String peerId) {
        return p2pService.nodeSnapshot(peerId).orElse(null);
    }

    @GetMapping("/blacklist/list")
    public List<P2PBlacklistEntry> blacklist() {
        return p2pService.blacklistEntries();
    }

    @GetMapping("/blacklist/peer/add")
    public P2PBlacklistEntry blacklistPeer(@RequestParam String peerId,
                                           @RequestParam(required = false) String reason,
                                           @RequestParam(required = false) Long ttlSeconds) {
        return p2pService.blacklistPeer(peerId, reason, ttl(ttlSeconds));
    }

    @GetMapping("/blacklist/ip/add")
    public P2PBlacklistEntry blacklistIp(@RequestParam String ip,
                                         @RequestParam(required = false) String reason,
                                         @RequestParam(required = false) Long ttlSeconds) {
        return p2pService.blacklistIp(ip, reason, ttl(ttlSeconds));
    }

    @GetMapping("/blacklist/peer/remove")
    public boolean unblockPeer(@RequestParam String peerId) {
        return p2pService.unblockPeer(peerId);
    }

    @GetMapping("/blacklist/ip/remove")
    public boolean unblockIp(@RequestParam String ip) {
        return p2pService.unblockIp(ip);
    }

    @GetMapping("/errors")
    public List<P2PTransferErrorRecord> errors(@RequestParam(required = false) String peerId) {
        return p2pService.transferErrors(peerId);
    }

    @GetMapping("/flow")
    public FlowControlSnapshot flow(@RequestParam String peerId) {
        return p2pService.flowControl(peerId);
    }

    @GetMapping("/flow/reserve")
    public boolean reserveOutbound(@RequestParam String peerId, @RequestParam long bytes) {
        return p2pService.reserveOutboundBytes(peerId, bytes);
    }

    private Duration ttl(Long ttlSeconds) {
        if (ttlSeconds == null || ttlSeconds <= 0) {
            return Duration.ZERO;
        }
        return Duration.ofSeconds(ttlSeconds);
    }
}
