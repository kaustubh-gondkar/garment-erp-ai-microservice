# ADR-004: Deterministic Multi-Agent DAG Orchestration, Model Routing, and Sliding-Window Memory

- **Status:** Accepted
- **Date:** 2026-10-22
- **System:** System 1 — Enterprise P2P & Supply Chain Risk Agent

## 1. Context
In Days 1–3, the P2P Supply Chain Agent (`P2PAgentService`) operated as a single-model (`gemini-2.5-flash`) sequential ReAct loop protected by a `CallAdvisor` circuit breaker (`LoopGuardAdvisor`). While effective for correctness, executing intent classification, catalog/in-flight stock checks, and compliance RAG sequentially introduced avoidable round-trip latency and spent `gemini-2.5-flash` tokens on simple read-only queries or off-topic/injection attempts. Additionally, stateless REST requests prevented multi-turn procurement adjustments.

## 2. Decision
We evolved the orchestration layer by introducing `P2pGraphOrchestrator` while strictly reusing our battle-tested domain tools (`ProcurementTools`, `ComplianceRagTools`) and execution service (`P2PAgentService`):

1. **Tiered Model Routing (Router Node):** Introduce a front-door `P2pIntentRouter` using `gemini-2.5-flash-lite` (`temperature: 0.0`) with structured output (`RoutingDecision` Java 21 `record`) to classify requests into `REJECT_UNSAFE_OR_OFFTOPIC`, `FAST_INVENTORY_QUERY`, or `FULL_PROCUREMENT_DAG`.
2. **Parallel Fan-Out / Fan-In on Java 21 Virtual Threads:** For `FULL_PROCUREMENT_DAG`, execute `ProcurementTools.checkInventoryLevels()` (computing physical stock, in-flight open PO quantities, and net deficit per ADR-001) and `ComplianceRagTools.checkCompliancePolicy()` concurrently via `CompletableFuture.supplyAsync(..., Executors.newVirtualThreadPerTaskExecutor())` with hard 5s/8s timeouts (~400–480ms parallel execution).
3. **DRY Synthesis Delegation & Transaction Isolation:** Inject the verified fan-in state (`[VERIFIED GRAPH STATE - IN-FLIGHT & INVENTORY AWARENESS]`) into `P2PAgentService` (`gemini-2.5-flash`) for final transactional tool execution. Keep `@Transactional` strictly scoped to `ProcurementTools.createPurchaseOrder` and `confirmHighValuePurchaseOrder` rather than across the DAG orchestrator to prevent HikariCP connection starvation during LLM network I/O.
4. **PostgreSQL Sliding-Window Memory:** Implement `JdbcSlidingWindowChatMemory` (Spring AI 1.1.6 `ChatMemory` contract) backed by `p2p_chat_memory` (indexed on `conversation_id, created_at DESC`), injecting only the last $N=6$ messages per turn via `MessageChatMemoryAdvisor` to bound token growth.

## 3. Consequences
- **Positive:** Cuts pre-check retrieval latency to ~450ms via Virtual Thread parallel fan-out; blocks prompt injections and serves fast-path stock queries without invoking `gemini-2.5-flash`; preserves ADR-001 in-flight duplicate order protection without duplicating tool logic; enables multi-turn conversational context safely.
- **Negative / Trade-offs:** Slightly higher orchestration coordination complexity compared to a single-agent prompt; mitigated by immutable Java 21 `record` state contracts, DRY delegation to `P2PAgentService`, and per-node timeout boundaries.