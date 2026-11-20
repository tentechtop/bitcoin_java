package com.bit.coin.ops;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** 通过进程退出码向容器报告健康检查结果，无需外部依赖。 */
public final class NodeProbe {

    private NodeProbe() {}

    public static void main(String[] args) {
        try {
            String port = System.getenv().getOrDefault("NODE_HTTP_PORT", "18333");
            URI uri = URI.create(args.length == 0
                    ? "http://127.0.0.1:" + port + "/actuator/health/readiness"
                    : args[0]);
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build();
            System.exit(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200 ? 0 : 1);
        } catch (Exception unavailable) {
            System.exit(1);
        }
    }
}
