package com.kaustubh.ai_microservice.mcp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ADR-008: Transport-layer guard for the standalone MCP Server (Port 8081).
 * Protects /sse and /mcp/message against non-trusted origins and tracks JSON-RPC traffic metrics.
 */
@Component
@Profile("mcp-server")
public class McpServerTransportGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(McpServerTransportGuardFilter.class);
    private static final Set<String> TRUSTED_LOOPBACK_ADDRS = Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");

    private final AtomicLong totalSseHandshakes = new AtomicLong(0);
    private final AtomicLong totalJsonRpcMessages = new AtomicLong(0);
    private final AtomicLong rejectedRequests = new AtomicLong(0);

    @Value("${p2p.mcp.security.require-api-key:false}")
    private boolean requireApiKey;

    @Value("${p2p.mcp.security.shared-key:local-dev-mcp-secret}")
    private String expectedSharedKey;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        String remoteAddr = request.getRemoteAddr();

        // 1. Enforce loopback boundary for local MCP Server
        if (!TRUSTED_LOOPBACK_ADDRS.contains(remoteAddr)) {
            rejectedRequests.incrementAndGet();
            log.warn("[MCP-TRANSPORT-GUARD] Rejected non-loopback request from IP={} to Path={}", remoteAddr, path);
            response.sendError(HttpStatus.FORBIDDEN.value(), "MCP Server restricted to trusted internal control plane.");
            return;
        }

        // 2. Optional API key check (toggleable via p2p.mcp.security.require-api-key)
        if (requireApiKey && (path.startsWith("/sse") || path.startsWith("/mcp"))) {
            String providedKey = request.getHeader("X-MCP-API-Key");
            if (providedKey == null || !providedKey.equals(expectedSharedKey)) {
                rejectedRequests.incrementAndGet();
                log.warn("[MCP-TRANSPORT-GUARD] Rejected unauthenticated MCP call to Path={}", path);
                response.sendError(HttpStatus.UNAUTHORIZED.value(), "Missing or invalid X-MCP-API-Key header.");
                return;
            }
        }

        if ("/sse".equals(path)) {
            long count = totalSseHandshakes.incrementAndGet();
            log.info("[MCP-TRANSPORT-GUARD] Active SSE handshake #{} established from {}", count, remoteAddr);
        } else if (path.startsWith("/mcp/message")) {
            totalJsonRpcMessages.incrementAndGet();
        }

        filterChain.doFilter(request, response);
    }

    public long getTotalSseHandshakes() {
        return totalSseHandshakes.get();
    }

    public long getTotalJsonRpcMessages() {
        return totalJsonRpcMessages.get();
    }

    public long getRejectedRequests() {
        return rejectedRequests.get();
    }
}