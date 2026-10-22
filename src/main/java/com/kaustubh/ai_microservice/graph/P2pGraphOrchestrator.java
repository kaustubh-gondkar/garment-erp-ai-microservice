package com.kaustubh.ai_microservice.graph;

import com.kaustubh.ai_microservice.agent.service.P2PAgentService;
import com.kaustubh.ai_microservice.graph.P2pGraphModels.*;
import com.kaustubh.ai_microservice.tools.ComplianceRagTools;
import com.kaustubh.ai_microservice.tools.ProcurementTools;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class P2pGraphOrchestrator {

    private static final Logger log = LogManager.getLogger(P2pGraphOrchestrator.class);
    private static final BigDecimal HITL_THRESHOLD = new BigDecimal("50000.00");

    private final P2pIntentRouter intentRouter;
    private final ProcurementTools procurementTools;
    private final ComplianceRagTools complianceRagTools;
    private final P2PAgentService p2pAgentService; // Reusing existing service

    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public P2pGraphOrchestrator(
            P2pIntentRouter intentRouter,
            ProcurementTools procurementTools,
            ComplianceRagTools complianceRagTools,
            P2PAgentService p2pAgentService
    ) {
        this.intentRouter = intentRouter;
        this.procurementTools = procurementTools;
        this.complianceRagTools = complianceRagTools;
        this.p2pAgentService = p2pAgentService;
    }

    public GraphExecutionResult executeGraph(String conversationId, String userMessage) {
        long graphStart = System.currentTimeMillis();

        long routerStart = System.currentTimeMillis();
        RoutingDecision decision = intentRouter.route(userMessage);
        long routerLatency = System.currentTimeMillis() - routerStart;

        if (decision.promptInjectionDetected() || decision.route() == WorkflowRoute.REJECT_UNSAFE_OR_OFFTOPIC) {
            return new GraphExecutionResult(
                    conversationId,
                    WorkflowRoute.REJECT_UNSAFE_OR_OFFTOPIC,
                    "Request blocked by P2P Security Guardrail: " + decision.routingReason(),
                    routerLatency, 0L, System.currentTimeMillis() - graphStart
            );
        }

        if (decision.route() == WorkflowRoute.FAST_INVENTORY_QUERY) {
            List<ProcurementTools.InventoryStatus> statuses = procurementTools.checkInventoryLevels(decision.skuCode());
            String fastReply = statuses.isEmpty()
                    ? "No inventory record found for SKU: " + decision.skuCode()
                    : "Fast-Path Inventory Status: " + statuses.get(0).toString();

            return new GraphExecutionResult(
                    conversationId,
                    WorkflowRoute.FAST_INVENTORY_QUERY,
                    fastReply,
                    routerLatency, 0L, System.currentTimeMillis() - graphStart
            );
        }

        long fanOutStart = System.currentTimeMillis();

        CompletableFuture<List<ProcurementTools.InventoryStatus>> inventoryFuture = CompletableFuture.supplyAsync(
                () -> procurementTools.checkInventoryLevels(decision.skuCode()),
                virtualThreadExecutor
        ).orTimeout(5, TimeUnit.SECONDS);

        CompletableFuture<String> complianceFuture = CompletableFuture.supplyAsync(
                () -> complianceRagTools.checkCompliancePolicy(userMessage),
                virtualThreadExecutor
        ).orTimeout(8, TimeUnit.SECONDS);

        CompletableFuture.allOf(inventoryFuture, complianceFuture).join();
        List<ProcurementTools.InventoryStatus> inventoryStates = inventoryFuture.join();
        String compliancePolicyResult = complianceFuture.join();
        long fanOutLatency = System.currentTimeMillis() - fanOutStart;

        ProcurementTools.InventoryStatus targetItem = inventoryStates.stream()
                .filter(i -> i.sku().equalsIgnoreCase(decision.skuCode()))
                .findFirst()
                .orElse(inventoryStates.isEmpty() ? null : inventoryStates.get(0));

        int physicalStock = targetItem != null ? targetItem.currentPhysicalStock() : 0;
        int inFlightQty = targetItem != null ? targetItem.inFlightPoQuantity() : 0;
        int netDeficit = targetItem != null ? targetItem.netDeficitToOrder() : 0;

        int qty = decision.requestedQuantity() != null ? decision.requestedQuantity() : Math.max(1, netDeficit);
        BigDecimal unitPrice = targetItem != null ? targetItem.unitPriceInr() : BigDecimal.ZERO;
        BigDecimal estimatedTotal = unitPrice.multiply(BigDecimal.valueOf(qty));
        boolean exceedsHitl = estimatedTotal.compareTo(HITL_THRESHOLD) > 0;

        String structuredContext = String.format("""
            [VERIFIED GRAPH STATE - IN-FLIGHT & INVENTORY AWARENESS]
            1. Inventory & In-Flight Audit:
               - SKU: %s
               - Physical Stock: %s
               - In-Flight Open PO Quantity: %s
               - Net Deficit To Order: %s
               - Requested Qty: %d | Unit Price: ₹%s | Total: ₹%s | Exceeds ₹50k HITL Gate: %s
            2. Vendor SLA / Policy Compliance Pre-Check: %s
            
            User Request: %s
            
            CRITICAL EXECUTION INSTRUCTION:
            - Check In-Flight Open PO Quantity. If sufficient inventory is already in-flight, do not duplicate.
            - Otherwise, you MUST immediately invoke the `createPurchaseOrder` tool with SKU (%s), vendor (%s), quantity (%d), and authoritative amount (₹%s).
            """,
                targetItem != null ? targetItem.sku() : decision.skuCode(),
                physicalStock,
                inFlightQty,
                netDeficit,
                qty,
                unitPrice,
                estimatedTotal,
                exceedsHitl,
                compliancePolicyResult,
                userMessage,
                targetItem != null ? targetItem.sku() : decision.skuCode(),
                targetItem != null ? targetItem.preferredVendorId() : decision.vendorCode(),
                qty,
                estimatedTotal
        );

        // REUSABILITY WIN: Delegate execution cleanly to P2PAgentService
        String finalResponse = p2pAgentService.executeWithStructuredContext(structuredContext, conversationId);

        return new GraphExecutionResult(
                conversationId,
                WorkflowRoute.FULL_PROCUREMENT_DAG,
                finalResponse,
                routerLatency,
                fanOutLatency,
                System.currentTimeMillis() - graphStart
        );
    }
}