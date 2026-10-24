package com.kaustubh.ai_microservice.dto;


import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class P2pControlPlaneDtos {

    private P2pControlPlaneDtos() {}

    /**
     * Request payload for a Procurement Manager resuming a paused > ₹50,000 HITL workflow.
     */
    public record HitlWorkflowResumeRequest(
            String idempotencyKey,
            UUID poId,
            String managerDecision, // "APPROVE" or "REJECT"
            String reason
    ) {}

    /**
     * Response returned after executing the manager's HITL decision and synchronizing workflow state.
     */
    public record HitlWorkflowResumeResponse(
            String idempotencyKey,
            UUID poId,
            String managerDecision,
            String purchaseOrderResult,
            String updatedWorkflowStatus,
            String updatedLedgerStatus,
            OffsetDateTime processedAt
    ) {}

    /**
     * Single row projection from agent_security_audit_log.
     */
    public record SecurityAuditEntryDto(
            Long id,
            String correlationId,
            String actorId,
            String actorRole,
            String actionName,
            String targetResource,
            String decision,
            String reason,
            OffsetDateTime createdAt
    ) {}

    /**
     * Aggregated token & estimated INR/USD cost telemetry from agent_workflow_runs + api_cost_logs.
     */
    public record TokenEconomicsSummaryDto(
            int totalStepCount,
            int maxStepsAllowed,
            long workflowTotalTokens,
            long loggedInputTokens,
            long loggedOutputTokens,
            BigDecimal estimatedCostInr
    ) {}

    /**
     * Full end-to-end correlated trace across all Phase 2 tables for a single idempotencyKey / batch_id.
     */
    public record CorrelatedExecutionTraceDto(
            String idempotencyKey,
            String erpLedgerStatus,
            String workflowRunStatus,
            String sku,
            UUID poId,
            BigDecimal poTotalAmountInr,
            String poStatus,
            OffsetDateTime poCreatedAt,
            OffsetDateTime hitlResolvedAt,
            String hitlResolvedBy,
            String finalAgentResponse,
            TokenEconomicsSummaryDto tokenEconomics,
            List<SecurityAuditEntryDto> securityAuditTrail
    ) {}

    /**
     * Batch DLQ sweep result summary.
     */
    public record DlqBatchSweepResultDto(
            int attemptedCount,
            int succeededCount,
            int failedCount,
            List<Long> processedDlqIds
    ) {}
}