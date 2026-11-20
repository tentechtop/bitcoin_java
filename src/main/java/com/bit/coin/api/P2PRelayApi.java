package com.bit.coin.api;

import com.bit.coin.p2p.conn.*;
import com.bit.coin.p2p.production.P2PAddressValidator;
import com.bit.coin.p2p.protocol.*;
import org.springframework.web.bind.annotation.*;

import java.security.*;
import java.util.*;
import java.util.concurrent.Semaphore;

@RestController
@RequestMapping("/p2p")
public class P2PRelayApi {

    private final Semaphore probes = new Semaphore(2);

    @GetMapping("/relay/status")
    public Map<String, Object> status() {
        return RelayTransport.status();
    }

    /** 仅管理员可主动探测端到端数据重组和完整性，每次探测的数据量上限为 1 MiB。 */
    @PostMapping("/probe")
    public Map<String, Object> probe(@RequestParam String peerId,
            @RequestParam(defaultValue = "65536") int bytes) throws Exception {
        if (bytes < 1 || bytes > 1_048_576) {
            throw new IllegalArgumentException("bytes must be 1..1048576");
        }
        String peer = P2PAddressValidator.normalizePeerId(peerId);
        if (!probes.tryAcquire()) {
            throw new IllegalStateException("Two probes are already running");
        }
        try {
            byte[] payload = new byte[bytes];
            new SecureRandom().nextBytes(payload);
            long start = System.nanoTime();
            P2PMessage response = QuicConnectionManager.sendRequest(peer, ProtocolEnum.P2P_Probe, payload, 20000);
            boolean success = response != null
                    && MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(payload),
                            response.getData());
            return Map.of("success", success, "peerId", peer, "bytes", bytes, "elapsedMillis",
                    (System.nanoTime() - start) / 1_000_000);
        } finally {
            probes.release();
        }
    }
}
