package com.kaustubh.ai_microservice.graph;


import java.math.BigDecimal;
import java.util.List;

public final class P2pGraphModels {

    public enum WorkflowRoute {
        FAST_INVENTORY_QUERY,     // Read-only stock or PO status check
        FULL_PROCUREMENT_DAG,     // Multi-step stock + SLA compliance + PO execution
        REJECT_UNSAFE_OR_OFFTOPIC // Prompt injection or non-P2P chatter
    }

    // Output of Node 1: Fast Guardrail & Intent Router (gemini-2.5-flash-lite)
    public record RoutingDecision(
            WorkflowRoute route,
            String skuCode,
            Integer requestedQuantity,
            String vendorCode,
            boolean promptInjectionDetected,
            String routingReason
    ) {}

    // Output of Node 2A: Deterministic Inventory & Catalog Worker
    public record InventoryNodeOutput(
            String skuCode,
            int currentStock,
            int reorderThreshold,
            BigDecimal catalogUnitPrice,
            BigDecimal estimatedTotalCost,
            boolean requiresHitlApproval
    ) {}

    // Output of Node 2B: Hybrid RAG + Cross-Encoder SLA Compliance Worker
    public record ComplianceNodeOutput(
            String vendorCode,
            boolean slaCompliant,
            List<String> citedPolicyClauses,
            double topRerankerScore
    ) {}

    // Final Fan-In Graph Output returned to REST Client
    public record GraphExecutionResult(
            String conversationId,
            WorkflowRoute executedRoute,
            String agentResponse,
            long routerLatencyMs,
            long parallelFanOutLatencyMs,
            long totalGraphLatencyMs
    ) {}
}