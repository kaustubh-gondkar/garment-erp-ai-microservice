# ADR-003: Bounded ReAct Loop Governance, Authoritative Catalog Validation, and Zero-Token HITL State Resumption

* **Status:** Accepted
* **Date:** 2026-10-21
* **System:** System 1 — Enterprise Procure-to-Pay (P2P) & Supply Chain Risk Agent
* **Components:** `LoopGuardAdvisor`, `P2PAgentOrchestrator`, `ProcurementTools`, `ComplianceRagTools`

---

## 1. Context and Problem Statement

When deploying an autonomous tool-calling LLM agent (`gemini-2.5-flash` via Spring AI) over transactional enterprise ERP tables (`purchase_orders`, `inventory_items`, `compliance_policies`), four production risks emerge:

1. **Unbounded Loop & Quota Exhaustion:** Adversarial prompts or ambiguous compliance queries can trap the ReAct agent in multi-turn tool-calling cycles, exhausting token budgets and saturating Neon DB connection pools.
2. **Prompt Price & SKU Spoofing:** If `@Tool` methods blindly trust unit prices or SKU names extracted by the LLM from untrusted user prompts, users can inject phantom SKUs or manipulate order totals to bypass approval gates.
3. **Stale Failure Cache Poisoning:** Naive idempotency caching stores transient or master-data validation failures (`FAILED`, `TERMINATED_STEP_LIMIT`) permanently, blocking legitimate retries even after an administrator seeds missing inventory records.
4. **Token Burn on Human-in-the-Loop (HITL) Resumption:** High-value purchase orders exceeding ₹50,000 require asynchronous manager approval hours after the initial HTTP request closes. Re-invoking the LLM to process a manager's binary approval wastes tokens and introduces non-deterministic tool re-execution.

---

## 2. Decision Drivers

* **Strict Cost & Latency Predictability:** Every agent workflow must be bounded by deterministic step and token ceilings.
* **Zero Trust on LLM Tool Inputs:** Pricing and inventory existence must be enforced authoritatively via PostgreSQL ACID lookups, never via prompt parameters.
* **Lightweight Infrastructure Footprint:** Avoid heavy external orchestration clusters (Temporal, Camunda) on a 12 GB RAM footprint while maintaining full auditability in Serverless PostgreSQL (Neon DB).

---

## 3. Considered Options

* **Option A: Unconstrained Spring AI `@Tool` Execution (Default)** — Rely solely on system prompt instructions to limit tool calls and validate prices. *Rejected: System prompts are probabilistic and vulnerable to prompt injection and hallucinated PO IDs.*
* **Option B: External Workflow Cluster (Temporal / Camunda)** — Run an external workflow state cluster for step tracking and HITL timers. *Rejected: Introduces heavy local Docker memory overhead and operational complexity disproportionate to a single-service P2P agent.*
* **Option C: Spring AI `CallAdvisor` Circuit Breaker + PostgreSQL State Machine (Selected)** — Govern the ReAct loop in-process via `LoopGuardAdvisor` and persist workflow telemetry and HITL transitions directly in Neon DB (`agent_workflow_runs` and `purchase_orders`).

---

## 4. Decision Outcome

We implemented a four-layer **Agentic Harness** inside `com.kaustubh.ai_microservice`:

### 4.1. Advisor-Level Circuit Breaking (`LoopGuardAdvisor implements CallAdvisor`)
* Registered at `Ordered.HIGHEST_PRECEDENCE` on the `ChatClient` advisor chain.
* Enforces a hard ceiling of **`MAX_ALLOWED_STEPS = 5`** and **`MAX_TOKEN_BUDGET = 8000`** per `WORKFLOW_RUN_ID`.
* Trips `AgentLoopTerminationException` immediately upon violation, recording `status = 'TERMINATED_STEP_LIMIT'` in `agent_workflow_runs`.

### 4.2. Authoritative Catalog Lookup & Anti-Spoofing (`ProcurementTools.createPurchaseOrder`)
* Queries `inventory_items` by `sku` prior to PO insertion.
* Computes `authoritativeTotal = unit_price_inr * quantity` inside PostgreSQL/Java `BigDecimal`, overriding any spoofed or miscalculated amount passed by the LLM prompt.
* Enforces the deterministic `INR 50,000` gate: orders `<= 50,000` commit as `AUTO_APPROVED`; orders `> 50,000` commit as `PENDING_MANAGER_APPROVAL` and surface `AWAITING_HUMAN_APPROVAL` to the orchestrator.

### 4.3. Selective Idempotency Caching (`P2PAgentOrchestrator.findExistingWorkflow`)
* Deduplicates incoming requests via `idempotency_key` on `agent_workflow_runs`.
* Caches and returns terminal success states (`COMPLETED`, `AWAITING_HUMAN_APPROVAL`, `APPROVED_RESUMED`), but explicitly bypasses cache on `FAILED` and `TERMINATED_STEP_LIMIT` runs so administrative fixes to master data (`inventory_items`, `vendors`) can be retried cleanly.

### 4.4. Zero-Token Deterministic HITL Resumption (`confirmHighValuePurchaseOrder`)
* Exposes `POST /api/v1/p2p/workflows/{runId}/approve` (and `/api/v1/procurement/orders/{poId}/approve`).
* Acquires a pessimistic row lock (`SELECT ... FROM purchase_orders WHERE po_id = ? FOR UPDATE`), validates `status == 'PENDING_MANAGER_APPROVAL'`, and transitions the order to `APPROVED` and the workflow to `APPROVED_RESUMED` with **0 LLM tokens consumed**.

---

## 5. Consequences

### Positive
* **Predictable Unit Economics:** Worst-case token burn is capped at 8,000 tokens per run (`<= $0.002` on `gemini-2.5-flash`), verified via adversarial 10-category prompt injection testing (tripped cleanly at step 1 / 10,446 tokens).
* **Zero Hallucinated Financial Mutations:** Database-enforced catalog pricing prevents prompt-based price manipulation.
* **Instant Auditability:** Operations teams can query P95 token usage, step counts, and HITL escalation rates directly via PostgreSQL window functions over `agent_workflow_runs`.

### Negative / Trade-offs
* In-memory `ConcurrentHashMap` step tracking inside `LoopGuardAdvisor` is scoped to a single JVM instance. Scaling horizontally to multiple worker pods (50x scale target) will require moving the step/token counters to Redis or passing state inside `ChatClientRequest.context()`.