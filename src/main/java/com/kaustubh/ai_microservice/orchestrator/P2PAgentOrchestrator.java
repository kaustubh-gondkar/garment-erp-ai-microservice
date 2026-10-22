package com.kaustubh.ai_microservice.orchestrator;

import com.kaustubh.ai_microservice.exception.AgentLoopTerminationException;
import com.kaustubh.ai_microservice.harness.LoopGuardAdvisor;
import com.kaustubh.ai_microservice.tools.ComplianceRagTools;
import com.kaustubh.ai_microservice.tools.ProcurementTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class P2PAgentOrchestrator {

  private static final Logger log = LoggerFactory.getLogger(P2PAgentOrchestrator.class);

  private final ChatClient chatClient;
  private final LoopGuardAdvisor loopGuardAdvisor;
  private final JdbcTemplate jdbcTemplate;
  private final ProcurementTools procurementTools;

  public P2PAgentOrchestrator(
      ChatClient.Builder chatClientBuilder,
      LoopGuardAdvisor loopGuardAdvisor,
      ProcurementTools procurementTools,
      ComplianceRagTools complianceRagTools,
      JdbcTemplate jdbcTemplate) {
    this.loopGuardAdvisor = loopGuardAdvisor;
    this.procurementTools = procurementTools;
    this.jdbcTemplate = jdbcTemplate;

    // Register tools and the advisor on the builder directly
    this.chatClient =
        chatClientBuilder
            .defaultSystem(
                """
                    You are an Enterprise Procure-to-Pay (P2P) Supply Chain Risk Agent.
                    CRITICAL OPERATING PROTOCOL (STRICT SEQUENTIAL REACT):
                    1. STEP 1: You MUST call the 'checkCompliancePolicy' tool first. Do not proceed without tool output.
                    2. STEP 2: Analyze the compliance output. If compliant, you MUST explicitly invoke the 'createPurchaseOrder' tool with the exact SKU, vendor ID, quantity, and total amount.
                    3. STEP 3: NEVER invent, hallucinate, or assume tool results or purchase order IDs. Only report PO IDs returned directly by the 'createPurchaseOrder' tool execution.
                    4. If any tool fails or returns an error, halt immediately and report the error.
                    """)
            .defaultTools(procurementTools, complianceRagTools)
            .defaultAdvisors(loopGuardAdvisor)
            .build();
  }

  public WorkflowExecutionResult executeWorkflow(String idempotencyKey, String userPrompt) {
    Optional<WorkflowExecutionResult> existingRun = findExistingWorkflow(idempotencyKey);
    if (existingRun.isPresent()) {
      log.warn("[IDEMPOTENCY_CACHE_HIT] Request with key {} already exists", idempotencyKey);
      return existingRun.get();
    }

    UUID runId = UUID.randomUUID();

    try {
      insertInitialWorkflowRun(runId, idempotencyKey, userPrompt);
    } catch (DuplicateKeyException ex) {
      return findExistingWorkflow(idempotencyKey)
          .orElseThrow(() -> new IllegalStateException("Failed to retrieve existing workflow"));
    }

    try {
      // Pass ONLY the context parameter here (the advisor is already in defaultAdvisors)
      ChatResponse chatResponse =
          chatClient
              .prompt()
              .user(userPrompt)
              .advisors(spec -> spec.param(LoopGuardAdvisor.WORKFLOW_RUN_ID_KEY, runId.toString()))
              .call()
              .chatResponse();

      String output = null;

      if (chatResponse != null && chatResponse.getResult() != null) {
        AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
        output = assistantMessage.getText();

        // Diagnostic inspections for tool execution
        log.info("[DIAGNOSTIC] Raw assistant text: {}", output);
        log.info("[DIAGNOSTIC] Has tool calls: {}", assistantMessage.hasToolCalls());
        if (assistantMessage.hasToolCalls()) {
          log.info("[DIAGNOSTIC] Tool calls requested: {}", assistantMessage.getToolCalls());
        }
      }

      int steps = loopGuardAdvisor.getStepCount(runId.toString());
      int tokens = loopGuardAdvisor.getTokenCount(runId.toString());

      // Fallback: If Gemini returned tool calls without text synthesis, capture that status
      if (output == null
          && chatResponse != null
          && chatResponse.getResult().getOutput().hasToolCalls()) {
        output = "Tool calls executed: " + chatResponse.getResult().getOutput().getToolCalls();
      }

      String finalStatus =
          (output != null && output.contains("AWAITING_HUMAN_APPROVAL"))
              ? "AWAITING_HUMAN_APPROVAL"
              : "COMPLETED";

      updateWorkflowRun(
          runId, finalStatus, steps, tokens, output != null ? output : "No response generated");
      return new WorkflowExecutionResult(runId, finalStatus, steps, tokens, output);

    } catch (AgentLoopTerminationException ex) {
      int steps = loopGuardAdvisor.getStepCount(runId.toString());
      int tokens = loopGuardAdvisor.getTokenCount(runId.toString());
      updateWorkflowRun(runId, "TERMINATED_STEP_LIMIT", steps, tokens, ex.getMessage());
      return new WorkflowExecutionResult(
          runId, "TERMINATED_STEP_LIMIT", steps, tokens, ex.getMessage());
    } finally {
      loopGuardAdvisor.cleanup(runId.toString());
    }
  }

  private Optional<WorkflowExecutionResult> findExistingWorkflow(String idempotencyKey) {
    String sql =
        """
            SELECT run_id, status, step_count, total_tokens_used, final_response
            FROM agent_workflow_runs
            WHERE idempotency_key = ?
            """;
    try {
      WorkflowExecutionResult existing =
          jdbcTemplate.queryForObject(
              sql,
              (rs, rowNum) ->
                  new WorkflowExecutionResult(
                      (UUID) rs.getObject("run_id"),
                      rs.getString("status"),
                      rs.getInt("step_count"),
                      rs.getInt("total_tokens_used"),
                      rs.getString("final_response") != null
                          ? rs.getString("final_response")
                          : "Workflow currently IN_PROGRESS"),
              idempotencyKey);

      if (existing != null) {
        String status = existing.status();
        // FDE Rule: Only cache true terminal success states.
        // If it FAILED or encountered a tool validation error, allow re-execution!
        if ("FAILED".equals(status) || "TERMINATED_STEP_LIMIT".equals(status)) {
          log.warn(
              "[IDEMPOTENCY_BYPASS] Previous run {} had status {}. Allowing retry.",
              existing.runId(),
              status);
          return Optional.empty();
        }
      }

      return Optional.ofNullable(existing);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  @Transactional
  public WorkflowExecutionResult approveAndResumePo(UUID runId, UUID poId, String approverId) {
    // Deterministic state transition: execute DB mutation directly without burning LLM tokens
    String confirmation = procurementTools.confirmHighValuePurchaseOrder(poId, approverId);

    jdbcTemplate.update(
        """
            UPDATE agent_workflow_runs
            SET status = 'APPROVED_RESUMED',
                final_response = ?,
                updated_at = NOW()
            WHERE run_id = ? AND status = 'AWAITING_HUMAN_APPROVAL'
            """,
        confirmation,
        runId);

    return new WorkflowExecutionResult(runId, "APPROVED_RESUMED", 1, 0, confirmation);
  }

  private void insertInitialWorkflowRun(UUID runId, String key, String prompt) {
    try {
      String sql =
"""
    UPDATE agent_workflow_runs
    SET run_id = ?,
        user_prompt = ?,
        status = 'IN_PROGRESS'
    WHERE idempotency_key = ?
""";

      jdbcTemplate.update(sql, runId, prompt,key );

    } catch (DataAccessException e) {
      log.error("insertInitialWorkflowRun Failed", e);
      throw new RuntimeException("insert Failed");
    }
  }

  private void updateWorkflowRun(
      UUID runId, String status, int steps, int tokens, String response) {
    jdbcTemplate.update(
        """
            UPDATE agent_workflow_runs
            SET status = ?, step_count = ?, total_tokens_used = ?, final_response = ?, updated_at = NOW()
            WHERE run_id = ?
            """,
        status,
        steps,
        tokens,
        response,
        runId);
  }

  public record WorkflowExecutionResult(
      UUID runId, String status, int stepsUsed, int tokensUsed, String response) {}
}
