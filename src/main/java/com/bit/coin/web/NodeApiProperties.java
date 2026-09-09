package com.bit.coin.web;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

@Getter @Setter
@ConfigurationProperties("node.api")
public class NodeApiProperties {
    private String adminToken = "";
    private String readToken = "";
    private boolean publicRead;
    private List<String> allowedOrigins = List.of();
    private int requestsPerSecond = 20;
    private int globalRequestsPerSecond = 500;
    private int maxRequestBytes = 2_100_000;

    public void validate() {
        if ((!adminToken.isEmpty() && (adminToken.length() < 32 || adminToken.length() > 4096)) || (!readToken.isEmpty() && (readToken.length() < 32 || readToken.length() > 4096)))
            throw new IllegalStateException("API tokens must contain at least 32 characters");
        if (!adminToken.isEmpty() && adminToken.equals(readToken)) throw new IllegalStateException("Read and admin tokens must differ");
        if (requestsPerSecond < 1 || requestsPerSecond > 1000 || globalRequestsPerSecond < requestsPerSecond
                || globalRequestsPerSecond > 10_000 || maxRequestBytes < 1024 || maxRequestBytes > 8_000_000)
            throw new IllegalStateException("Invalid API resource limits");
        if (allowedOrigins.stream().anyMatch(origin -> origin.contains("*"))) throw new IllegalStateException("CORS requires explicit origins");
    }
}
