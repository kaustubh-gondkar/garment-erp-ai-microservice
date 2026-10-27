package com.kaustubh.ai_microservice.config;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class P2pMcpToolRouter {

    private static final Logger log = LoggerFactory.getLogger(P2pMcpToolRouter.class);

    private final AtomicReference<List<McpSyncClient>> activeClientsRef = new AtomicReference<>(Collections.emptyList());
    private final String mcpServerUrl;

    @Autowired
    public P2pMcpToolRouter(
            @Autowired(required = false) List<McpSyncClient> initialClients,
            @Value("${spring.ai.mcp.client.sse.connections.p2p-supply-chain-mcp-server.url:http://localhost:8081}") String mcpServerUrl) {
        if (initialClients != null && !initialClients.isEmpty()) {
            this.activeClientsRef.set(initialClients);
        }
        this.mcpServerUrl = mcpServerUrl;
    }

    public synchronized ToolCallback[] resolveActiveToolCallbacks() {
        log.warn("[MCP-CLIENT-ROUTER] >>> PROBING REMOTE MCP SERVER ({}) FOR ACTIVE TOOLS <<<", mcpServerUrl);

        List<McpSyncClient> clients = activeClientsRef.get();
        List<String> rawServerToolNames = new ArrayList<>();

        try {
            if (clients.isEmpty()) {
                clients = Collections.singletonList(createFreshMcpClient());
                activeClientsRef.set(clients);
            }
            probeClients(clients, rawServerToolNames);
        } catch (Exception firstEx) {
            log.warn("[MCP-CLIENT-ROUTER] Stale or broken SSE session detected ({}). Attempting fresh SSE handshake with {}...",
                    firstEx.getMessage(), mcpServerUrl);
            try {
                rawServerToolNames.clear();
                McpSyncClient freshClient = createFreshMcpClient();
                clients = Collections.singletonList(freshClient);
                probeClients(clients, rawServerToolNames);
                activeClientsRef.set(clients);
                log.warn("[MCP-CLIENT-ROUTER] >>> SELF-HEALING SSE RECONNECT SUCCEEDED ON {} <<<", mcpServerUrl);
            } catch (Exception reconnectEx) {
                log.error("[MCP-CLIENT-ROUTER] Remote MCP Server ({}) is UNREACHABLE: {}", mcpServerUrl, reconnectEx.getMessage());
                throw new IllegalStateException(
                        "MCP Transport Error: Remote MCP Server (:8081) is down or unreachable.", reconnectEx);
            }
        }

        SyncMcpToolCallbackProvider remoteProvider = new SyncMcpToolCallbackProvider(clients);
        ToolCallback[] rawCallbacks = remoteProvider.getToolCallbacks();

        ToolCallback[] cleanCallbacks = Arrays.stream(rawCallbacks)
                .map(delegate -> wrapWithCleanToolName(delegate, rawServerToolNames))
                .toArray(ToolCallback[]::new);

        List<String> exposedNames = Arrays.stream(cleanCallbacks)
                .map(cb -> cb.getToolDefinition().name())
                .toList();
        log.warn("[MCP-CLIENT-ROUTER] >>> DISCOVERED {} REMOTE MCP TOOLS ON PORT 8081: {} <<<",
                exposedNames.size(), exposedNames);

        return cleanCallbacks;
    }

    private void probeClients(List<McpSyncClient> clients, List<String> rawServerToolNames) {
        for (McpSyncClient client : clients) {
            McpSchema.ListToolsResult toolsResult = client.listTools();
            if (toolsResult == null || toolsResult.tools() == null || toolsResult.tools().isEmpty()) {
                throw new IllegalStateException("Remote MCP Server (:8081) returned 0 tools!");
            }
            toolsResult.tools().forEach(t -> rawServerToolNames.add(t.name()));
        }
    }

    private McpSyncClient createFreshMcpClient() {
        HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(mcpServerUrl).build();
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .build();
        client.initialize();
        return client;
    }

    private ToolCallback wrapWithCleanToolName(ToolCallback delegate, List<String> rawServerToolNames) {
        String originalDefName = delegate.getToolDefinition().name();
        String cleanName = rawServerToolNames.stream()
                .filter(raw -> originalDefName.equals(raw) || originalDefName.endsWith("_" + raw))
                .findFirst()
                .orElse(originalDefName);

        ToolDefinition cleanDefinition = ToolDefinition.builder()
                .name(cleanName)
                .description(delegate.getToolDefinition().description())
                .inputSchema(delegate.getToolDefinition().inputSchema())
                .build();

        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return cleanDefinition;
            }

            @Override
            public String call(String toolInput) {
                log.warn("[MCP-CLIENT -> PORT 8081] Invoking remote MCP tool '{}' over HTTP/SSE with args: {}",
                        cleanName, toolInput);
                String response = delegate.call(toolInput);
                log.warn("[MCP-SERVER <- PORT 8081] Remote MCP tool '{}' returned: {}",
                        cleanName, response);
                return response;
            }
        };
    }
}