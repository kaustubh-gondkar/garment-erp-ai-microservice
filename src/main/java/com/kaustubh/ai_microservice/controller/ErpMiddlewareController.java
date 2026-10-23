package com.kaustubh.ai_microservice.controller;

import com.kaustubh.ai_microservice.graph.P2pGraphModels;
import com.kaustubh.ai_microservice.middleware.security.ErpEventMiddlewareService;
import com.kaustubh.ai_microservice.middleware.security.RbacGuardService;
import com.kaustubh.ai_microservice.tools.ProcurementTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/erp")
public class ErpMiddlewareController {

    private final ErpEventMiddlewareService middlewareService;

    public ErpMiddlewareController(ErpEventMiddlewareService middlewareService) {
        this.middlewareService = middlewareService;
    }

    public record ManagerDecisionRequest(String decision) {}

    @PostMapping("/events/consumption")
    public ResponseEntity<ErpEventMiddlewareService.ErpWebhookResponseDto> ingestConsumptionEvent(
            @RequestBody ErpEventMiddlewareService.ErpStockConsumptionPayload payload,
            @RequestHeader(value = "X-Actor-Id", defaultValue = "sap-wms-connector-01") String actorId,
            @RequestHeader(value = "X-Actor-Role", defaultValue = "ROLE_ERP_WEBHOOK") String actorRole,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId
    ) {
        RbacGuardService.EnterpriseActorContext ctx = RbacGuardService.EnterpriseActorContext.of(actorId, actorRole, correlationId);
        return ResponseEntity.ok(middlewareService.processConsumptionWebhook(payload, ctx));
    }

    @PostMapping("/po/{poId}/decision")
    public ResponseEntity<String> decidePurchaseOrder(
            @PathVariable UUID poId,
            @RequestBody ManagerDecisionRequest request,
            @RequestHeader(value = "X-Actor-Id", defaultValue = "unknown-user") String actorId,
            @RequestHeader(value = "X-Actor-Role", defaultValue = "ROLE_SUPPLY_CHAIN_OPERATOR") String actorRole,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId
    ) {
        RbacGuardService.EnterpriseActorContext ctx = RbacGuardService.EnterpriseActorContext.of(actorId, actorRole, correlationId);
        return ResponseEntity.ok(middlewareService.executeManagerPoDecision(poId, request.decision(), ctx));
    }

    @PostMapping("/dlq/{dlqId}/replay")
    public ResponseEntity<P2pGraphModels.GraphExecutionResult> replayDlqEvent(
            @PathVariable Long dlqId,
            @RequestHeader(value = "X-Actor-Id", defaultValue = "sre-oncall-01") String actorId,
            @RequestHeader(value = "X-Actor-Role", defaultValue = "ROLE_SUPPLY_CHAIN_OPERATOR") String actorRole,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId
    ) {
        RbacGuardService.EnterpriseActorContext ctx = RbacGuardService.EnterpriseActorContext.of(actorId, actorRole, correlationId);
        return ResponseEntity.ok(middlewareService.replayDlqEvent(dlqId, ctx));
    }
}
