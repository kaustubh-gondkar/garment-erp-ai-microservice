SYSTEM CONTEXT:
- Stack: Java 21, Spring Boot 3.x, Spring AI, Neon DB (PostgreSQL + pgvector), Google AI Studio (gemini-2.5-flash).
- Active Project: System 1 – Enterprise Procure-to-Pay (P2P) & Supply Chain Risk Agent.
- STRICT REUSE RULE: Never rewrite existing DB queries, DTOs, or business logic below. Constructor-inject existing beans and call their public methods.

DB SCHEMA SUMMARY:
|table_name|all_columns|
|----------|-----------|
|agent_dlq_events|id, batch_id, sku, synthesized_prompt, error_type, error_message, retry_count, status, created_at, updated_at|
|agent_security_audit_log|id, correlation_id, actor_id, actor_role, action_name, target_resource, decision, reason, created_at|
|agent_workflow_runs|run_id, idempotency_key, user_prompt, status, step_count, max_steps, total_tokens_used, pending_tool_payload, final_response, created_at, updated_at|
|api_cost_logs|id, input_tokens, operation, output_tokens, timestamp|
|compliance_policies|policy_id, title, policy_text, created_at|
|compliance_policy_chunks|chunk_id, doc_code, vendor_id, category, section_title, content, embedding, fts_doc, created_at|
|erp_event_ledger|batch_id, sku, quantity_used, source_system, actor_id, status, orchestration_summary, created_at|
|inventory_items|sku, item_name, current_stock, reorder_threshold, unit_price_inr, preferred_vendor_id|
|materials|id, material_name, quantity, unit|
|p2p_chat_memory|id, conversation_id, message_type, content, created_at|
|purchase_orders|po_id, sku, vendor_id, quantity, total_amount_inr, status, created_at, idempotency_key|
|vector_store|id, content, metadata, embedding|
|vendors|vendor_id, vendor_name, status, created_at|
|stock_consumption_log|id, sku, quantity_used, batch_id, consumed_at|



EXISTING POSTMAN-VERIFIED BEANS & SIGNATURES (DO NOT REWRITE):
1. ProcurementTools (@Component) [ADR-001]:
    - public List<InventoryStatus> checkInventoryLevels
    -   @Tool(description = "Creates a purchase order after validating SKU against master inventory and calculating authoritative pricing from the database.")
        @Transactional
        public String createPurchaseOrder(String sku, String vendorId, int quantity, BigDecimal userProvidedAmountInr) {
        * Contract: Already enforces in-flight duplicate PO prevention and > ₹50,000 PENDING_MANAGER_APPROVAL HITL gate.
    - public String confirmHighValuePurchaseOrder(UUID poId, String approverId) {

2. ComplianceRagTools / ComplianceTools (@Component) [ADR-002]:
    -  public List<RerankedPolicyView> verifyVendorSlaAndProcurementPolicy(String vendorId)
        * Contract: Runs pgvector + tsvector RRF (k=60) hybrid search + Cross-Encoder reranking.

3. BoundedLoopAdvisor / AgentLoopService (@Component) [ADR-003]:
    - Enforces max step count, circuit breaker, and token budget on ChatClient tool calls.

4. P2pGraphOrchestrator (@Service) [ADR-004]:
    -  public WorkflowExecutionResult executeWorkflow(String idempotencyKey, String userPrompt) {
        * Contract: Composes Inventory Triage Node -> Conditional Edge -> Compliance RAG Node -> ProcurementTools Execution Node.

5. RbacGuardService (@Service) & ErpEventMiddlewareService (@Service) [ADR-005]:
    - public void authorizeOrThrow(EnterpriseActorContext ctx, String actionName, String targetResource, Set<String> allowedRoles)
      Contract: Enforces deterministic RBAC (ROLE_ERP_WEBHOOK, ROLE_SUPPLY_CHAIN_OPERATOR, ROLE_PROCUREMENT_MANAGER) and writes an immutable audit log to agent_security_audit_log before tool execution.
    - public ErpWebhookResponseDto processConsumptionWebhook(ErpStockConsumptionPayload payload, EnterpriseActorContext actorContext)
      Contract: Enforces batch_id idempotency via erp_event_ledger, deducts stock in inventory_items, checks InventoryStatusDto.needsReorder(), auto-triggers P2pGraphOrchestrator on stockout, and routes failures to agent_dlq_events.
    - public PurchaseOrderDto executeManagerPoDecision(UUID/String poId, String managerDecision, EnterpriseActorContext actorContext)
      Contract: Requires ROLE_PROCUREMENT_MANAGER before delegating to ProcurementTools.approveOrRejectPo.
    - public OrchestrationResultDto replayDlqEvent(Long dlqId, EnterpriseActorContext actorContext)
      Contract: Uses PostgreSQL FOR UPDATE SKIP LOCKED to safely replay failed agent runs from agent_dlq_events.

6. P2pLifecycleCoordinatorService (@Service) & P2pControlPlaneController (@RestController) [ADR-006]:
- public HitlWorkflowResumeResponse resumePausedHitlWorkflow(HitlWorkflowResumeRequest request, RbacGuardService.EnterpriseActorContext actorContext)
  Contract: Enforces ROLE_PROCUREMENT_MANAGER via RbacGuardService, validates Cross-SKU integrity between purchase_orders and erp_event_ledger, delegates PO approval/rejection to ProcurementTools.confirmHighValuePurchaseOrder / ErpEventMiddlewareService.executeManagerPoDecision, and synchronizes agent_workflow_runs (LIMIT 1 + backfill upsert) and erp_event_ledger to COMPLETED_AFTER_HITL / REJECTED_BY_MANAGER.
- public CorrelatedExecutionTraceDto getCorrelatedExecutionTrace(String idempotencyKey, RbacGuardService.EnterpriseActorContext actorContext)
  Contract: Runs inside read-write @Transactional (to permit RbacGuardService audit INSERT), joining erp_event_ledger, agent_workflow_runs, purchase_orders, api_cost_logs, and agent_security_audit_log (matched by correlation_id and PO:<poId>) to return full status, token cost in INR, poCreatedAt, hitlResolvedAt, and hitlResolvedBy.
- public DlqBatchSweepResultDto sweepPendingDlqEvents(int batchLimit, RbacGuardService.EnterpriseActorContext actorContext)
  Contract: Queries retryable agent_dlq_events (retry_count < 3) and delegates each row to ErpEventMiddlewareService.replayDlqEvent.
- public CorrelatedExecutionTraceDto getCorrelatedExecutionTrace(String idempotencyKey, RbacGuardService.EnterpriseActorContext actorContext)
  Contract: Runs inside read-write @Transactional (to permit RbacGuardService audit INSERT), joining erp_event_ledger, agent_workflow_runs, purchase_orders, api_cost_logs, and agent_security_audit_log (matched by correlation_id and PO:<poId>) to return full status, token cost in INR, poCreatedAt, hitlResolvedAt, and hitlResolvedBy. If no trace matches idempotencyKey, logs a warning and returns a structured TRACE_NOT_FOUND DTO instead of throwing an unhandled RuntimeException (preventing @Transactional rollback of the VIEW_CORRELATED_AUDIT_TRACE security audit row).

4. P2pGraphOrchestrator (@Service) & P2PAgentService (@Service) [ADR-004 / ADR-007]:
- public GraphExecutionResult executeGraph(String conversationId, String userMessage)
  Contract: Probes remote MCP Server (:8081) via P2pMcpToolRouter.resolveActiveToolCallbacks() at graph start (failing fast to agent_dlq_events if :8081 is unreachable), runs P2pIntentRouter + virtual-thread fan-out pre-checks (checkInventoryLevels + checkCompliancePolicy), computes authoritative BigDecimal net deficit & ₹50,000 HITL threshold context, and delegates to P2PAgentService.executeWithStructuredContext(structuredContext, conversationId, activeMcpCallbacks).
- public String executeWithStructuredContext(String structuredContext, String conversationId, ToolCallback[] activeMcpCallbacks)
  Contract: Attaches remote MCP ToolCallbacks and conversation_id memory advisor to ChatClient so Gemini executes createPurchaseOrder remotely on Port 8081.

7. P2pMcpServerConfig (@Configuration) & P2pMcpToolRouter (@Component) [ADR-007]:
- Profiles & Ports:
    * `mcp-server` (Port 8081): Runs `spring-ai-starter-mcp-server-webmvc` exposing `ProcurementTools` and `ComplianceRagTools` over HTTP/SSE (`GET /sse`, `POST /mcp/message`) and `GET /mcp/diagnostics/tools` with Hikari pool capped at 3.
    * `mcp-client` (Port 8080): Runs `spring-ai-starter-mcp-client` connected to `http://localhost:8081` with Hikari pool capped at 3.
    * `mcp-stdio` (Headless): Runs `web-application-type: none`, `banner-mode: off`, `stdio: true` for `@google/gemini-cli` and Claude Desktop subprocess execution.
- public ToolCallbackProvider p2pEnterpriseMcpToolCallbackProvider(ProcurementTools procurementTools, ComplianceRagTools complianceRagTools)
  Contract: Exposes existing @Tool methods (checkInventoryLevels, createPurchaseOrder, confirmHighValuePurchaseOrder, checkCompliancePolicy) via MethodToolCallbackProvider without rewriting domain code.
- public synchronized ToolCallback[] resolveActiveToolCallbacks()
  Contract: Probes Port 8081 via McpSyncClient.listTools(), self-heals stale/EOF SSE streams by instantiating a fresh HttpClientSseClientTransport connection when Port 8081 restarts, strips Spring AI's client/server prefix so Gemini receives exact tool names, and fails closed (throwing IllegalStateException) when Port 8081 is down so ErpEventMiddlewareService routes failed webhooks into agent_dlq_events (status = PENDING_REPLAY).

8. P2pMcpServerAuditAspect (@Aspect, @Component) & McpServerTransportGuardFilter (@Component) [ADR-008]:
- Profiles: `mcp-server`, `mcp-stdio` (Port 8081 / Headless STDIO)
- public Object enforceMcpServerAuditBoundary(ProceedingJoinPoint joinPoint)
  Contract: Intercepts all remote @Tool executions (`createPurchaseOrder`, `confirmHighValuePurchaseOrder`, `verifyVendorSlaAndProcurementPolicy`, `checkInventoryLevels`) on Port 8081 via AspectJ `@Around`. Dynamically maps tool signature to target resource (`SKU:<sku>`, `PO:<poId>`, `VENDOR:<id>`), generates correlation ID (`MCP-EXEC-<uuid>`), and delegates to `RbacGuardService.authorizeOrThrow(...)` to write immutable `ALLOWED`/`BLOCKED` records into `agent_security_audit_log` before domain code runs.
- McpServerTransportGuardFilter (OncePerRequestFilter):
  Contract: Restricts `/sse` and `/mcp/message` to trusted loopback addresses (`127.0.0.1`, `::1`), provides optional `X-MCP-API-Key` validation via `p2p.mcp.security.require-api-key`, and maintains atomic counters for SSE handshakes and JSON-RPC tool messages.
 
- P2pMcpBridgeDiagnosticsController (@RestController) [ADR-008]:
- Profile: `mcp-client` (Port 8080)
- GET /api/v1/p2p/control-plane/mcp-bridge-status
  Contract: Invokes `P2pMcpToolRouter.resolveActiveToolCallbacks()` to probe Port 8081. Returns 200 OK with `pingLatencyMs`, `registeredToolCount: 4`, tool schemas, and `failClosedDlqGuard: "ARMED"`. If Port 8081 is unreachable, returns 503 SERVICE_UNAVAILABLE (`DOWN_FAIL_CLOSED_TO_DLQ`) without crashing the orchestrator.


9. P2pMcpContractVerifierService (@Service) & P2pMcpContractController (@RestController) [ADR-009]:
- Profile: `mcp-client` (Port 8080)
- public McpContractVerificationReport verifyRemoteMcpContract(String sampleSku, String sampleVendorId, RbacGuardService.EnterpriseActorContext actorContext)
  Contract: Enforces client-side RBAC (`VERIFY_MCP_WIRE_CONTRACT`), resolves active remote MCP `ToolCallback` instances from `P2pMcpToolRouter`, verifies schema parity across the 4 exposed MCP tools (`checkInventoryLevels`, `checkCompliancePolicy`, `verifyVendorSlaAndProcurementPolicy`, `createPurchaseOrder`) while confirming `confirmHighValuePurchaseOrder` remains excluded from the LLM/MCP surface, executes zero-LLM-token JSON-RPC wire probes for read-only tools against Port 8081, and verifies within the probe window that `P2pMcpServerAuditAspect` persisted corresponding `MCP-EXEC-%` `ALLOWED` rows in `agent_security_audit_log`.
  Empirical Benchmarks: `checkInventoryLevels` = ~754 ms wire round-trip (0 LLM tokens); `verifyVendorSlaAndProcurementPolicy` = ~2,171 ms wire round-trip (0 LLM tokens).
- public Map<String, Object> getMcpAuditAndQueueTelemetry(RbacGuardService.EnterpriseActorContext actorContext)
  Contract: Enforces RBAC (`READ_MCP_AUDIT_TELEMETRY`) and returns aggregated `MCP-EXEC-%` server-side execution counts, the 15 most recent remote tool audit rows, and a live queue health snapshot (`pendingDlqReplayCount`, `pendingHitlPoCountOver50kInr`, `pausedHitlWorkflowRuns`).
- Endpoints on Port 8080:
    - `POST /api/v1/p2p/control-plane/mcp-contract/verify` (returns 200 OK `CONTRACT_VERIFIED_HEALTHY` or 503 `DOWN_FAIL_CLOSED_TO_DLQ` if Port 8081 is unreachable).
    - `GET /api/v1/p2p/control-plane/mcp-contract/audit-telemetry`

10. Headless MCP STDIO Subprocess Harness (`scripts/verify_mcp_stdio.py` & `.gemini/settings.json`) [ADR-010]:
- Profile: `mcp-stdio` (Headless JAR execution, `-Xmx512m`, Hikari max pool size = 2)
- Contract: Enforces strict `STDOUT` framing (`spring.main.banner-mode=off`, `logging.pattern.console=""`, logs routed to `./logs/mcp-stdio-server.log`) so newline-delimited JSON-RPC 2.0 frames (`initialize`, `tools/list`, `tools/call`) execute over `STDIN`/`STDOUT` with zero console log corruption for `@google/gemini-cli` and local CI runners.