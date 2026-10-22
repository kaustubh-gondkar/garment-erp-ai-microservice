package com.kaustubh.ai_microservice.controller;

import com.kaustubh.ai_microservice.graph.P2pGraphModels.GraphExecutionResult;
import com.kaustubh.ai_microservice.graph.P2pGraphOrchestrator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/p2p/graph")
public class P2pGraphController {

    private final P2pGraphOrchestrator graphOrchestrator;

    public P2pGraphController(P2pGraphOrchestrator graphOrchestrator) {
        this.graphOrchestrator = graphOrchestrator;
    }

    public record GraphRequest(String conversationId, String prompt) {}

    @PostMapping("/execute")
    public ResponseEntity<?> executeDag(@RequestBody GraphRequest request) {

        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "error", "Invalid Request",
                            "message", "The 'prompt' field is mandatory and cannot be null or blank."
                    ));
        }

        String convId = (request.conversationId() == null || request.conversationId().isBlank())
                ? "default-session"
                : request.conversationId().trim();

        GraphExecutionResult result = graphOrchestrator.executeGraph(convId, request.prompt().trim());
        return ResponseEntity.ok(result);
    }
}