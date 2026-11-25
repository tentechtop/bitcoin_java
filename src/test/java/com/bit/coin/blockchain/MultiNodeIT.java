package com.bit.coin.blockchain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** 启动真实节点进程，使用实际的 UDP 通信和 HTTP 认证，每个节点使用独立的 RocksDB 数据库。 */
class MultiNodeIT {

    private static final JsonMapper JSON = new JsonMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String token = UUID.randomUUID().toString() + UUID.randomUUID();
    private Path root;

    @Test
    @Timeout(240)
    void syncsCompetingChainsAndRecoversAfterProcessKill() throws Exception {
        try (var jar = new java.util.zip.ZipFile("target/bitcoin_java-0.0.1-SNAPSHOT.jar");
                var files = Files.walk(Path.of("target/classes"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String relative = Path.of("target/classes").relativize(file).toString().replace('\\', '/');
                String entry = relative.startsWith("META-INF/") ? relative : "BOOT-INF/classes/" + relative;
                assertNotNull(jar.getEntry(entry), "Packaged resource missing: " + entry);
                try (var stream = jar.getInputStream(jar.getEntry(entry))) {
                    assertArrayEquals(Files.readAllBytes(file), stream.readAllBytes(),
                            "Stale packaged resource: " + entry);
                }
            }
        }
        root = Files.createTempDirectory(Path.of("target"), "multi-node-").toAbsolutePath();
        try (Node a = new Node("a"); Node b = new Node("b")) {
            a.start();
            b.start();
            a.awaitReady();
            b.awaitReady();
            assertEquals(401, a.raw("GET", "/node/info", false).statusCode());
            assertEquals(401, a.raw("POST", "/chain/mingOneBlock", false).statusCode());
            assertEquals(404, a.raw("GET", "/mock/sendMock", true).statusCode());
            assertEquals(200, a.raw("GET", "/actuator/prometheus", true).statusCode());
            assertEquals(413, a.chunkedOversizedTransaction().statusCode());
            a.call("POST", "/chain/mingOneBlockByCount?count=4");
            b.call("POST", "/chain/mingOneBlockByCount?count=2");
            assertNotEquals(a.info().path("tip").asText(), b.info().path("tip").asText());
            b.connect(a);
            b.call("POST", "/chain/sync/startHeadersFirst");
            awaitSameTip(a, b, 4);
            String identity = b.info().path("peerId").asText();
            b.kill();
            a.call("POST", "/chain/mingOneBlockByCount?count=3");
            b.start();
            b.awaitReady();
            assertEquals(identity, b.info().path("peerId").asText());
            assertEquals(4, b.info().path("height").asInt());
            b.connect(a);
            b.call("POST", "/chain/sync/startHeadersFirst");
            awaitSameTip(a, b, 7);
            assertEquals("UP", b.call("GET", "/actuator/health/readiness").path("status").asText());
            Files.writeString(root.resolve("result.txt"),
                    "PASS: authenticated APIs, competing chains, UDP header/body sync, forced process termination, identity and tip recovery, catch-up to height 7\n");
        }
    }

    @Test
    @Timeout(240)
    void concurrentMinersRaceAndHigherWorkBranchWinsAcrossThreeNodes() throws Exception {
        root = Files.createTempDirectory(Path.of("target"), "mining-competition-").toAbsolutePath();
        try (Node a = new Node("a"); Node b = new Node("b"); Node observer = new Node("observer")) {
            a.start();
            b.start();
            observer.start();
            a.awaitReady();
            b.awaitReady();
            observer.awaitReady();

            ExecutorService miners = Executors.newFixedThreadPool(2);
            JsonNode aFirst;
            JsonNode bFirst;
            try {
                Future<JsonNode> aResult = miners.submit(() -> a.call("POST", "/chain/mingOneBlock"));
                Future<JsonNode> bResult = miners.submit(() -> b.call("POST", "/chain/mingOneBlock"));
                aFirst = aResult.get(60, TimeUnit.SECONDS);
                bFirst = bResult.get(60, TimeUnit.SECONDS);
            } finally {
                miners.shutdownNow();
            }

            assertEquals(1, aFirst.path("height").asInt());
            assertEquals(1, bFirst.path("height").asInt());
            assertEquals(aFirst.path("hash").asText(), a.info().path("tip").asText());
            assertEquals(bFirst.path("hash").asText(), b.info().path("tip").asText());
            assertNotEquals(aFirst.path("hash").asText(), bFirst.path("hash").asText(),
                    "Different miners must produce distinct competing blocks");
            JsonNode aFirstStored = a.call("GET", "/chain/getBlockByHash?hash=" + aFirst.path("hash").asText());
            JsonNode bFirstStored = b.call("GET", "/chain/getBlockByHash?hash=" + bFirst.path("hash").asText());
            assertEquals(aFirstStored.path("chainWorkDecimal").asText(),
                    bFirstStored.path("chainWorkDecimal").asText());

            JsonNode aSecond = a.call("POST", "/chain/mingOneBlock");
            assertEquals(2, aSecond.path("height").asInt());
            JsonNode aSecondStored = a.call("GET", "/chain/getBlockByHash?hash=" + aSecond.path("hash").asText());
            assertTrue(new BigInteger(aSecondStored.path("chainWorkDecimal").asText())
                    .compareTo(new BigInteger(bFirstStored.path("chainWorkDecimal").asText())) > 0,
                    "The extension of A's branch must have more cumulative work: a="
                            + aSecondStored.path("chainWorkDecimal") + ", b=" + bFirstStored.path("chainWorkDecimal"));

            b.connect(a);
            observer.connect(a);
            b.call("POST", "/chain/sync/startHeadersFirst");
            observer.call("POST", "/chain/sync/startHeadersFirst");
            awaitSameTip(a, b, 2);
            awaitSameTip(a, observer, 2);

            String winningTip = aSecond.path("hash").asText();
            assertEquals(winningTip, b.info().path("tip").asText());
            assertEquals(winningTip, observer.info().path("tip").asText());
            HttpResponse<String> losingBlock = b.raw("GET",
                    "/chain/getBlockByHash?hash=" + bFirst.path("hash").asText(), true);
            assertEquals(200, losingBlock.statusCode(), losingBlock.body());
            assertEquals(bFirst.path("hash").asText(), JSON.readTree(losingBlock.body()).path("hash").asText());
            Files.writeString(root.resolve("competition-result.txt"),
                    "PASS: two isolated miners created distinct height-1 blocks; "
                            + "the higher-work height-2 branch won after three-node synchronization.\n"
                            + "A first=" + aFirst + "\nB first=" + bFirst + "\nA winner=" + aSecond + "\n");
        }
    }

    private void awaitSameTip(Node a, Node b, int height) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(50);
        while (System.nanoTime() < deadline) {
            JsonNode left = a.info(), right = b.info();
            if (left.path("height").asInt() == height && right.path("height").asInt() == height
                    && left.path("tip").asText().equals(right.path("tip").asText())) {
                return;
            }
            Thread.sleep(250);
        }
        fail("Nodes did not converge. Logs: " + root + "; a=" + a.info() + "; b=" + b.info()
                + "; sync=" + b.call("GET", "/chain/sync/status"));
    }

    private final class Node implements AutoCloseable {

        private final String name;
        private final String minerAddress;
        private final int httpPort, udpPort;
        private Process process;
        private int launch;

        Node(String name) throws Exception {
            this.name = name;
            byte[] rewardKey = new byte[32];
            rewardKey[31] = (byte) name.charAt(0);
            minerAddress = org.bitcoinj.base.Base58.encode(rewardKey);
            try (java.net.ServerSocket tcp = new java.net.ServerSocket(0); DatagramSocket udp = new DatagramSocket(0)) {
                httpPort = tcp.getLocalPort();
                udpPort = udp.getLocalPort();
            }
        }

        void start() throws Exception {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            ProcessBuilder builder = new ProcessBuilder(java, "-Xms64m", "-Xmx384m", "-XX:ActiveProcessorCount=2",
                    "-jar", Path.of("target/bitcoin_java-0.0.1-SNAPSHOT.jar").toAbsolutePath().toString(),
                    "--spring.profiles.active=local", "--server.port=" + httpPort, "--server.address=127.0.0.1",
                    "--system.path=" + root.resolve(name), "--system.quic-port=" + udpPort,
                    "--system.p2p-bind-address=127.0.0.1", "--system.networking-enabled=true", "--system.is-stun=false",
                    "--system.mining-type=1", "--system.bootstrap=", "--node.api.public-read=false",
                    "--logging.level.com.bit.coin.web=DEBUG", "--node.api.requests-per-second=100",
                    "--logging.file.path=" + root.resolve(name + "-logs"),
                    "--system.miner-address-list=" + minerAddress);
            builder.environment().put("NODE_ADMIN_TOKEN", token);
            builder.environment().remove("NODE_READ_TOKEN");
            builder.redirectErrorStream(true).redirectOutput(root.resolve(name + "-" + (++launch) + ".log").toFile());
            process = builder.start();
        }

        void awaitReady() throws Exception {
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
            fail("Node " + name + " failed to start; logs: " + root);
        }

        JsonNode info() throws Exception {
            return call("GET", "/node/info");
        }

        void connect(Node other) throws Exception {
            String addr = "/ip4/127.0.0.1/quic/" + other.udpPort + "/p2p/" + other.info().path("peerId").asText();
            JsonNode response = call("POST",
                    "/p2p/connect?addr=" + URLEncoder.encode(addr, java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(response.path("success").asBoolean(), "Connect failed: " + response + "; logs=" + root);
        }

        JsonNode call(String method, String path) throws Exception {
            HttpResponse<String> response = raw(method, path, true);
            assertEquals(200, response.statusCode(), method + " " + path + " => " + response.body() + "; logs=" + root);
            return JSON.readTree(response.body());
        }

        HttpResponse<String> raw(String method, String path, boolean auth) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + path))
                    .timeout(Duration.ofSeconds(40)).method(method, HttpRequest.BodyPublishers.noBody());
            if (auth) {
                request.header("Authorization", "Bearer " + token);
            }
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> chunkedOversizedTransaction() throws Exception {
            byte[] body = ("{\"rawTransaction\":\"" + "0".repeat(2_100_001) + "\"}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + "/tx/broadcast"))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(body)))
                    .build();
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        }

        void kill() throws Exception {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            awaitDatabaseUnlock();
        }

        private void awaitDatabaseUnlock() throws Exception {
            Path lock = root.resolve(name).resolve("LOCK");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (Files.exists(lock) && System.nanoTime() < deadline) {
                try {
                    Files.deleteIfExists(lock);
                } catch (java.io.IOException stillOpen) {
                    Thread.sleep(100);
                }
            }
            assertFalse(Files.exists(lock), "RocksDB lock was not released: " + lock);
        }

        @Override
        public void close() throws Exception {
            if (process != null && process.isAlive()) {
                process.destroy();
                if (!process.waitFor(8, TimeUnit.SECONDS)) {
                    kill();
                }
            }
        }
    }
}
