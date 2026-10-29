package com.kaustubh.ai_microservice.mcp.security;

import com.kaustubh.ai_microservice.middleware.security.RbacGuardService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * ADR-008: Enforces deterministic RBAC and writes immutable audit records to
 * agent_security_audit_log inside the standalone MCP Server (:8081 / mcp-stdio)
 * before any @Tool method in ProcurementTools or ComplianceRagTools executes.
 */
@Aspect
@Component
@Profile({"mcp-server", "mcp-stdio"})
public class P2pMcpServerAuditAspect {

    private static final Logger log = LoggerFactory.getLogger(P2pMcpServerAuditAspect.class);

    private static final Set<String> STANDARD_MCP_TOOL_ROLES = Set.of(
            "ROLE_ERP_WEBHOOK",
            "ROLE_SUPPLY_CHAIN_OPERATOR",
            "ROLE_PROCUREMENT_MANAGER"
    );

    private static final Set<String> MANAGER_ONLY_ROLES = Set.of(
            "ROLE_PROCUREMENT_MANAGER"
    );

    private final RbacGuardService rbacGuardService;

    public P2pMcpServerAuditAspect(RbacGuardService rbacGuardService) {
        this.rbacGuardService = rbacGuardService;
    }

    @Around("execution(@org.springframework.ai.tool.annotation.Tool * *(..))")
    public Object enforceMcpServerAuditBoundary(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        Object[] args = joinPoint.getArgs();

        String targetResource = resolveTargetResource(methodName, args);
        boolean isHighPrivilegeTool = "confirmHighValuePurchaseOrder".equals(methodName);

        String effectiveRole = isHighPrivilegeTool
                ? "ROLE_PROCUREMENT_MANAGER"
                : "ROLE_SUPPLY_CHAIN_OPERATOR";

        Set<String> requiredRoles = isHighPrivilegeTool
                ? MANAGER_ONLY_ROLES
                : STANDARD_MCP_TOOL_ROLES;

        String correlationId = "MCP-EXEC-" + UUID.randomUUID().toString().substring(0, 8);
        RbacGuardService.EnterpriseActorContext mcpActorContext = new RbacGuardService.EnterpriseActorContext(
                "mcp-remote-orchestrator",
                effectiveRole,
                correlationId
        );

        long startNanos = System.nanoTime();
        try {
            // Reuses existing RbacGuardService to verify role and INSERT into agent_security_audit_log
            rbacGuardService.authorizeOrThrow(
                    mcpActorContext,
                    "MCP_TOOL_" + methodName,
                    targetResource,
                    requiredRoles
            );

            Object result = joinPoint.proceed();
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;

            log.info("[MCP-SERVER-AUDIT] Tool={} Target={} CorrelationId={} LatencyMs={} Status=SUCCESS",
                    methodName, targetResource, correlationId, durationMs);
            return result;
        } catch (Throwable ex) {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.error("[MCP-SERVER-AUDIT] Tool={} Target={} CorrelationId={} LatencyMs={} Status=FAILED Reason={}",
                    methodName, targetResource, correlationId, durationMs, ex.getMessage());
            throw ex;
        }
    }

    private String resolveTargetResource(String methodName, Object[] args) {
        if (args == null || args.length == 0 || args[0] == null) {
            return "MCP_RESOURCE:ALL_INVENTORY";
        }
        return switch (methodName) {
            case "createPurchaseOrder" -> "SKU:" + args[0];
            case "confirmHighValuePurchaseOrder" -> "PO:" + args[0];
            case "verifyVendorSlaAndPolicy", "checkCompliancePolicy" ->
                    args.length > 1 && args[1] != null ? "VENDOR:" + args[1] : "POLICY_QUERY:" + args[0];
            default -> "MCP_ARG:" + args[0];
        };
    }
}