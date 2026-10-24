package com.kaustubh.ai_microservice.service;

import com.kaustubh.ai_microservice.dto.P2pControlPlaneDtos.CorrelatedExecutionTraceDto;
import com.kaustubh.ai_microservice.dto .P2pControlPlaneDtos.DlqBatchSweepResultDto;
import com.kaustubh.ai_microservice.dto.P2pControlPlaneDtos.HitlWorkflowResumeRequest;
import com.kaustubh.ai_microservice.dto.P2pControlPlaneDtos.HitlWorkflowResumeResponse;
import com.kaustubh.ai_microservice.dto.P2pControlPlaneDtos.SecurityAuditEntryDto;
import com.kaustubh.ai_microservice.dto.P2pControlPlaneDtos.TokenEconomicsSummaryDto;
import com.kaustubh.ai_microservice.graph.P2pGraphOrchestrator;
import com.kaustubh.ai_microservice.middleware.security.ErpEventMiddlewareService;
import com.kaustubh.ai_microservice.middleware.security.RbacGuardService;
import com.kaustubh.ai_microservice.tools.ComplianceRagTools;
import com.kaustubh.ai_microservice.tools.ProcurementTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class P2pLifecycleCoordinatorService {

    private static final Logger log = LoggerFactory.getLogger(P2pLifecycleCoordinatorService.class);

    // Gemini 2.5 Flash blended cost estimate in INR per 1,000 tokens for executive ROI reporting
    private static final BigDecimal COST_PER_1K_TOKENS_INR = new BigDecimal("0.0250");

    private final RbacGuardService rbacGuardService;
    private final ErpEventMiddlewareService erpEventMiddlewareService;
    private final P2pGraphOrchestrator p2pGraphOrchestrator;
    private final ProcurementTools procurementTools;
    private final ComplianceRagTools complianceRagService;
    private final JdbcTemplate jdbcTemplate;

    public P2pLifecycleCoordinatorService(
            RbacGuardService rbacGuardService,
            ErpEventMiddlewareService erpEventMiddlewareService,
            P2pGraphOrchestrator p2pGraphOrchestrator,
            ProcurementTools procurementTools,
            ComplianceRagTools complianceRagService,
            JdbcTemplate jdbcTemplate
    ) {
        this.rbacGuardService = rbacGuardService;
        this.erpEventMiddlewareService = erpEventMiddlewareService;
        this.p2pGraphOrchestrator = p2pGraphOrchestrator;
        this.procurementTools = procurementTools;
        this.complianceRagService = complianceRagService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Sweeps pending/failed DLQ rows in batch by delegating each row to the existing
     * ErpEventMiddlewareService.replayDlqEvent(dlqId, actorContext) [ADR-005].
     */
    public DlqBatchSweepResultDto sweepPendingDlqEvents(int batchLimit, RbacGuardService.EnterpriseActorContext actorContext) {
        rbacGuardService.authorizeOrThrow(
                actorContext,
                "DLQ_BATCH_SWEEP",
                "agent_dlq_events",
                Set.of("ROLE_SUPPLY_CHAIN_OPERATOR", "ROLE_PROCUREMENT_MANAGER")
        );

        List<Long> candidateIds = jdbcTemplate.queryForList("""
                SELECT id
                FROM agent_dlq_events
                WHERE status IN ('PENDING', 'FAILED_RETRYABLE')
                  AND retry_count < 3
                ORDER BY created_at ASC
                LIMIT ?
                """,
                Long.class,
                batchLimit
        );

        int succeeded = 0;
        int failed = 0;
        List<Long> processed = new ArrayList<>();

        for (Long dlqId : candidateIds) {
            try {
                erpEventMiddlewareService.replayDlqEvent(dlqId, actorContext);
                succeeded++;
                processed.add(dlqId);
            } catch (Exception ex) {
                log.warn("DLQ replay failed during sweep for dlqId={}: {}", dlqId, ex.getMessage());
                failed++;
            }
        }

        return new DlqBatchSweepResultDto(candidateIds.size(), succeeded, failed, processed);
    }
    @Transactional
    public HitlWorkflowResumeResponse resumePausedHitlWorkflow(
            HitlWorkflowResumeRequest request,
            RbacGuardService.EnterpriseActorContext actorContext
    ) {
        OffsetDateTime processedAt = OffsetDateTime.now();

        // Force correlationId to match request.idempotencyKey() so audit logs never drift due to stale Postman headers
        RbacGuardService.EnterpriseActorContext alignedContext = new RbacGuardService.EnterpriseActorContext(
                actorContext.actorId(),
                actorContext.actorRole(),
                request.idempotencyKey()
        );

        // 1. Delegate RBAC check to existing RbacGuardService [ADR-005]
        rbacGuardService.authorizeOrThrow(
                alignedContext,
                "HITL_WORKFLOW_RESUME",
                "PO:" + request.poId(),
                Set.of("ROLE_PROCUREMENT_MANAGER")
        );

        // 2. Fetch authoritative SKU & created_at from purchase_orders
        var poMetaList = jdbcTemplate.query("""
                SELECT sku, idempotency_key, created_at
                FROM purchase_orders
                WHERE po_id = ?
                """,
                (rs, rowNum) -> new Object[]{
                        rs.getString("sku"),
                        rs.getString("idempotency_key"),
                        rs.getObject("created_at", OffsetDateTime.class)
                },
                request.poId()
        );

        if (poMetaList.isEmpty()) {
            throw new IllegalArgumentException("Purchase Order not found for poId: " + request.poId());
        }

        String poSku = (String) poMetaList.getFirst()[0];

        // 3. Guard against Cross-SKU Contamination in erp_event_ledger
        List<String> ledgerSkus = jdbcTemplate.queryForList(
                "SELECT sku FROM erp_event_ledger WHERE batch_id = ?",
                String.class,
                request.idempotencyKey()
        );

        if (!ledgerSkus.isEmpty() && !poSku.equalsIgnoreCase(ledgerSkus.getFirst())) {
            throw new IllegalArgumentException(String.format(
                    "Cross-SKU correlation mismatch: PO %s belongs to SKU [%s], but ERP batch_id [%s] belongs to SKU [%s].",
                    request.poId(), poSku, request.idempotencyKey(), ledgerSkus.getFirst()
            ));
        }

        // 4. Delegate PO approval/rejection to existing verified bean [ADR-001 / ADR-005]
        String normalizedDecision = request.managerDecision().trim().toUpperCase();
        String poExecutionResult;

        if ("APPROVE".equals(normalizedDecision) || "APPROVED".equals(normalizedDecision)) {
            poExecutionResult = procurementTools.confirmHighValuePurchaseOrder(
                    request.poId(),
                    alignedContext.actorId()
            );
        } else {
            var poDto = erpEventMiddlewareService.executeManagerPoDecision(
                    request.poId(),
                    normalizedDecision,
                    alignedContext
            );
            poExecutionResult = "PO " + request.poId() + " transitioned via manager decision: " + poDto;
        }

        // 5. Update agent_workflow_runs if row exists, OR insert a synchronized row if P2pGraphOrchestrator didn't create one
        String targetWorkflowStatus = ("APPROVE".equals(normalizedDecision) || "APPROVED".equals(normalizedDecision))
                ? "COMPLETED_AFTER_HITL"
                : "REJECTED_BY_MANAGER";

        String resolutionSummary = String.format(
                "[resolved_at=%s] HITL %s by %s: %s | Reason: %s",
                processedAt,
                normalizedDecision,
                alignedContext.actorId(),
                poExecutionResult,
                request.reason()
        );

        String poIdStr = request.poId().toString();

        int workflowRowsUpdated = jdbcTemplate.update("""
                UPDATE agent_workflow_runs
                SET status = ?,
                    pending_tool_payload = NULL,
                    final_response = COALESCE(final_response, '') || ' | ' || ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE run_id = (
                    SELECT run_id
                    FROM agent_workflow_runs
                    WHERE idempotency_key = ?
                       OR idempotency_key LIKE '%' || ? || '%'
                       OR final_response LIKE '%' || ? || '%'
                       OR pending_tool_payload::text LIKE '%' || ? || '%'
                       OR user_prompt LIKE '%' || ? || '%'
                    ORDER BY created_at DESC
                    LIMIT 1
                )
                """,
                targetWorkflowStatus,
                resolutionSummary,
                request.idempotencyKey(),
                request.idempotencyKey(),
                poIdStr,
                poIdStr,
                poSku
        );

        if (workflowRowsUpdated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO agent_workflow_runs (
                        run_id, idempotency_key, user_prompt, status,
                        step_count, max_steps, total_tokens_used,
                        pending_tool_payload, final_response, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, 3, 5, 0, NULL, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """,
                    UUID.randomUUID(),
                    request.idempotencyKey(),
                    "Synthesized ERP Stockout Replenishment for SKU " + poSku + " (Batch " + request.idempotencyKey() + ")",
                    targetWorkflowStatus,
                    resolutionSummary
            );
            workflowRowsUpdated = 1;
        }

        // 6. Synchronize erp_event_ledger
        int ledgerRowsUpdated = jdbcTemplate.update("""
                UPDATE erp_event_ledger
                SET status = ?,
                    orchestration_summary = COALESCE(orchestration_summary, '') || ' | ' || ?
                WHERE batch_id = ?
                  AND sku = ?
                """,
                targetWorkflowStatus,
                resolutionSummary,
                request.idempotencyKey(),
                poSku
        );

        return new HitlWorkflowResumeResponse(
                request.idempotencyKey(),
                request.poId(),
                normalizedDecision,
                poExecutionResult,
                workflowRowsUpdated > 0 ? targetWorkflowStatus : "NO_MATCHING_WORKFLOW_RUN",
                ledgerRowsUpdated > 0 ? targetWorkflowStatus : "NO_MATCHING_ERP_LEDGER",
                processedAt
        );
    }

    @Transactional
    public CorrelatedExecutionTraceDto getCorrelatedExecutionTrace(
            String idempotencyKey,
            RbacGuardService.EnterpriseActorContext actorContext
    ) {
        rbacGuardService.authorizeOrThrow(
                actorContext,
                "VIEW_CORRELATED_AUDIT_TRACE",
                "RUN:" + idempotencyKey,
                Set.of("ROLE_SUPPLY_CHAIN_OPERATOR", "ROLE_PROCUREMENT_MANAGER")
        );

    var mainTraceList =
        jdbcTemplate.query(
            """
               SELECT
                   COALESCE(combined.batch_id, combined.w_idempotency_key, p.idempotency_key) AS corr_key,
                   COALESCE(combined.l_status, 'DIRECT_PROMPT_TRIGGER') AS erp_ledger_status,
                   COALESCE(combined.w_status, 'NOT_RECORDED') AS workflow_status,
                   COALESCE(combined.l_sku, p.sku, 'N/A') AS sku,
                   p.po_id,
                   p.total_amount_inr,
                   COALESCE(p.status, 'NO_PO_CREATED') AS po_status,
                   p.created_at AS po_created_at,
                   combined.w_updated_at AS workflow_updated_at,
                   COALESCE(combined.final_response, combined.orchestration_summary) AS final_response,
                   COALESCE(combined.step_count, 0) AS step_count,
                   COALESCE(combined.max_steps, 5) AS max_steps,
                   COALESCE(combined.total_tokens_used, 0) AS total_tokens_used
               FROM (
                   SELECT
                       l.batch_id, l.status AS l_status, l.sku AS l_sku, l.created_at AS l_created_at, l.orchestration_summary,
                       w.idempotency_key AS w_idempotency_key, w.status AS w_status, w.final_response,\s
                       w.step_count, w.max_steps, w.total_tokens_used, w.created_at AS w_created_at, w.updated_at AS w_updated_at
                   FROM erp_event_ledger l
                   LEFT JOIN agent_workflow_runs w
                       ON w.idempotency_key = l.batch_id
                       OR w.idempotency_key LIKE '%' || l.batch_id || '%'
                       OR (l.sku IS NOT NULL AND w.user_prompt LIKE '%' || l.sku || '%')

                   UNION
                   
                   SELECT
                       l.batch_id, l.status AS l_status, l.sku AS l_sku, l.created_at AS l_created_at, l.orchestration_summary,
                       w.idempotency_key AS w_idempotency_key, w.status AS w_status, w.final_response,\s
                       w.step_count, w.max_steps, w.total_tokens_used, w.created_at AS w_created_at, w.updated_at AS w_updated_at
                   FROM agent_workflow_runs w
                   LEFT JOIN erp_event_ledger l
                       ON w.idempotency_key = l.batch_id
                       OR w.idempotency_key LIKE '%' || l.batch_id || '%'
                       OR (l.sku IS NOT NULL AND w.user_prompt LIKE '%' || l.sku || '%')
                   WHERE l.batch_id IS NULL
               ) combined
              
               LEFT JOIN purchase_orders p
                   ON p.idempotency_key = combined.batch_id
                   OR p.idempotency_key = combined.w_idempotency_key
                   OR (p.sku = COALESCE(combined.l_sku, '') AND p.created_at >= COALESCE(combined.l_created_at, combined.w_created_at))
               WHERE combined.batch_id = ?
                  OR combined.w_idempotency_key = ?
                  OR combined.w_idempotency_key LIKE '%' || ? || '%'
                  OR p.idempotency_key = ?
               ORDER BY combined.w_created_at DESC NULLS LAST, p.created_at DESC NULLS LAST
               LIMIT 1;
                """,
            (rs, rowNum) ->
                new Object[] {
                  rs.getString("corr_key"),
                  rs.getString("erp_ledger_status"),
                  rs.getString("workflow_status"),
                  rs.getString("sku"),
                  rs.getObject("po_id", UUID.class),
                  rs.getBigDecimal("total_amount_inr"),
                  rs.getString("po_status"),
                  rs.getObject("po_created_at", OffsetDateTime.class),
                  rs.getObject("workflow_updated_at", OffsetDateTime.class),
                  rs.getString("final_response"),
                  rs.getInt("step_count"),
                  rs.getInt("max_steps"),
                  rs.getLong("total_tokens_used")
                },
            idempotencyKey,
            idempotencyKey,
            idempotencyKey,
            idempotencyKey);

        if (mainTraceList.isEmpty()) {
            throw new IllegalArgumentException("No workflow or ERP ledger trace found for key: " + idempotencyKey);
        }

        Object[] row = mainTraceList.getFirst();
        UUID poId = (UUID) row[4];
        OffsetDateTime poCreatedAt = (OffsetDateTime) row[7];
        OffsetDateTime workflowUpdatedAt = (OffsetDateTime) row[8];
        int stepCount = (Integer) row[10];
        int maxSteps = (Integer) row[11];
        long workflowTokens = (Long) row[12];

        // 2. Fetch security audit trail entries matching EITHER the batch/idempotencyKey OR the specific PO UUID
        String poTargetResource = poId != null ? "PO:" + poId : "NONE";

        List<SecurityAuditEntryDto> auditEntries = jdbcTemplate.query("""
                SELECT id, correlation_id, actor_id, actor_role, action_name,
                       target_resource, decision, reason, created_at
                FROM agent_security_audit_log
                WHERE correlation_id = ?
                   OR target_resource LIKE ?
                   OR target_resource = ?
                ORDER BY created_at ASC
                """,
                (rs, rowNum) -> new SecurityAuditEntryDto(
                        rs.getLong("id"),
                        rs.getString("correlation_id"),
                        rs.getString("actor_id"),
                        rs.getString("actor_role"),
                        rs.getString("action_name"),
                        rs.getString("target_resource"),
                        rs.getString("decision"),
                        rs.getString("reason"),
                        rs.getObject("created_at", OffsetDateTime.class)
                ),
                idempotencyKey, "%" + idempotencyKey + "%", poTargetResource
        );

        // 3. Extract authoritative HITL approval timestamp and approver from the security audit log
        OffsetDateTime hitlResolvedAt = workflowUpdatedAt;
        String hitlResolvedBy = null;

        for (SecurityAuditEntryDto entry : auditEntries) {
            if ("ALLOWED".equalsIgnoreCase(entry.decision())
                    && ("HITL_WORKFLOW_RESUME".equalsIgnoreCase(entry.actionName())
                    || entry.actionName().contains("APPROVE")
                    || entry.actionName().contains("DECISION"))) {
                hitlResolvedAt = entry.createdAt();
                hitlResolvedBy = entry.actorId();
            }
        }

        // 4. Aggregate token logs from api_cost_logs
        var tokenRow = jdbcTemplate.queryForMap("""
                SELECT
                    COALESCE(SUM(input_tokens), 0) AS in_tokens,
                    COALESCE(SUM(output_tokens), 0) AS out_tokens
                FROM api_cost_logs
                WHERE operation LIKE ?
                """,
                "%" + idempotencyKey + "%"
        );

        long inTokens = ((Number) tokenRow.get("in_tokens")).longValue();
        long outTokens = ((Number) tokenRow.get("out_tokens")).longValue();
        long effectiveTotalTokens = workflowTokens > 0 ? workflowTokens : (inTokens + outTokens);

        BigDecimal estimatedCostInr = BigDecimal.valueOf(effectiveTotalTokens)
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP)
                .multiply(COST_PER_1K_TOKENS_INR)
                .setScale(4, RoundingMode.HALF_UP);

        TokenEconomicsSummaryDto tokenEconomics = new TokenEconomicsSummaryDto(
                stepCount,
                maxSteps,
                effectiveTotalTokens,
                inTokens,
                outTokens,
                estimatedCostInr
        );

        return new CorrelatedExecutionTraceDto(
                (String) row[0],
                (String) row[1],
                (String) row[2],
                (String) row[3],
                poId,
                (BigDecimal) row[5],
                (String) row[6],
                poCreatedAt,
                hitlResolvedAt,
                hitlResolvedBy,
                (String) row[9],
                tokenEconomics,
                auditEntries
        );
    }
}