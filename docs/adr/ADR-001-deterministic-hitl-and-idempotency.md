# ADR-001: Deterministic Code-Level HITL Gate & Net-Pipeline Idempotency in Tool Loops

* **Status:** Accepted
* **Date:** 2026-10-19
* **System:** System 1 — Enterprise P2P & Supply Chain Risk Agent

## 1. Context
Autonomous LLM agents invoking database mutation tools (`createPurchaseOrder`) introduce two severe production risks in enterprise ERP environments:
1. **Probabilistic Financial Compliance:** Relying on system prompts to enforce the ₹50,000 manager approval threshold exposes the ledger to arithmetic hallucinations and prompt injection.
2. **Duplicate Orders & In-Flight Blindness:** Network retries, repeated agent runs, or mid-day stock consumption while an earlier PO is still `PENDING_MANAGER_APPROVAL` can cause naive agents (or naive date-bucket idempotency keys) to double-order inventory or block legitimate incremental orders.

## 2. Decision
We enforce both financial routing and idempotency deterministically inside the Spring AI `@Tool` boundary (`ProcurementTools.java`) backed by PostgreSQL:
1. **Code-Level HITL Gate:** The LLM only passes intent (`sku`, `quantity`). The Java `@Tool` fetches authoritative `unit_price_inr` from PostgreSQL, computes total cost via `BigDecimal`, and deterministically assigns `PENDING_MANAGER_APPROVAL` (> ₹50,000) or `AUTO_APPROVED` (<= ₹50,000).
2. **State-Aware Net Pipeline Inventory:** Both `checkInventoryLevels` and `createPurchaseOrder` compute effective stock as:
   `net_deficit = GREATEST(0, reorder_threshold - (current_stock + in_flight_open_po_qty))`
   If `net_deficit == 0`, the tool acts as an idempotent no-op shield (`SKIPPED_ALREADY_IN_FLIGHT`). If physical stock drops further before an open PO is delivered, only the incremental deficit is ordered.

## 3. Consequences
* **Positive:** 100% immunity against LLM price hallucination, duplicate retry loops, and in-flight over-ordering.
* **Positive:** Same-day legitimate re-orders are automatically unblocked once an in-flight PO transitions to `DELIVERED` or `CANCELLED`.
* **Trade-off:** Requires a `LEFT JOIN` aggregation over active rows in `purchase_orders` during tool execution, which we mitigate via an index on `purchase_orders(sku, status)`.