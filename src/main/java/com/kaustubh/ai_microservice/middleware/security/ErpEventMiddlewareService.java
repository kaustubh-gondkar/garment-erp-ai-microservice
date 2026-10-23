package com.kaustubh.ai_microservice.middleware.security;

import com.kaustubh.ai_microservice.graph.P2pGraphModels;
import com.kaustubh.ai_microservice.graph.P2pGraphOrchestrator;
import com.kaustubh.ai_microservice.tools.ProcurementTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ErpEventMiddlewareService {

    private static final Logger log = LoggerFactory.getLogger(ErpEventMiddlewareService.class);

    private final ProcurementTools procurementTools;
    private final P2pGraphOrchestrator p2pGraphOrchestrator;
    private final RbacGuardService rbacGuardService;
    private final JdbcTemplate jdbcTemplate;

    public ErpEventMiddlewareService(
            ProcurementTools procurementTools,
            P2pGraphOrchestrator p2pGraphOrchestrator,
            RbacGuardService rbacGuardService,
            JdbcTemplate jdbcTemplate
    ) {
        this.procurementTools = procurementTools;
        this.p2pGraphOrchestrator = p2pGraphOrchestrator;
        this.rbacGuardService = rbacGuardService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ErpStockConsumptionPayload(
            String batchId,
            String sku,
            int quantityUsed,
            String preferredVendorId,
            String sourceSystem
    ) {}

    public record ErpWebhookResponseDto(
            String batchId,
            String sku,
            boolean idempotentReplay,
            boolean stockoutTriggeredAgent,
            String status,
            ProcurementTools.InventoryStatus inventoryStatus,
            P2pGraphModels.GraphExecutionResult orchestrationResult,
            Long dlqEventId
    ) {}

    /**
     * 1. Enforces RBAC (ROLE_ERP_WEBHOOK or ROLE_SUPPLY_CHAIN_OPERATOR)
     * 2. Guarantees idempotency on batchId
     * 3. Calls ProcurementTools.recordStockConsumption & checkInventoryStock
     * 4. Automatically triggers P2pGraphOrchestrator if stock <= reorder_threshold
     * 5. Routes failures to agent_dlq_events for deterministic replay
     */
    public ErpWebhookResponseDto processConsumptionWebhook(
            ErpStockConsumptionPayload payload,
            RbacGuardService.EnterpriseActorContext actorContext
    ) {
        rbacGuardService.authorizeOrThrow(
                actorContext,
                "ERP_STOCK_CONSUMPTION_WEBHOOK",
                payload.sku(),
                Set.of(RbacGuardService.ROLE_ERP_SYSTEM, RbacGuardService.ROLE_OPERATOR, RbacGuardService.ROLE_MANAGER)
        );

        // Step A: Idempotency Check & Ledger Lock
        try {
            jdbcTemplate.update("""
                    INSERT INTO erp_event_ledger (batch_id, sku, quantity_used, source_system, actor_id, status)
                    VALUES (?, ?, ?, ?, ?, 'PROCESSING')
                    """,
                    payload.batchId(),
                    payload.sku(),
                    payload.quantityUsed(),
                    payload.sourceSystem() != null ? payload.sourceSystem() : "SAP_WMS",
                    actorContext.actorId()
            );
        } catch (DuplicateKeyException ex) {
            log.info("Idempotent duplicate ERP event detected for batchId={}. Returning cached status.", payload.batchId());
            String existingStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM erp_event_ledger WHERE batch_id = ?",
                    String.class,
                    payload.batchId()
            );
            ProcurementTools.InventoryStatus currentStock = null;
            try {
                currentStock = procurementTools.checkInventoryLevels(payload.sku()).getFirst();
            } catch (Exception e) {
                log.error("SKU NOT PRESENT FOR:{}",payload);
                return new ErpWebhookResponseDto(
                        payload.batchId(),
                        payload.sku(),
                        false,
                        false,
                        "SKU NOT PRESENT",
                        null,
                        null,
                        null
                );
            }
            return new ErpWebhookResponseDto(
                    payload.batchId(),
                    payload.sku(),
                    true,
                    "AGENT_TRIGGERED".equals(existingStatus),
                    existingStatus,
                    currentStock,
                    null,
                    null
            );
        }

// Step B: Deduct Stock in `inventory_items` and Log to `stock_consumption_log`
        try {
            int updatedRows = jdbcTemplate.update("""
                    UPDATE inventory_items
                    SET current_stock = GREATEST(0, current_stock - ?)
                    WHERE sku = ?
                    """,
                    payload.quantityUsed(),
                    payload.sku()
            );

            if (updatedRows == 0) {
                jdbcTemplate.update("DELETE FROM erp_event_ledger WHERE batch_id = ?", payload.batchId());
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "SKU not found in inventory_items table: " + payload.sku()
                );
            }

            jdbcTemplate.update("""
                    INSERT INTO stock_consumption_log (sku, quantity_used, batch_id)
                    VALUES (?, ?, ?)
                    """,
                    payload.sku(),
                    payload.quantityUsed(),
                    payload.batchId()
            );
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            // Prevent failed DB updates from leaving the batch stuck in 'PROCESSING'
            jdbcTemplate.update("DELETE FROM erp_event_ledger WHERE batch_id = ?", payload.batchId());
            throw ex;
        }

        // Fetch updated stock & in-flight PO calculation via existing ProcurementTools bean
         ProcurementTools.InventoryStatus inventoryStatus = null;
     try{   inventoryStatus = procurementTools.checkInventoryLevels(payload.sku()).getFirst();
    } catch (Exception e) {
        log.error("SKU NOT PRESENT FOR:{}",payload);
        return new ErpWebhookResponseDto(
                payload.batchId(),
                payload.sku(),
                false,
                false,
                "SKU NOT PRESENT",
                null,
                null,
                null
        );
    }
        // Step C: Evaluate Stockout using your DTO's needsReorder() OR physical stock breach
        boolean needsRestock = inventoryStatus.needsReorder()
                || (inventoryStatus.currentPhysicalStock() + inventoryStatus.inFlightPoQuantity()) <= inventoryStatus.reorderThreshold();

        if (!needsRestock) {
            jdbcTemplate.update(
                    "UPDATE erp_event_ledger SET status = 'PROCESSED_NO_RESTOCK_NEEDED' WHERE batch_id = ?",
                    payload.batchId()
            );
            return new ErpWebhookResponseDto(
                    payload.batchId(),
                    payload.sku(),
                    false,
                    false,
                    "PROCESSED_NO_RESTOCK_NEEDED",
                    inventoryStatus,
                    null,
                    null
            );
        }

        // Step D: Stockout Risk Detected -> Synthesize Prompt & Trigger P2pGraphOrchestrator
        int recommendedOrderQty = inventoryStatus.netDeficitToOrder() > 0
                ? inventoryStatus.netDeficitToOrder()
                : Math.max(inventoryStatus.reorderThreshold() - inventoryStatus.currentPhysicalStock(), inventoryStatus.reorderThreshold());

        String targetVendor = (payload.preferredVendorId() != null && !payload.preferredVendorId().isBlank())
                ? payload.preferredVendorId()
                : inventoryStatus.preferredVendorId();

        String synthesizedPrompt = String.format(
                "[ERP-AUTO-TRIGGER batch=%s] Stock for SKU %s (%s) dropped to %d physical units (reorder threshold: %d). " +
                        "Verify vendor SLA compliance and procurement policy with vendor %s, and raise a purchase order for %d units of %s.",
                payload.batchId(),
                payload.sku(),
                inventoryStatus.itemName(),
                inventoryStatus.currentPhysicalStock(),
                inventoryStatus.reorderThreshold(),
                targetVendor,
                recommendedOrderQty,
                payload.sku()
        );

        try {
            log.info("Stockout threshold breached for SKU={}. Triggering P2pGraphOrchestrator...", payload.sku());
            P2pGraphModels.GraphExecutionResult graphResult = p2pGraphOrchestrator.executeGraph(payload.batchId, synthesizedPrompt);

            jdbcTemplate.update("""
                    UPDATE erp_event_ledger
                    SET status = 'AGENT_TRIGGERED', orchestration_summary = ?
                    WHERE batch_id = ?
                    """,
                    graphResult.toString(),
                    payload.batchId()
            );

            return new ErpWebhookResponseDto(
                    payload.batchId(),
                    payload.sku(),
                    false,
                    true,
                    "AGENT_TRIGGERED",
                    inventoryStatus,
                    graphResult,
                    null
            );
        } catch (Exception ex) {
            log.error("P2pGraphOrchestrator failed for batchId={}. Routing to agent_dlq_events.", payload.batchId(), ex);
            Long dlqId = jdbcTemplate.queryForObject("""
                    INSERT INTO agent_dlq_events (batch_id, sku, synthesized_prompt, error_type, error_message, status)
                    VALUES (?, ?, ?, ?, ?, 'PENDING_REPLAY')
                    RETURNING id
                    """,
                    Long.class,
                    payload.batchId(),
                    payload.sku(),
                    synthesizedPrompt,
                    ex.getClass().getSimpleName(),
                    ex.getMessage() != null ? ex.getMessage() : "Unknown orchestration failure"
            );

            jdbcTemplate.update(
                    "UPDATE erp_event_ledger SET status = 'DLQ_ROUTED' WHERE batch_id = ?",
                    payload.batchId()
            );

            return new ErpWebhookResponseDto(
                    payload.batchId(),
                    payload.sku(),
                    false,
                    true,
                    "DLQ_ROUTED",
                    inventoryStatus,
                    null,
                    dlqId
            );
        }
    }

    /**
     * RBAC-Protected Human-in-the-Loop (HITL) Manager Decision Endpoint.
     * Strictly requires ROLE_PROCUREMENT_MANAGER before calling ProcurementTools.approveOrRejectPo.
     */
    public String executeManagerPoDecision(
            UUID poId,
            String managerDecision,
            RbacGuardService.EnterpriseActorContext actorContext
    ) {
        rbacGuardService.authorizeOrThrow(
                actorContext,
                "HITL_PO_APPROVAL_DECISION",
                "PO:" + poId,
                Set.of(RbacGuardService.ROLE_MANAGER)
        );
        return procurementTools.confirmHighValuePurchaseOrder(poId, managerDecision);
    }

    /**
     * Replays a failed agent execution from the Dead-Letter Queue using SKIP LOCKED concurrency protection.
     */
    public  P2pGraphModels.GraphExecutionResult replayDlqEvent(Long dlqId, RbacGuardService.EnterpriseActorContext actorContext) {
        rbacGuardService.authorizeOrThrow(
                actorContext,
                "DLQ_EVENT_REPLAY",
                "DLQ:" + dlqId,
                Set.of(RbacGuardService.ROLE_OPERATOR, RbacGuardService.ROLE_MANAGER)
        );

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                UPDATE agent_dlq_events
                SET status = 'REPLAYING', retry_count = retry_count + 1, updated_at = NOW()
                WHERE id = (
                    SELECT id FROM agent_dlq_events
                    WHERE id = ? AND status IN ('PENDING_REPLAY', 'FAILED_REPLAY')
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id, batch_id, synthesized_prompt
                """, dlqId);

        if (rows.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "DLQ event " + dlqId + " is either already resolved, does not exist, or is locked by another worker."
            );
        }

        Map<String, Object> dlqRow = rows.getFirst();
        String batchId = (String) dlqRow.get("batch_id");
        String prompt = (String) dlqRow.get("synthesized_prompt");

        try {
            P2pGraphModels.GraphExecutionResult result = p2pGraphOrchestrator.executeGraph(batchId,prompt);
            jdbcTemplate.update(
                    "UPDATE agent_dlq_events SET status = 'RESOLVED', updated_at = NOW() WHERE id = ?",
                    dlqId
            );
            jdbcTemplate.update(
                    "UPDATE erp_event_ledger SET status = 'AGENT_TRIGGERED', orchestration_summary = ? WHERE batch_id = ?",
                    result.toString(),
                    batchId
            );
            return result;
        } catch (Exception ex) {
            jdbcTemplate.update("""
                    UPDATE agent_dlq_events
                    SET status = 'FAILED_REPLAY', error_message = ?, updated_at = NOW()
                    WHERE id = ?
                    """,
                    ex.getMessage(),
                    dlqId
            );
            throw ex;
        }
    }
}