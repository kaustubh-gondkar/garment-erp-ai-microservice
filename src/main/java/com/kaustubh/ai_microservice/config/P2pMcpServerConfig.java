package com.kaustubh.ai_microservice.config;

import com.kaustubh.ai_microservice.tools.ComplianceRagTools;
import com.kaustubh.ai_microservice.tools.ProcurementTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * ADR-007: Isolated MCP Server Configuration (Active ONLY under 'mcp-server' or 'mcp-stdio' profile).
 * Wraps existing ADR-001 (ProcurementTools) and ADR-002 (ComplianceRagTools) beans into
 * a standardized Model Context Protocol ToolCallbackProvider with zero changes to domain logic.
 */
@Configuration
@Profile({"mcp-server", "mcp-stdio"})
public class P2pMcpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(P2pMcpServerConfig.class);

    /**
     * Exposes all @Tool annotated methods inside ProcurementTools and ComplianceRagTools
     * over MCP (tools/list and tools/call).
     *
     * Tools exposed automatically:
     * 1. checkInventoryLevels (ADR-001)
     * 2. createPurchaseOrder (ADR-001 - includes > ₹50,000 HITL PENDING_MANAGER_APPROVAL gate)
     * 3. confirmHighValuePurchaseOrder (ADR-001)
     * 4. verifyVendorSlaAndPolicy (ADR-002 - pgvector + tsvector RRF k=60 + Cross-Encoder)
     */
    @Bean
    public ToolCallbackProvider p2pEnterpriseMcpToolCallbackProvider(
            ProcurementTools procurementTools,
            ComplianceRagTools complianceRagTools) {

        ToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(procurementTools, complianceRagTools)
                .build();

        List<String> registeredToolNames = Arrays.stream(provider.getToolCallbacks())
                .map(tc -> tc.getToolDefinition().name())
                .toList();

        log.info("[MCP-SERVER] Successfully registered {} P2P Enterprise Tools for remote MCP invocation: {}",
                registeredToolNames.size(), registeredToolNames);

        return provider;
    }

    /**
     * Lightweight diagnostics controller active only on the HTTP/SSE MCP Server (Port 8081)
     * so you can verify registered tool definitions in Postman before running the Orchestrator.
     */
    @RestController
    @Profile("mcp-server")
    public static class McpServerDiagnosticsController {

        private final ToolCallbackProvider toolCallbackProvider;

        public McpServerDiagnosticsController(ToolCallbackProvider toolCallbackProvider) {
            this.toolCallbackProvider = toolCallbackProvider;
        }

        @GetMapping("/mcp/diagnostics/tools")
        public ResponseEntity<Map<String, Object>> inspectRegisteredMcpTools() {
            ToolCallback[] callbacks = toolCallbackProvider.getToolCallbacks();
            List<Map<String, String>> toolSpecs = Arrays.stream(callbacks)
                    .map(cb -> Map.of(
                            "name", cb.getToolDefinition().name(),
                            "description", cb.getToolDefinition().description(),
                            "inputSchema", cb.getToolDefinition().inputSchema()
                    ))
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "mcpServerStatus", "UP",
                    "transport", "HTTP_SSE",
                    "sseEndpoint", "/sse",
                    "messageEndpoint", "/mcp/message",
                    "registeredToolCount", toolSpecs.size(),
                    "tools", toolSpecs,
                    "timestamp", Instant.now().toString()
            ));
        }
    }
}