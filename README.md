# Enterprise Procure-to-Pay (P2P) & Supply Chain Risk Agent (System 1)

**Production-Grade Agentic Microservice | Java 21 · Spring Boot 3.3 · Spring AI · Model Context Protocol (MCP) · Neon Serverless PostgreSQL (`pgvector` + `tsvector`)**

---

## 1. Executive Summary (BLUF)

Enterprise supply chains suffer from delayed stockout detection, manual vendor contract verification, and unguarded LLM tool execution risks. **System 1** is an autonomous, event-driven **Procure-to-Pay (P2P) & Supply Chain Risk Agent** engineered for high-reliability enterprise environments:

- **Deterministic Financial Guardrails:** Automatically executes restock purchase orders under **₹50,000** while hard-pausing orders above **₹50,000** in `PENDING_MANAGER_APPROVAL` for Human-in-the-Loop (HITL) manager sign-off (`ADR-001`, `ADR-006`).
- **Hybrid RAG + Cross-Encoder Reranking:** Combines `pgvector` HNSW dense embeddings with PostgreSQL `tsvector` BM25 keyword search via **Reciprocal Rank Fusion (RRF, k=60)** and Cross-Encoder reranking over vendor SLA contracts and procurement policies (`ADR-002`).
- **Bounded Loop & Graph Orchestration:** Replaces open-ended ReAct loops with a deterministic DAG (`P2pGraphOrchestrator`) using Java 21 Virtual Threads for parallel pre-check fan-out, step-count circuit breakers, and token budget enforcement (`ADR-003`, `ADR-004`).
- **Zero-Trust Model Context Protocol (MCP) Topology:** Decouples the Agent Orchestrator (`mcp-client` on `:8080`) from the Database/Tool Execution boundary (`mcp-server` on `:8081` over HTTP/SSE and headless `mcp-stdio`), enforcing cross-JVM AspectJ RBAC auditing (`MCP-EXEC-*`) and automatic fail-closed Dead-Letter Queue (`agent_dlq_events`) routing (`ADR-007` – `ADR-010`).

---

## 2. System Architecture & Cross-JVM MCP Topology

```mermaid
flowchart TB
    subgraph External["Enterprise Producers & Control Plane"]
        ERP["ERP Stock Consumption Webhook<br/>(POST /api/v1/p2p/webhooks/stock-consumption)"]
        MGR["Procurement Manager HITL Resume<br/>(POST /api/v1/p2p/control-plane/hitl/resume)"]
        CLI["@google/gemini-cli / CI Runner<br/>(Headless STDIO Subprocess)"]
    end

    subgraph ClientJVM["Orchestrator JVM (Port 8080 | Profile: mcp-client)"]
        MW["ErpEventMiddlewareService<br/>(Idempotency + DLQ FOR UPDATE SKIP LOCKED)"]
        RBAC_C["RbacGuardService<br/>(Client RBAC Boundary)"]
        GRAPH["P2pGraphOrchestrator<br/>(Java 21 Virtual Thread Fan-Out)"]
        LOOP["BoundedLoopAdvisor & P2PAgentService<br/>(Max Steps + Token Budget + Gemini 2.5 Flash)"]
        ROUTER["P2pMcpToolRouter<br/>(SSE Self-Heal + Fail-Closed Guard)"]
        VERIFIER["P2pMcpContractVerifierService<br/>(Zero-Token JSON-RPC Wire Probe)"]
    end

    subgraph ServerJVM["Tool Execution JVM (Port 8081: mcp-server | Headless: mcp-stdio)"]
        FILTER["McpServerTransportGuardFilter<br/>(Loopback + Optional X-MCP-API-Key)"]
        ASPECT["P2pMcpServerAuditAspect<br/>(AspectJ @Around -> MCP-EXEC-* Audit)"]
        TOOLS_P["ProcurementTools (@Tool)<br/>(checkInventoryLevels, createPurchaseOrder)"]
        TOOLS_C["ComplianceRagTools (@Tool)<br/>(verifyVendorSlaAndProcurementPolicy)"]
    end

    subgraph Storage["Neon Serverless PostgreSQL (Hikari Pool Capped at 3/JVM)"]
        LEDGER[("erp_event_ledger &<br/>agent_workflow_runs")]
        INV[("inventory_items &<br/>purchase_orders")]
        RAG_DB[("compliance_policy_chunks<br/>(pgvector + tsvector RRF k=60)")]
        AUDIT[("agent_security_audit_log &<br/>agent_dlq_events")]
    end

    ERP --> MW
    MGR --> RBAC_C
    MW --> RBAC_C --> GRAPH --> LOOP --> ROUTER
    ROUTER -- "JSON-RPC 2.0 over HTTP/SSE (:8081)" --> FILTER
    VERIFIER -- "Zero-Token Wire Probe" --> FILTER
    CLI -- "JSON-RPC 2.0 over STDIN/STDOUT" --> ASPECT
    FILTER --> ASPECT
    ASPECT --> TOOLS_P & TOOLS_C
    TOOLS_P --> INV
    TOOLS_C --> RAG_DB
    MW & ASPECT & RBAC_C --> AUDIT
    MW --> LEDGER
```

---

## 3. Empirical Latency, Token Economics & Wire Benchmarks

Measured against live **Neon Serverless PostgreSQL** (ap-southeast-1) and **Google AI Studio (`gemini-2.5-flash`)** on a 12 GB RAM dual-JVM setup (`HikariCP max-pool-size=3` per JVM):

| Operation / Execution Path | Transport / Mode | LLM Tokens Used | Empirical Latency | Reliability / Guardrail Behavior |
|---|---|---:|---:|---|
| **MCP Bridge Health Probe** (`/mcp-bridge-status`) | HTTP/SSE `tools/list` | `0` | `~45 – 110 ms` | Detects `:8081` restart & self-heals SSE client |
| **Wire Probe: `checkInventoryLevels`** | JSON-RPC `tools/call` (`:8080` → `:8081`) | `0` | `~754 ms` | Writes `MCP-EXEC-*` `ALLOWED` audit row |
| **Wire Probe: `verifyVendorSlaAndProcurementPolicy`** | JSON-RPC `tools/call` (`pgvector` + `tsvector` RRF + Rerank) | `0` | `~2,171 ms` | Hybrid RRF (`k=60`) + Cross-Encoder scoring |
| **Full E2E Agentic Restock Loop** (Stockout → RAG → PO Creation) | Virtual-Thread Pre-Check + Remote MCP Tool Call | `~1,150 – 1,650` | `~4.8 – 6.4 s` | Enforces ₹50,000 HITL gate & duplicate PO prevention |
| **Chaos Fail-Closed Routing** (`:8081` Unreachable) | Fast-Fail Circuit Breaker | `0` | `< 50 ms` | Persists webhook payload to `agent_dlq_events` (`PENDING_REPLAY`) |

---

## 4. End-to-End Correlated Execution Trace (Sample Output)

Calling `GET /api/v1/p2p/control-plane/trace/{idempotencyKey}` joins `erp_event_ledger`, `agent_workflow_runs`, `purchase_orders`, `api_cost_logs`, and `agent_security_audit_log` (including cross-JVM `MCP-EXEC-*` rows) into a single auditable payload:

```json
{
  "idempotencyKey": "BATCH-2026-10-31-TITANIUM-01",
  "sku": "SKU-TI-ALLOY-001",
  "ledgerStatus": "COMPLETED_AFTER_HITL",
  "workflowStatus": "COMPLETED_AFTER_HITL",
  "poId": "9f8c7b6a-5d4e-4f3a-8b2c-1e0d9a8b7c6d",
  "poStatus": "APPROVED",
  "totalAmountInr": 145000.00,
  "totalTokensUsed": 1420,
  "estimatedCostInr": 0.0312,
  "securityAuditTrail": [
    "ALLOWED: PROCESS_ERP_CONSUMPTION_WEBHOOK on BATCH:BATCH-2026-10-31-TITANIUM-01 by ROLE_ERP_WEBHOOK",
    "ALLOWED: MCP_TOOL_CHECK_INVENTORY on SKU:SKU-TI-ALLOY-001 (correlationId=MCP-EXEC-...)",
    "ALLOWED: MCP_TOOL_VERIFY_VENDOR_SLA on VENDOR:VEND-HAL-AERO-01 (correlationId=MCP-EXEC-...)",
    "ALLOWED: MCP_TOOL_CREATE_PO on SKU:SKU-TI-ALLOY-001 (correlationId=MCP-EXEC-...)",
    "ALLOWED: RESUME_HITL_WORKFLOW on PO:9f8c7b6a-5d4e-4f3a-8b2c-1e0d9a8b7c6d by ROLE_PROCUREMENT_MANAGER"
  ]
}
```

---

## 5. Architecture Decision Records (ADR Index)

All architectural trade-offs are documented in `docs/adr/`:

| ADR | Title | Core Decision & Guardrail Enforced |
|---|---|---|
| **ADR-001** | Transactional `@Tool` Design & Deterministic HITL Gate | Calculate authoritative PO pricing inside PostgreSQL; route POs above ₹50,000 to `PENDING_MANAGER_APPROVAL`; exclude `confirmHighValuePurchaseOrder` from LLM tool surface. |
| **ADR-002** | Hybrid Search (`pgvector` + `tsvector` RRF) & Cross-Encoder Reranking | Combine semantic dense retrieval with exact clause/SKU keyword matching via Reciprocal Rank Fusion (`k=60`) and deterministic reranking. |
| **ADR-003** | Bounded Loop Engineering & Cost Telemetry | Enforce hard step limits, token budgets, and per-call cost logging in `api_cost_logs` via `BoundedLoopAdvisor`. |
| **ADR-004** | Deterministic DAG Orchestration & Virtual-Thread Fan-Out | Pre-compute inventory deficit and vendor SLA compliance concurrently on Java 21 Virtual Threads before invoking Gemini for transactional PO execution. |
| **ADR-005** | Deterministic RBAC, Webhook Idempotency & DLQ Replay | Enforce pre-execution RBAC (`agent_security_audit_log`), `batch_id` idempotency, and concurrent-safe `FOR UPDATE SKIP LOCKED` DLQ replay. |
| **ADR-006** | HITL Lifecycle Coordinator & Unified Correlated Audit Trace | Synchronize state transitions across `purchase_orders`, `agent_workflow_runs`, and `erp_event_ledger` on manager approval/rejection. |
| **ADR-007** | Multi-Profile MCP Server/Client Split & SSE Self-Healing | Extract `@Tool` beans into `:8081` (`mcp-server`), cap HikariCP pools at 3 per JVM for 12 GB RAM hosts, and auto-heal stale SSE transports in `P2pMcpToolRouter`. |
| **ADR-008** | Zero-Trust MCP Server Audit Aspect & Loopback Transport Filter | Intercept every remote tool invocation on `:8081` via AspectJ `@Around` (`P2pMcpServerAuditAspect`) and restrict `/sse` & `/mcp/message` to trusted loopback origins. |
| **ADR-009** | Zero-Token MCP Wire Contract Verifier & Telemetry Endpoint | Programmatically verify schema parity across the 4 exposed tools, assert HITL approval tool exclusion, and benchmark live JSON-RPC round-trip latency with zero LLM token burn. |
| **ADR-010** | Headless `mcp-stdio` Subprocess Transport & Strict STDOUT Framing | Configure zero-banner, file-routed logging (`./logs/mcp-stdio-server.log`) for clean newline-delimited JSON-RPC 2.0 execution under `@google/gemini-cli` and CI harnesses. |

---

## 6. Local Execution Runbook (12 GB RAM Optimized)

### Prerequisites
- **Java 21** & **Maven 3.9+**
- **Environment Variables:** `NEON_DB_URL`, `NEON_DB_USERNAME`, `NEON_DB_PASSWORD`, `GEMINI_API_KEY`

### Mode A: Dual-JVM HTTP/SSE Production Topology (`:8081` + `:8080`)
```bash
# Terminal 1: Start Isolated MCP Server on Port 8081
mvn spring-boot:run -Dspring-boot.run.profiles=mcp-server \
  -Dspring-boot.run.jvmArguments="-Xms256m -Xmx512m"

# Terminal 2: Start P2P Agent Orchestrator (MCP Client) on Port 8080
mvn spring-boot:run -Dspring-boot.run.profiles=mcp-client \
  -Dspring-boot.run.jvmArguments="-Xms256m -Xmx512m"

# Terminal 3: Run Zero-Token Wire Contract & Audit Verification
curl -X POST "http://localhost:8080/api/v1/p2p/control-plane/mcp-contract/verify" \
  -H "X-Actor-Id: sec-auditor-01" \
  -H "X-Actor-Role: ROLE_PROCUREMENT_MANAGER"
```

### Mode B: Headless STDIO Subprocess Harness (`mcp-stdio`)
```bash
# Build executable JAR (skipping tests if DB is remote)
mvn clean package -DskipTests

# Run Automated Python JSON-RPC 2.0 STDIO Verification Harness
python scripts/verify_mcp_stdio.py
```

### Postman Collection
Import `docs/postman/System1-P2P-SupplyChain-MCP.postman_collection.json` into Postman to execute the complete webhook, HITL approval, DLQ chaos replay, and MCP contract verification suite.