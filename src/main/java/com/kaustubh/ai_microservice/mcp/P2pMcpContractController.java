package com.kaustubh.ai_microservice.mcp;

import com.kaustubh.ai_microservice.middleware.security.RbacGuardService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/p2p/control-plane/mcp-contract")
@Profile("mcp-client")
public class P2pMcpContractController {

    private final P2pMcpContractVerifierService contractVerifierService;

    public P2pMcpContractController(P2pMcpContractVerifierService contractVerifierService) {
        this.contractVerifierService = contractVerifierService;
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verifyMcpContract(
            @RequestParam(defaultValue = "SKU-STEEL-001") String sampleSku,
            @RequestParam(defaultValue = "VEND-001") String sampleVendorId,
            @RequestHeader(value = "X-Actor-Id", defaultValue = "ops-verifier-01") String actorId,
            @RequestHeader(value = "X-Actor-Role", defaultValue = "ROLE_SUPPLY_CHAIN_OPERATOR") String actorRole,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId
    ) {
        String effectiveCorrelationId = (correlationId != null && !correlationId.isBlank())
                ? correlationId
                : "MCP-CONTRACT-" + UUID.randomUUID();

        RbacGuardService.EnterpriseActorContext actorContext =
                new RbacGuardService.EnterpriseActorContext(actorId, actorRole, effectiveCorrelationId);

        try {
            P2pMcpContractVerifierService.McpContractVerificationReport report =
                    contractVerifierService.verifyRemoteMcpContract(sampleSku, sampleVendorId, actorContext);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("contractReport", report);
            return ResponseEntity.ok(body);
        } catch (IllegalStateException ex) {
            Map<String, Object> errorBody = new LinkedHashMap<>();
            errorBody.put("verificationStatus", "DOWN_FAIL_CLOSED_TO_DLQ");
            errorBody.put("executedAt", Instant.now());
            errorBody.put("correlationId", effectiveCorrelationId);
            errorBody.put("error", ex.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorBody);
        }
    }

    @GetMapping("/audit-telemetry")
    public ResponseEntity<Map<String, Object>> getMcpAuditTelemetry(
            @RequestHeader(value = "X-Actor-Id", defaultValue = "ops-verifier-01") String actorId,
            @RequestHeader(value = "X-Actor-Role", defaultValue = "ROLE_SUPPLY_CHAIN_OPERATOR") String actorRole,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId
    ) {
        String effectiveCorrelationId = (correlationId != null && !correlationId.isBlank())
                ? correlationId
                : "MCP-TELEMETRY-" + UUID.randomUUID();

        RbacGuardService.EnterpriseActorContext actorContext =
                new RbacGuardService.EnterpriseActorContext(actorId, actorRole, effectiveCorrelationId);

        return ResponseEntity.ok(contractVerifierService.getMcpAuditAndQueueTelemetry(actorContext));
    }
}