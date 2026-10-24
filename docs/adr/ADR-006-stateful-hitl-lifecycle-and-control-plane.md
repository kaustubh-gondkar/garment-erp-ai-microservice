# ADR-006: Stateful HITL Resumption, Correlated Multi-Table Audit Control Plane & Cross-SKU Integrity Guard

- **Status:** Accepted
- **Date:** 2026-10-24
- **System:** System 1 – Enterprise Procure-to-Pay (P2P) & Supply Chain Risk Agent

## Context
Across ADR-001 through ADR-005, we implemented transactional `@Tool` methods (`ProcurementTools`), Hybrid RRF + Cross-Encoder RAG (`ComplianceRagService`), bounded agent loops (`BoundedLoopAdvisor`), a virtual-thread DAG (`P2pGraphOrchestrator`), and RBAC/ERP webhook middleware (`ErpEventMiddlewareService`).

When a purchase order exceeds ₹50,000, `ProcurementTools.createPurchaseOrder` halts autonomous settlement and persists the PO in `PENDING_MANAGER_APPROVAL`. However, full enterprise lifecycle closure required:
1. Resuming paused workflows deterministically when a Procurement Manager approves or rejects a PO without re-invoking the LLM or duplicating existing bean logic.
2. Synchronizing terminal state across `purchase_orders`, `agent_workflow_runs`, and `erp_event_ledger` while preventing Cross-SKU contamination and multi-row historical update blast radiuses.
3. Providing a single SOX/RBI-ready audit trace endpoint that exposes `poCreatedAt`, `hitlResolvedAt`, `hitlResolvedBy`, token economics (`api_cost_logs`), and RBAC decisions (`agent_security_audit_log`).

## Decision
1. **Strict Bean Composition (Zero Logic Duplication):** `P2pLifecycleCoordinatorService` constructor-injects `RbacGuardService`, `ErpEventMiddlewareService`, `ProcurementTools`, `ComplianceRagService`, and `P2pGraphOrchestrator`, delegating all RBAC checks, PO state transitions, and `FOR UPDATE SKIP LOCKED` DLQ replays to existing verified methods.
2. **Zero-Token Deterministic HITL Resumption:** Manager approval via `POST /api/v1/p2p/control-plane/hitl/resume` invokes `ProcurementTools.confirmHighValuePurchaseOrder` (or `ErpEventMiddlewareService.executeManagerPoDecision` for rejections) and transitions `agent_workflow_runs` and `erp_event_ledger` to `COMPLETED_AFTER_HITL` / `REJECTED_BY_MANAGER` with 0 additional LLM tokens consumed.
3. **Cross-SKU Contamination Guard & Blast-Radius Isolation:**
    - Before updating `erp_event_ledger`, the service verifies that `purchase_orders.sku` matches `erp_event_ledger.sku` for the supplied `idempotencyKey`, throwing an `IllegalArgumentException` on mismatch.
    - `agent_workflow_runs` updates are constrained via a deterministic `WHERE run_id = (SELECT run_id ... ORDER BY created_at DESC LIMIT 1)` subquery, with an automatic `INSERT` backfill when a workflow originated from `P2pGraphOrchestrator` without a pre-existing loop row.
4. **Read-Write Transaction Semantics for Audited Reads:** `getCorrelatedExecutionTrace` runs inside a standard `@Transactional` boundary (omitting `readOnly = true`) because `RbacGuardService.authorizeOrThrow` executes an immutable `INSERT INTO agent_security_audit_log` on every trace inspection.
5. **First-Class Compliance Timestamps:** `CorrelatedExecutionTraceDto` surfaces `poCreatedAt`, `hitlResolvedAt`, and `hitlResolvedBy` by correlating `agent_security_audit_log` across both `correlation_id = idempotencyKey` and `target_resource = 'PO:' || poId`.

## Consequences
- **Positive:** 100% reuse of ADR-001 through ADR-005 beans; zero LLM token burn or hallucination risk during post-approval settlement; strict Cross-SKU data integrity; complete auditability of creation-to-approval turnaround latency.
- **Trade-off:** Audited `GET` trace calls incur a lightweight single-row `INSERT` into `agent_security_audit_log` to satisfy strict access-audit compliance.