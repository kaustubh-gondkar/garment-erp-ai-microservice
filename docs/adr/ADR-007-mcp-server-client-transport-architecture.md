# ADR-007: Dual-Profile Model Context Protocol (MCP) Server/Client Extraction, Self-Healing SSE Transport & Fail-Closed DLQ Routing

* **Status:** Accepted & Postman-Verified
* **Date:** 2026-10-27
* **System:** System 1 — Enterprise Procure-to-Pay (P2P) & Supply Chain Risk Agent
* **Related Components:** `P2pMcpServerConfig`, `P2pMcpToolRouter`, `P2pGraphOrchestrator` (ADR-004), `P2PAgentService`, `ProcurementTools` (ADR-001), `ComplianceRagTools` (ADR-002), `LoopGuardAdvisor` (ADR-003), `ErpEventMiddlewareService` & `RbacGuardService` (ADR-005), `P2pLifecycleCoordinatorService` (ADR-006)

---

## 1. Context & Problem Statement

In Phase 2 (Week 1), System 1 executed all `@Tool` methods (`checkInventoryLevels`, `createPurchaseOrder`, `confirmHighValuePurchaseOrder`, and `checkCompliancePolicy`) in-process within a single monolithic Spring Boot JVM via local reflection.

To achieve enterprise Forward Deployed Engineer (FDE) production standards, tool execution must be decoupled from orchestration via the **Model Context Protocol (MCP)** so downstream microservice orchestrators and CLI/desktop agents can discover and invoke P2P database and Hybrid RAG capabilities over a standardized wire protocol without duplicating SQL schemas or business rules.

We had to solve five concrete engineering challenges during extraction:
1. **Strict Zero-Rewrite Reuse Rule:** Existing Postman-verified domain logic in `ProcurementTools` (`ADR-001`), `ComplianceRagTools` (`ADR-002`), and deterministic manager approval calls in `P2pLifecycleCoordinatorService` (`ADR-006`) must remain untouched.
2. **12 GB Local RAM & Neon DB Connection Budget:** Running two Spring Boot JVMs locally alongside IDE/DB tooling must stay under ~550 MB combined heap and avoid exhausting serverless PostgreSQL connection limits.
3. **LLM Function Name Length & Prefix Mismatch:** Spring AI's `SyncMcpToolCallbackProvider` prefixes remote tool names with the client and server identifiers (e.g., `p2p_graph_orchestrator_client_p2p_supply_chain_mcp_server_createPurchaseOrder`, 77 chars), which exceeds Gemini's 64-character tool name constraint and breaks system prompts referencing `createPurchaseOrder`.
4. **Stale SSE Socket Recovery:** If the MCP Server (`:8081`) restarts, the MCP Client (`:8080`) holds a broken `HttpClientSseClientTransport` stream (`EOFException`) and fails on subsequent requests unless it can self-heal without a client JVM restart.
5. **Fail-Closed Fault Isolation to PostgreSQL DLQ:** If the MCP Server (`:8081`) is unreachable during an ERP stockout webhook, the client must **never** silently fall back to local execution or drop the event; it must fail fast so `ErpEventMiddlewareService` routes the batch to `agent_dlq_events` (`status = PENDING_REPLAY`).

---

## 2. Decision

We implemented a **Dual-Profile Single-Repository MCP Architecture** using `spring-ai-starter-mcp-server-webmvc` and `spring-ai-starter-mcp-client`:

1. **Isolated HTTP/SSE MCP Server (`--spring.profiles.active=mcp-server` on Port `8081`):**
    * `P2pMcpServerConfig` exposes `ProcurementTools` and `ComplianceRagTools` via `MethodToolCallbackProvider.builder().toolObjects(...).build()`.
    * Exposes `GET /sse` and `POST /mcp/message` for JSON-RPC 2.0 transport plus `GET /mcp/diagnostics/tools` for schema inspection.
2. **Orchestrator & MCP Client Host (`--spring.profiles.active=mcp-client` on Port `8080`):**
    * `P2pMcpToolRouter` manages remote tool discovery, strips long client/server prefixes from `ToolDefinition` names via delegating `ToolCallback` wrappers, self-heals broken SSE connections by instantiating a fresh `McpSyncClient` over `HttpClientSseClientTransport` when `client.listTools()` throws an `IOException`/`EOFException`, and enforces fail-closed execution.
    * `P2pGraphOrchestrator` executes a fail-fast `p2pMcpToolRouter.resolveActiveToolCallbacks()` probe at the start of `executeGraph()`, runs fan-out pre-checks, and passes `activeMcpCallbacks` into `P2PAgentService.executeWithStructuredContext(...)`.
3. **Headless STDIO Subprocess Profile (`--spring.profiles.active=mcp-stdio`):**
    * Disables the embedded web server (`web-application-type: none`), disables the Spring ASCII banner (`banner-mode: off`), silences console `stdout`, and routes logs to `logs/p2p-mcp-stdio-server.log` so CLI hosts (`@google/gemini-cli`, Claude Desktop) can communicate over `stdin`/`stdout` pipes without JSON-RPC frame corruption.

---

## 3. Transport Trade-Off Matrix: HTTP/SSE vs. STDIO

| Architectural Dimension | HTTP/SSE (`mcp-server` on Port `8081`) | STDIO (`mcp-stdio` Headless Pipe) | System 1 Production Role |
| :--- | :--- | :--- | :--- |
| **Communication Channel** | Persistent `GET /sse` stream + `POST /mcp/message?sessionId=...` | OS parent-child `stdin` / `stdout` pipes | **HTTP/SSE** for microservice orchestration (`:8080` $\leftrightarrow$ `:8081`) |
| **JVM Memory Footprint** | ~260 MB per JVM (`-Xms128m -Xmx256m -XX:TieredStopAtLevel=1`) | ~150 MB (no Tomcat thread pool) | Both fit comfortably within our 12 GB RAM constraint |
| **Multi-Client Concurrency** | Concurrent client sessions multiplexed by `sessionId` | 1:1 with the parent process that spawned the JAR | **HTTP/SSE** required for concurrent ERP webhooks |
| **50x Scale Load Balancing** | Requires Layer-7 `sessionId` sticky routing or Redis Pub/Sub backplane | Local machine only; not network load-balanceable | Documented L7 session affinity for K8s ingress |
| **Fault Isolation & Observability** | 12ms fail-fast connection probe routes outages to `agent_dlq_events` | Parent process manages child lifecycle via OS exit codes | **HTTP/SSE** verified end-to-end with `agent_dlq_events` replay |

---

## 4. Verification & Consequences

* **Verified Test Case 3 (Healthy MCP Execution):** Webhook on `:8080` probes `:8081`, discovers 4 tools (`checkInventoryLevels`, `createPurchaseOrder`, `confirmHighValuePurchaseOrder`, `checkCompliancePolicy`), and executes `createPurchaseOrder` on Port `8081` to create a `PENDING_MANAGER_APPROVAL` PO for orders greater than ₹50,000.
* **Verified Test Case 4 (Transport Outage & Self-Healing DLQ Replay):** Killing Port `8081` causes `P2pMcpToolRouter` on Port `8080` to fail fast in 12ms (`java.net.ConnectException`), routing `BATCH-2026-10-24-E2E-021` into `agent_dlq_events` (`status = PENDING_REPLAY`, `retry_count = 0`). Restarting Port `8081` triggers `P2pMcpToolRouter`'s self-healing SSE handshake and replays the DLQ event to `RESOLVED`.