package com.bit.coin.web;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Getter
@Setter
@ConfigurationProperties("node.api")
public class NodeApiProperties {

    private String adminToken = "";
    private String readToken = "";
    /** Production profiles must provide an admin token; local/test may stay locked down without one. */
    private boolean requireTokens;
    private boolean publicRead;
    private List<String> allowedOrigins = List.of();
    private int requestsPerSecond = 20;  // 每个来源 IP 每秒可发起的请求数
    private int globalRequestsPerSecond = 500;  // 所有来源合计的每秒请求数上限
    private int maxRequestBytes = 2_100_000;  // 单个请求体的字节上限，读取请求流时也会检查

    public void validate() {
        if (requireTokens && adminToken.isEmpty()) {
            throw new IllegalStateException("API admin token must be configured when require-tokens is enabled");
        }
        if ((!adminToken.isEmpty() && (adminToken.length() < 32 || adminToken.length() > 4096))
                || (!readToken.isEmpty() && (readToken.length() < 32 || readToken.length() > 4096))) {
            throw new IllegalStateException("API tokens must contain at least 32 characters");
        }
        if (!adminToken.isEmpty() && adminToken.equals(readToken)) {
            throw new IllegalStateException("Read and admin tokens must differ");
        }
        if (requestsPerSecond < 1 || requestsPerSecond > 1000 || globalRequestsPerSecond < requestsPerSecond
                || globalRequestsPerSecond > 10_000 || maxRequestBytes < 1024 || maxRequestBytes > 8_000_000) {
            throw new IllegalStateException("Invalid API resource limits");
        }
        if (allowedOrigins.stream().anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalStateException("CORS requires explicit origins");
        }
    }
}
