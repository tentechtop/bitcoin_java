package com.bit.coin.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

final class ApiAccessFilter extends OncePerRequestFilter {

    private static final Logger AUDIT = LoggerFactory.getLogger("node.audit");
    private final NodeApiProperties properties;
    private final Cache<String, Window> clients = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofMinutes(2))
            .build();
    private final java.util.concurrent.Semaphore concurrentRequests = new java.util.concurrent.Semaphore(8);
    private final Window global = new Window();

    ApiAccessFilter(NodeApiProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ((request.getRequestURI().equals("/actuator/health")
                || request.getRequestURI().startsWith("/actuator/health/"))) {
            chain.doFilter(request, response);
            return;
        }

        if (!global.allow(properties.getGlobalRequestsPerSecond())
                || !clients.get(request.getRemoteAddr(), ignored -> new Window())
                        .allow(properties.getRequestsPerSecond())) {
            response.setHeader("Retry-After", "1");
            reject(response, 429, "rate_limit_exceeded");
            return;
        }

        if (request.getContentLengthLong() > properties.getMaxRequestBytes()) {
            reject(response, 413, "request_too_large");
            return;
        }

        String authorization = request.getHeader("Authorization");
        if (authorization != null) {
            String token = authorization.startsWith("Bearer ") ? authorization.substring(7) : "";
            String role = matches(properties.getAdminToken(), token) ? "ROLE_ADMIN"
                    : matches(properties.getReadToken(), token) ? "ROLE_READ" : null;
            if (role == null) {
                reject(response, 401, "invalid_credentials");
                return;
            }

            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    role.equals("ROLE_ADMIN") ? "node-admin" : "node-reader", null,
                    AuthorityUtils.createAuthorityList(role)));
            SecurityContextHolder.setContext(context);
        }

        if (!concurrentRequests.tryAcquire()) {
            response.setHeader("Retry-After", "1");
            reject(response, 503, "node_busy");
            return;
        }

        try {
            chain.doFilter(new BoundedRequest(request, properties.getMaxRequestBytes()), response);
        } finally {
            concurrentRequests.release();

            if (!request.getMethod().equals("GET") && !request.getMethod().equals("HEAD")
                    && !request.getMethod().equals("OPTIONS")) {
                var auth = SecurityContextHolder.getContext().getAuthentication();
                AUDIT.info("method={} path={} principal={} remote={} status={}", request.getMethod(),
                        request.getRequestURI().replaceAll("[\\r\\n\\t]", "_"),
                        auth == null ? "anonymous" : auth.getName(), request.getRemoteAddr(), response.getStatus());
            }
        }
    }

    private static boolean matches(String expected, String supplied) {
        if (expected.isEmpty() || supplied.isEmpty() || supplied.length() > 4096) {
            return false;
        }
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            return MessageDigest.isEqual(hash.digest(expected.getBytes(StandardCharsets.UTF_8)),
                    hash.digest(supplied.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + code + "\"}");
    }

    private static final class Window {

        private long second;
        private int count;

        synchronized boolean allow(int limit) {
            long now = System.nanoTime() / 1_000_000_000L;
            if (second != now) {
                second = now;
                count = 0;
            }
            return ++count <= limit;
        }
    }

    static final class RequestTooLarge extends RuntimeException {}

    // 读取请求体时累计字节数，使未声明 Content-Length 的请求同样受限。
    private static final class BoundedRequest extends HttpServletRequestWrapper {

        private ServletInputStream stream;
        private final int limit;

        BoundedRequest(HttpServletRequest request, int limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                ServletInputStream source = super.getInputStream();
                stream = new ServletInputStream() {

                    private int consumed;

                    @Override
                    public int read() throws IOException {
                        int value = source.read();
                        if (value >= 0 && ++consumed > limit) {
                            throw new RequestTooLarge();
                        }
                        return value;
                    }

                    @Override
                    public int read(byte[] bytes, int offset, int length) throws IOException {
                        int count = source.read(bytes, offset, Math.min(length, limit - consumed + 1));
                        if (count > 0 && (consumed += count) > limit) {
                            throw new RequestTooLarge();
                        }
                        return count;
                    }

                    @Override
                    public boolean isFinished() {
                        return source.isFinished();
                    }

                    @Override
                    public boolean isReady() {
                        return source.isReady();
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        source.setReadListener(listener);
                    }
                };
            }
            return stream;
        }

        @Override
        public java.io.BufferedReader getReader() throws IOException {
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
