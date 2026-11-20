package com.bit.coin.blockchain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/** 三个节点分别运行在独立的 JVM 中，受限的 UDP 网关只允许与公网中继通信。 */
class RelayNodeIT {

    private static final JsonMapper JSON = new JsonMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String token = UUID.randomUUID().toString() + UUID.randomUUID();
    private Path root;

    @Test
    @Timeout(300)
    void twoRestrictedNodesRelayAuthenticatedDataThroughLossAndPublicNodeRestart() throws Exception {
        root = Files.createTempDirectory(Path.of("target"), "relay-nodes-").toAbsolutePath();
        try (Node relay = new Node("relay"); Node a = new Node("a"); Node b = new Node("b")) {
            relay.server = true;
            relay.start();
            relay.ready();
            String relayId = relay.info().path("peerId").asText();
            try (RestrictedUdp aNat = new RestrictedUdp(a.udp, relay.udp);
                    RestrictedUdp bNat = new RestrictedUdp(b.udp, relay.udp)) {
                // 从 A 的实际端口无法直达 B 的映射端点，网关只允许中继访问。
                try (DatagramSocket socket = new DatagramSocket(a.udp, InetAddress.getLoopbackAddress())) {
                    socket.send(new DatagramPacket(new byte[100], 100, InetAddress.getLoopbackAddress(), bNat.port()));
                }
                await(() -> bNat.denied.get() > 0, 3, "private-to-private packet was not filtered");
                a.relayAddress = address(aNat.port(), relayId);
                b.relayAddress = address(bNat.port(), relayId);
                b.start();
                b.ready();
                a.peer = b.info().path("peerId").asText();
                a.start();
                a.ready();
                String aId = a.info().path("peerId").asText(), bId = b.info().path("peerId").asText();
                await(() -> hasCircuit(a, bId) && hasCircuit(b, aId), 45, "relay circuit did not establish");
                assertEquals(401, a.raw("POST", "/p2p/probe?peerId=" + bId, false).statusCode());
                assertEquals(401, a.raw("GET", "/p2p/relay/status", false).statusCode());
                long oldCircuit = circuitId(a, bId);
                // 首次握手的请求和响应均已丢失，重试时必须复用同一组已签名的握手报文。
                assertEquals(2, aNat.handshakeDrops.get());
                assertEquals(2, bNat.handshakeDrops.get());
                JsonNode forward = probe(a, bId, 1_048_576);
                JsonNode backward = probe(b, aId, 262_144);
                Files.writeString(root.resolve("probes.txt"), "A->B=" + forward + "\nB->A=" + backward + "\n");
                a.call("POST", "/chain/mingOneBlockByCount?count=3");
                b.call("POST", "/chain/sync/startHeadersFirst");
                await(() -> sameTip(a, b, 3), 40, "chain sync failed through relay topology");
                JsonNode before = relay.call("GET", "/p2p/relay/status");
                assertTrue(before.path("forwardedBytes").asLong() > 1_048_576);
                assertTrue(aNat.lost.get() > 0 && bNat.lost.get() > 0);
                assertTrue(aNat.duplicated.get() > 0 && bNat.duplicated.get() > 0);
                assertTrue(aNat.delayed.get() > 0 && bNat.delayed.get() > 0);
                assertTrue(aNat.maxEncryptedPacket.get() <= 1429);
                assertTrue(bNat.maxEncryptedPacket.get() <= 1429);

                relay.kill();
                relay.start();
                relay.ready();
                assertEquals(relayId, relay.info().path("peerId").asText());
                // 单跳会话过期后，依赖它的中继线路随之失效。已配置的客户端会自动重连，无需通过 HTTP 触发。
                await(() -> circuitId(a, bId) != oldCircuit && hasCircuit(a, bId) && hasCircuit(b, aId),
                        110, "relay and circuit did not recover after public node restart");
                probe(a, bId, 65_536);
                probe(b, aId, 65_536);
                assertTrue(relay.call("GET", "/p2p/relay/status").path("forwardedPackets").asLong() > 0);
                Files.writeString(root.resolve("result.txt"),
                        "PASS: filtered direct traffic; authenticated one-hop circuits; lost handshake request/response; "
                                + "bidirectional SHA-256 probes; packet loss, duplicate and reordering; chain sync; automatic relay restart recovery.\n"
                                + "A->B probe=" + forward + "\nB->A probe=" + backward + "\nRelay=" + before
                                + "\nA gateway=" + aNat.stats() + "\nB gateway=" + bNat.stats() + "\n");
            }
        }
    }

    private JsonNode probe(Node node, String peer, int bytes) throws Exception {
        JsonNode result = node.call("POST", "/p2p/probe?peerId=" + peer + "&bytes=" + bytes);
        assertTrue(result.path("success").asBoolean(), "Probe failed: " + result + "; logs=" + root);
        return result;
    }

    private static String address(int port, String id) {
        return "/ip4/127.0.0.1/quic/" + port + "/p2p/" + id;
    }

    private long circuitId(Node node, String peer) throws Exception {
        String hex = com.bit.coin.p2p.production.P2PAddressValidator.normalizePeerId(peer);
        for (JsonNode c : node.call("GET", "/p2p/relay/status").path("circuits")) {
            if (hex.equals(c.path("peerId").asText()) && c.path("available").asBoolean()) {
                return c.path("connectionId").asLong();
            }
        }
        return 0;
    }

    private boolean hasCircuit(Node node, String peer) throws Exception {
        return circuitId(node, peer) != 0;
    }

    private boolean sameTip(Node a, Node b, int height) throws Exception {
        JsonNode ai = a.info(), bi = b.info();
        return ai.path("height").asInt() == height && bi.path("height").asInt() == height
                && ai.path("tip").asText().equals(bi.path("tip").asText());
    }

    @FunctionalInterface
    interface Check {

        boolean test() throws Exception;
    }

    private void await(Check check, int seconds, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (check.test()) {
                return;
            }
            Thread.sleep(250);
        }
        fail(message + "; logs=" + root);
    }

    private final class Node implements AutoCloseable {

        final String name;
        final int http, udp;
        boolean server;
        String relayAddress, peer;
        Process process;
        int launches;

        Node(String name) throws Exception {
            this.name = name;
            try (ServerSocket tcp = new ServerSocket(0); DatagramSocket socket = new DatagramSocket(0)) {
                http = tcp.getLocalPort();
                udp = socket.getLocalPort();
            }
        }

        void start() throws Exception {
            List<String> args = new ArrayList<>(
                    List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                            "-Xms64m", "-Xmx384m", "-XX:ActiveProcessorCount=2", "-jar",
                            Path.of("target/bitcoin_java-0.0.1-SNAPSHOT.jar").toAbsolutePath().toString(),
                            "--spring.profiles.active=local", "--server.address=127.0.0.1", "--server.port=" + http,
                            "--system.path=" + root.resolve(name), "--system.quic-port=" + udp,
                            "--system.p2p-bind-address=127.0.0.1",
                            "--system.networking-enabled=true", "--system.is-stun=false", "--system.mining-type=1",
                            "--system.bootstrap=",
                            "--system.relay.server-enabled=" + server, "--node.api.public-read=false",
                            "--node.api.requests-per-second=100",
                            "--logging.file.path=" + root.resolve(name + "-logs"),
                            "--system.miner-address-list=11111111111111111111111111111111"));
            if (relayAddress != null) {
                args.add("--system.relay.servers=" + relayAddress);
            }
            if (peer != null) {
                args.add("--system.relay.peers=" + peer);
            }
            ProcessBuilder builder = new ProcessBuilder(args);
            builder.environment().put("NODE_ADMIN_TOKEN", token);
            builder.environment().remove("NODE_READ_TOKEN");
            builder.redirectErrorStream(true).redirectOutput(root.resolve(name + "-" + (++launches) + ".log").toFile());
            process = builder.start();
        }

        void ready() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(50);
            while (System.nanoTime() < deadline && process.isAlive()) {
                try {
                    if (info().path("ready").asBoolean()) {
                        return;
                    }
                } catch (Exception starting) {
                }
                Thread.sleep(200);
            }
            fail(name + " startup failed; logs=" + root);
        }

        JsonNode info() throws Exception {
            return call("GET", "/node/info");
        }

        JsonNode call(String method, String path) throws Exception {
            var result = raw(method, path, true);
            assertEquals(200, result.statusCode(), result.body() + "; logs=" + root);
            return JSON.readTree(result.body());
        }

        HttpResponse<String> raw(String method, String path, boolean auth) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http + path))
                    .timeout(Duration.ofSeconds(30))
                    .method(method, HttpRequest.BodyPublishers.noBody());
            if (auth) {
                request.header("Authorization", "Bearer " + token);
            }
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        void kill() throws Exception {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        }

        public void close() throws Exception {
            if (process != null && process.isAlive()) {
                process.destroy();
                if (!process.waitFor(8, TimeUnit.SECONDS)) {
                    kill();
                }
            }
        }
    }

    private static final class RestrictedUdp implements AutoCloseable {

        final DatagramSocket socket;
        final int inside, relay;
        final Thread reader;
        final ScheduledExecutorService delays = Executors.newSingleThreadScheduledExecutor();
        final AtomicLong denied = new AtomicLong(), lost = new AtomicLong(), duplicated = new AtomicLong(),
                delayed = new AtomicLong();
        final AtomicInteger handshakeDrops = new AtomicInteger(), maxEncryptedPacket = new AtomicInteger();
        final Set<Integer> droppedHandshakeDirections = new HashSet<>();
        int packets;

        RestrictedUdp(int inside, int relay) throws Exception {
            this.inside = inside;
            this.relay = relay;
            socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            socket.setReceiveBufferSize(4 * 1024 * 1024);
            reader = new Thread(this::read, "restricted-udp-" + inside);
            reader.setDaemon(true);
            reader.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        void read() {
            while (!socket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(new byte[2048], 2048);
                    socket.receive(packet);
                    int source = packet.getPort();
                    if (!packet.getAddress().isLoopbackAddress() || (source != inside && source != relay)) {
                        denied.incrementAndGet();
                        continue;
                    }
                    byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                    if (data.length > 4 && data[4] == 0 && droppedHandshakeDirections.add(source)) {
                        handshakeDrops.incrementAndGet();
                        continue;
                    }
                    if (data.length > 4 && data[4] == 1) {
                        maxEncryptedPacket.accumulateAndGet(data.length, Math::max);
                    }
                    int target = source == inside ? relay : inside;
                    int number = ++packets;
                    if (number % 29 == 0) {
                        lost.incrementAndGet();
                        continue;
                    }
                    long delay = number % 13 == 0 ? 35 : 2;
                    if (delay == 35) {
                        delayed.incrementAndGet();
                    }
                    delays.schedule(() -> send(data, target), delay, TimeUnit.MILLISECONDS);
                    if (number % 19 == 0) {
                        duplicated.incrementAndGet();
                        delays.schedule(() -> send(data, target), delay + 4, TimeUnit.MILLISECONDS);
                    }
                } catch (Exception failure) {
                    if (!socket.isClosed()) {
                        throw new RuntimeException(failure);
                    }
                }
            }
        }

        void send(byte[] data, int port) {
            try {
                socket.send(new DatagramPacket(data, data.length, InetAddress.getLoopbackAddress(), port));
            } catch (Exception failure) {
                if (!socket.isClosed()) {
                    throw new RuntimeException(failure);
                }
            }
        }

        String stats() {
            return "denied=" + denied + ", lost=" + lost + ", duplicate=" + duplicated + ", delayed=" + delayed
                    + ", maxUdpBytes=" + maxEncryptedPacket;
        }

        public void close() throws Exception {
            socket.close();
            delays.shutdownNow();
            reader.join(2000);
        }
    }
}
