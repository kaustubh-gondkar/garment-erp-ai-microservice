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
    - public List<PolicyChunkDto> verifyVendorSlaAndPolicy(String query, String vendorId)
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
- 
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


