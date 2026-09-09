package com.bit.coin.ops;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Dependency-free process exit code for container health probes. */
public final class NodeProbe {
    private NodeProbe() {}
    public static void main(String[] args) {
        try {
            URI uri = URI.create(args.length == 0 ? "http://127.0.0.1:18333/actuator/health/readiness" : args[0]);
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build();
            System.exit(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200 ? 0 : 1);
        } catch (Exception unavailable) { System.exit(1); }
    }
}
