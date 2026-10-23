# ADR-005: Enterprise Middleware Integration, RBAC Guardrails, and PostgreSQL DLQ Resilience

- **Status:** Accepted
- **Date:** 2026-10-23
- **System:** System 1 – Enterprise Procure-to-Pay (P2P) & Supply Chain Risk Agent

## 1. Context
Enterprise ERP systems (SAP S/4HANA, Oracle NetSuite) emit high-frequency stock consumption webhooks with at-least-once delivery guarantees. Directly coupling webhook ingestion to multi-step LLM orchestration introduces three production risks:
1. **Duplicate Execution:** Network retries on ERP webhooks can double-deduct stock and generate duplicate Purchase Orders.
2. **DB Connection Pool Exhaustion:** Holding `@Transactional` JDBC connections open during 3–6 second LLM tool-calling loops starves HikariCP under burst load.
3. **Privilege Escalation:** Without deterministic RBAC outside the LLM prompt, an unauthorized operator or prompt-injected payload could invoke `approveOrRejectPo` on POs > ₹50,000.

## 2. Decision
1. **Idempotent Event Ledger (`erp_event_ledger`):** Enforce a unique primary key constraint on `batch_id` at the middleware boundary before invoking `ProcurementTools.recordStockConsumption`.
2. **Post-Consumption Autonomous Trigger & DLQ (`agent_dlq_events`):** Evaluate `current_stock <= reorder_threshold` deterministically. Only invoke `P2pGraphOrchestrator.executeP2pWorkflow` when a stockout threshold is breached. Any LLM timeout or `BoundedLoopAdvisor` circuit-breaker trip is caught and persisted to `agent_dlq_events` with `FOR UPDATE SKIP LOCKED` replay semantics.
3. **Deterministic RBAC & Security Audit (`RbacGuardService`):** Enforce role checks (`ROLE_PROCUREMENT_MANAGER` for HITL approvals) at the Java service layer using an explicit `EnterpriseActorContext` record, logging every `ALLOWED` and `DENIED` decision to `agent_security_audit_log`.

## 3. Consequences
- **Positive:** Zero duplicate POs under webhook retries; zero local Docker memory overhead (PostgreSQL-backed ledger and DLQ); 100% auditable RBAC compliance for > ₹50,000 HITL approvals.
- **Negative / Trade-off:** PostgreSQL-backed DLQ polling handles moderate enterprise throughput (< 500 events/sec); for high-throughput BFSI transaction streams (> 10,000 events/sec), this seam will be upgraded to a dedicated message broker in System 2.