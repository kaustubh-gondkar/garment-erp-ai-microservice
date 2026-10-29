package com.kaustubh.ai_microservice.controller;

import com.kaustubh.ai_microservice.config.P2pMcpToolRouter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * ADR-008: Control-Plane diagnostic probe on Port 8080 (mcp-client)
 * verifying remote MCP Server (:8081) tool contract readiness and round-trip latency.
 */
@RestController
@RequestMapping("/api/v1/p2p/control-plane")
@Profile("mcp-client")
public class P2pMcpBridgeDiagnosticsController {

    private final P2pMcpToolRouter mcpToolRouter;

    public P2pMcpBridgeDiagnosticsController(P2pMcpToolRouter mcpToolRouter) {
        this.mcpToolRouter = mcpToolRouter;
    }

    @GetMapping("/mcp-bridge-status")
    public ResponseEntity<Map<String, Object>> inspectMcpBridgeStatus() {
        long startNanos = System.nanoTime();
        try {
            ToolCallback[] callbacks = mcpToolRouter.resolveActiveToolCallbacks();
            long pingLatencyMs = (System.nanoTime() - startNanos) / 1_000_000;

            List<Map<String, String>> discoveredTools = Arrays.stream(callbacks)
                    .map(cb -> Map.of(
                            "toolName", cb.getToolDefinition().name(),
                            "description", cb.getToolDefinition().description()
                    ))
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "status", "UP_AND_SYNCHRONIZED",
                    "remoteMcpServerUrl", "http://localhost:8081",
                    "pingLatencyMs", pingLatencyMs,
                    "registeredToolCount", discoveredTools.size(),
                    "discoveredTools", discoveredTools,
                    "failClosedDlqGuard", "ARMED",
                    "timestamp", Instant.now().toString()
            ));
        } catch (Exception ex) {
            long failureLatencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", "DOWN_FAIL_CLOSED_TO_DLQ",
                    "remoteMcpServerUrl", "http://localhost:8081",
                    "pingLatencyMs", failureLatencyMs,
                    "error", ex.getMessage(),
                    "timestamp", Instant.now().toString()
            ));
        }
    }
}