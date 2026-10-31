package com.kaustubh.ai_microservice.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kaustubh.ai_microservice.config.P2pMcpToolRouter;
import com.kaustubh.ai_microservice.middleware.security.RbacGuardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Profile("mcp-client")
public class P2pMcpContractVerifierService {

    private static final Logger log = LoggerFactory.getLogger(P2pMcpContractVerifierService.class);

    private static final Set<String> ALLOWED_CONTRACT_ROLES = Set.of(
            "ROLE_SUPPLY_CHAIN_OPERATOR",
            "ROLE_PROCUREMENT_MANAGER"
    );

    private static final List<String> EXPECTED_CANONICAL_TOOLS = List.of(
            "checkInventoryLevels",
            "checkCompliancePolicy",
            "verifyVendorSlaAndProcurementPolicy",
            "createPurchaseOrder"
    );


    private final P2pMcpToolRouter mcpToolRouter;
    private final RbacGuardService rbacGuardService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public P2pMcpContractVerifierService(
            P2pMcpToolRouter mcpToolRouter,
            RbacGuardService rbacGuardService,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.mcpToolRouter = mcpToolRouter;
        this.rbacGuardService = rbacGuardService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public record ToolWireProbeResult(
            String toolName,
            String status,
            long wireRoundTripMs,
            String requestPayloadJson,
            String responsePreview,
            boolean serverAuditRowVerified
    ) {}

    public record McpContractVerificationReport(
            String verificationStatus,
            Instant executedAt,
            String correlationId,
            boolean schemaParityPassed,
            List<String> discoveredTools,
            List<String> missingTools,
            long totalVerificationLatencyMs,
            List<ToolWireProbeResult> readOnlyWireProbes,
            int newServerSideAuditRowsCreated,
            Map<String, Object> systemQueueSnapshot
    ) {}

    /**
     * Runs a zero-LLM-token deterministic contract verification across :8080 (mcp-client) -> :8081 (mcp-server).
     * Executes read-only remote tools over SSE/JSON-RPC and verifies P2pMcpServerAuditAspect persistence.
     */
    public McpContractVerificationReport verifyRemoteMcpContract(
            String sampleSku,
            String sampleVendorId,
            RbacGuardService.EnterpriseActorContext actorContext
    ) {
        long suiteStartMs = System.currentTimeMillis();
        Instant probeWindowStart = Instant.now().minusSeconds(2);

        // 1. Enforce client-side RBAC audit before running control-plane verification
        rbacGuardService.authorizeOrThrow(
                actorContext,
                "VERIFY_MCP_WIRE_CONTRACT",
                "MCP_SERVER:8081",
                ALLOWED_CONTRACT_ROLES
        );

        // 2. Resolve active remote MCP ToolCallbacks from :8081 (fails closed if :8081 is unreachable)
        ToolCallback[] callbacks = mcpToolRouter.resolveActiveToolCallbacks();
        Map<String, ToolCallback> callbackMap = Arrays.stream(callbacks)
                .collect(Collectors.toMap(
                        cb -> cb.getToolDefinition().name(),
                        cb -> cb,
                        (existing, replacement) -> replacement,
                        LinkedHashMap::new
                ));

        List<String> discoveredTools = new ArrayList<>(callbackMap.keySet());
        List<String> missingTools = EXPECTED_CANONICAL_TOOLS.stream()
                .filter(expected -> !callbackMap.containsKey(expected))
                .toList();
        boolean schemaParityPassed = missingTools.isEmpty();

        int auditBaselineCount = countServerMcpAuditRowsSince(probeWindowStart);

        // 3. Execute zero-LLM-token JSON-RPC wire probes for read-only tools (:8080 -> :8081)
        List<ToolWireProbeResult> probeResults = new ArrayList<>();

        if (callbackMap.containsKey("checkInventoryLevels")) {
            probeResults.add(executeReadOnlyToolProbe(
                    callbackMap.get("checkInventoryLevels"),
                    sampleSku,
                    sampleVendorId,
                    probeWindowStart
            ));
        }

        if (callbackMap.containsKey("verifyVendorSlaAndProcurementPolicy")) {
            probeResults.add(executeReadOnlyToolProbe(
                    callbackMap.get("verifyVendorSlaAndProcurementPolicy"),
                    sampleSku,
                    sampleVendorId,
                    probeWindowStart
            ));
        }

        int auditPostCount = countServerMcpAuditRowsSince(probeWindowStart);
        int newServerAuditRows = Math.max(0, auditPostCount - auditBaselineCount);

        boolean allProbesPassed = probeResults.stream()
                .allMatch(p -> "PASSED".equals(p.status()) && p.serverAuditRowVerified());

        String overallStatus = (schemaParityPassed && allProbesPassed)
                ? "CONTRACT_VERIFIED_HEALTHY"
                : "CONTRACT_DEGRADED";

        long totalMs = System.currentTimeMillis() - suiteStartMs;

        return new McpContractVerificationReport(
                overallStatus,
                Instant.now(),
                actorContext.correlationId(),
                schemaParityPassed,
                discoveredTools,
                missingTools,
                totalMs,
                probeResults,
                newServerAuditRows,
                fetchSystemQueueSnapshot()
        );
    }

    /**
     * Returns an aggregated telemetry snapshot of server-side MCP-EXEC-* audit logs,
     * HITL pending POs (> 50,000 INR), and fail-closed DLQ events.
     */
    public Map<String, Object> getMcpAuditAndQueueTelemetry(
            RbacGuardService.EnterpriseActorContext actorContext
    ) {
        rbacGuardService.authorizeOrThrow(
                actorContext,
                "READ_MCP_AUDIT_TELEMETRY",
                "MCP_SERVER:8081",
                ALLOWED_CONTRACT_ROLES
        );

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("timestamp", Instant.now());

        List<Map<String, Object>> recentServerAudits = jdbcTemplate.queryForList("""
                SELECT correlation_id, actor_id, actor_role, action_name, target_resource, decision, reason, created_at
                FROM agent_security_audit_log
                WHERE correlation_id LIKE 'MCP-EXEC-%'
                ORDER BY created_at DESC
                LIMIT 15
                """);

        List<Map<String, Object>> actionSummary = jdbcTemplate.queryForList("""
                SELECT action_name, decision, COUNT(*) AS execution_count
                FROM agent_security_audit_log
                WHERE correlation_id LIKE 'MCP-EXEC-%'
                GROUP BY action_name, decision
                ORDER BY execution_count DESC
                """);

        response.put("serverSideMcpAuditSummary", actionSummary);
        response.put("recentServerSideMcpExecutions", recentServerAudits);
        response.put("queueHealthSnapshot", fetchSystemQueueSnapshot());
        return response;
    }

    private ToolWireProbeResult executeReadOnlyToolProbe(
            ToolCallback callback,
            String sampleSku,
            String sampleVendorId,
            Instant probeWindowStart
    ) {
        String toolName = callback.getToolDefinition().name();
        String inputSchemaJson = callback.getToolDefinition().inputSchema();
        String requestPayload = buildSchemaAwareProbePayload(inputSchemaJson, sampleSku, sampleVendorId);

        long startMs = System.currentTimeMillis();
        try {
            String rawResponse = callback.call(requestPayload);
            long elapsedMs = System.currentTimeMillis() - startMs;

            String preview = (rawResponse != null && rawResponse.length() > 240)
                    ? rawResponse.substring(0, 240) + "..."
                    : String.valueOf(rawResponse);

            boolean auditVerified = verifyServerAuditExistsForTool(toolName, probeWindowStart);

            return new ToolWireProbeResult(
                    toolName,
                    "PASSED",
                    elapsedMs,
                    requestPayload,
                    preview,
                    auditVerified
            );
        } catch (Exception ex) {
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.error("MCP wire probe failed for tool {}: {}", toolName, ex.getMessage());
            return new ToolWireProbeResult(
                    toolName,
                    "FAILED",
                    elapsedMs,
                    requestPayload,
                    "ERROR: " + ex.getMessage(),
                    false
            );
        }
    }

    /**
     * Dynamically inspects the remote MCP tool's JSON Schema so the probe payload
     * always matches the exact parameter names exposed by Spring AI's MCP Server.
     */
    private String buildSchemaAwareProbePayload(
            String inputSchemaJson,
            String sampleSku,
            String sampleVendorId
    ) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            if (inputSchemaJson == null || inputSchemaJson.isBlank()) {
                return "{}";
            }
            JsonNode schemaNode = objectMapper.readTree(inputSchemaJson);
            JsonNode propertiesNode = schemaNode.get("properties");
            if (propertiesNode == null || !propertiesNode.isObject()) {
                return "{}";
            }

            Iterator<String> fieldNames = propertiesNode.fieldNames();
            while (fieldNames.hasNext()) {
                String field = fieldNames.next();
                String lower = field.toLowerCase();
                if (lower.contains("sku")) {
                    payload.put(field, sampleSku);
                } else if (lower.contains("vendor")) {
                    payload.put(field, sampleVendorId);
                } else if (lower.contains("query") || lower.contains("policy") || lower.contains("topic")) {
                    payload.put(field, "SLA delivery penalty and compliance policy");
                } else {
                    payload.put(field, sampleSku);
                }
            }
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    private int countServerMcpAuditRowsSince(Instant since) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM agent_security_audit_log
                WHERE correlation_id LIKE 'MCP-EXEC-%'
                  AND created_at >= ?
                """, Integer.class, Timestamp.from(since));
        return count != null ? count : 0;
    }

    private boolean verifyServerAuditExistsForTool(String toolName, Instant since) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM agent_security_audit_log
                WHERE correlation_id LIKE 'MCP-EXEC-%'
                  AND LOWER(action_name) LIKE ?
                  AND decision = 'ALLOWED'
                  AND created_at >= ?
                """, Integer.class, "%" + toolName.toLowerCase() + "%", Timestamp.from(since));
        return count != null && count > 0;
    }

    private Map<String, Object> fetchSystemQueueSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();

        Integer pendingDlq = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM agent_dlq_events WHERE status = 'PENDING_REPLAY'
                """, Integer.class);

        Integer pendingHitlPos = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM purchase_orders WHERE status = 'PENDING_MANAGER_APPROVAL'
                """, Integer.class);

        Integer pausedWorkflows = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM agent_workflow_runs WHERE status LIKE '%HITL%' OR status LIKE '%PENDING%'
                """, Integer.class);

        snapshot.put("pendingDlqReplayCount", pendingDlq != null ? pendingDlq : 0);
        snapshot.put("pendingHitlPoCountOver50kInr", pendingHitlPos != null ? pendingHitlPos : 0);
        snapshot.put("pausedHitlWorkflowRuns", pausedWorkflows != null ? pausedWorkflows : 0);
        return snapshot;
    }
}