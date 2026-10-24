package com.kaustubh.ai_microservice.controller;

import com.kaustubh.ai_microservice.dto.P2pControlPlaneDtos.CorrelatedExecutionTraceDto;
import com.kaustubh.ai_microservice.dto.P2pControlPlaneDtos.DlqBatchSweepResultDto;
import com.kaustubh.ai_microservice.dto.P2pControlPlaneDtos.HitlWorkflowResumeRequest;
import com.kaustubh.ai_microservice.dto.P2pControlPlaneDtos.HitlWorkflowResumeResponse;
import com.kaustubh.ai_microservice.middleware.security.RbacGuardService;
import com.kaustubh.ai_microservice.service.P2pLifecycleCoordinatorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/p2p/control-plane")
public class P2pControlPlaneController {

    private final P2pLifecycleCoordinatorService coordinatorService;

    public P2pControlPlaneController(P2pLifecycleCoordinatorService coordinatorService) {
        this.coordinatorService = coordinatorService;
    }

    /**
     * 1. Resumes a paused > ₹50,000 HITL workflow and synchronizes PO + workflow + ERP ledger states.
     */
    @PostMapping("/hitl/resume")
    public ResponseEntity<HitlWorkflowResumeResponse> resumeHitlWorkflow(
            @RequestHeader("X-Actor-Id") String actorId,
            @RequestHeader("X-Actor-Role") String actorRole,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody HitlWorkflowResumeRequest request
    ) {
        String effectiveCorrId = (correlationId != null && !correlationId.isBlank())
                ? correlationId
                : request.idempotencyKey();

    RbacGuardService.EnterpriseActorContext actorContext =
        new RbacGuardService.EnterpriseActorContext(actorId, actorRole, effectiveCorrId);

        return ResponseEntity.ok(
                coordinatorService.resumePausedHitlWorkflow(request, actorContext)
        );
    }

    /**
     * 2. Fetches the unified multi-table execution & security audit trace for any batch_id / idempotencyKey.
     */
    @GetMapping("/traces/{idempotencyKey}")
    public ResponseEntity<CorrelatedExecutionTraceDto> getExecutionTrace(
            @PathVariable String idempotencyKey,
            @RequestHeader("X-Actor-Id") String actorId,
            @RequestHeader("X-Actor-Role") String actorRole
    ) {
    RbacGuardService.EnterpriseActorContext actorContext =
        new RbacGuardService.EnterpriseActorContext(actorId, actorRole, idempotencyKey);

        return ResponseEntity.ok(
                coordinatorService.getCorrelatedExecutionTrace(idempotencyKey, actorContext)
        );
    }

    /**
     * 3. Triggers an on-demand batch sweep of retryable DLQ events using FOR UPDATE SKIP LOCKED under the hood.
     */
    @PostMapping("/dlq/sweep")
    public ResponseEntity<DlqBatchSweepResultDto> sweepDlq(
            @RequestParam(defaultValue = "5") int limit,
            @RequestHeader("X-Actor-Id") String actorId,
            @RequestHeader("X-Actor-Role") String actorRole,
            @RequestHeader(value = "X-Correlation-Id", defaultValue = "DLQ-SWEEP-001") String correlationId
    ) {
    RbacGuardService.EnterpriseActorContext actorContext =
        new RbacGuardService.EnterpriseActorContext(actorId, actorRole, correlationId);

        return ResponseEntity.ok(
                coordinatorService.sweepPendingDlqEvents(limit, actorContext)
        );
    }
}
