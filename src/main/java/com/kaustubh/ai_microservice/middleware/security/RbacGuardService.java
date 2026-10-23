package com.kaustubh.ai_microservice.middleware.security;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.UUID;

@Service
 public class RbacGuardService {

    public static final String ROLE_OPERATOR = "ROLE_SUPPLY_CHAIN_OPERATOR";
    public static final String ROLE_MANAGER = "ROLE_PROCUREMENT_MANAGER";
    public static final String ROLE_ERP_SYSTEM = "ROLE_ERP_WEBHOOK";

    private final JdbcTemplate jdbcTemplate;

    public RbacGuardService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record EnterpriseActorContext(
            String actorId,
            String actorRole,
            String correlationId
    ) {
        public static EnterpriseActorContext of(String actorId, String actorRole, String correlationId) {
            return new EnterpriseActorContext(
                    (actorId == null || actorId.isBlank()) ? "anonymous" : actorId.trim(),
                    (actorRole == null || actorRole.isBlank()) ? "ROLE_NONE" : actorRole.trim().toUpperCase(),
                    (correlationId == null || correlationId.isBlank()) ? UUID.randomUUID().toString() : correlationId.trim()
            );
        }
    }

    /**
     * Enforces role-based access control and writes an immutable audit row before sensitive tool execution.
     */
    public void authorizeOrThrow(
            EnterpriseActorContext ctx,
            String actionName,
            String targetResource,
            Set<String> allowedRoles
    ) {
        boolean isAllowed = allowedRoles.contains(ctx.actorRole());

        jdbcTemplate.update("""
                INSERT INTO agent_security_audit_log
                    (correlation_id, actor_id, actor_role, action_name, target_resource, decision, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                ctx.correlationId(),
                ctx.actorId(),
                ctx.actorRole(),
                actionName,
                targetResource,
                isAllowed ? "ALLOWED" : "DENIED",
                isAllowed
                        ? "Authorized via role " + ctx.actorRole()
                        : "Required one of " + allowedRoles + " but caller held " + ctx.actorRole()
        );

        if (!isAllowed) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    String.format("RBAC_DENIED: Actor '%s' with role '%s' is not authorized for '%s' on '%s'. Required: %s",
                            ctx.actorId(), ctx.actorRole(), actionName, targetResource, allowedRoles)
            );
        }
    }
}