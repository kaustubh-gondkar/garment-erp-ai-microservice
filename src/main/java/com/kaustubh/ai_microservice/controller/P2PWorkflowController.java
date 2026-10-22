package com.kaustubh.ai_microservice.controller;

import com.kaustubh.ai_microservice.orchestrator.P2PAgentOrchestrator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/p2p/workflows")
public class P2PWorkflowController {

    private final P2PAgentOrchestrator orchestrator;

    public P2PWorkflowController(P2PAgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/execute")
    public ResponseEntity<P2PAgentOrchestrator.WorkflowExecutionResult> executeWorkflow(@RequestBody WorkflowRequest request) {
        P2PAgentOrchestrator.WorkflowExecutionResult result = orchestrator.executeWorkflow(
                request.idempotencyKey(),
                request.prompt()
        );
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{runId}/approve")
    public ResponseEntity<P2PAgentOrchestrator.WorkflowExecutionResult> approveWorkflow(
            @PathVariable UUID runId,
            @RequestParam UUID poId,
            @RequestParam(defaultValue = "VP-SUPPLY-CHAIN") String approverId) {

        P2PAgentOrchestrator.WorkflowExecutionResult result = orchestrator.approveAndResumePo(runId, poId, approverId);
        return ResponseEntity.ok(result);
    }

    public record WorkflowRequest(String idempotencyKey, String prompt) {}
}