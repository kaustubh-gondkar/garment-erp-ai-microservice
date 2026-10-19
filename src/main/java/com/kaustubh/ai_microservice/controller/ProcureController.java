package com.kaustubh.ai_microservice.controller;

import com.kaustubh.ai_microservice.agent.service.P2PAgentService;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class ProcureController {

  private final P2PAgentService p2PAgentService;

    public record ProcureRequest(String prompt) {}

    @PostMapping("/procure")
    public ResponseEntity<String> procure(@RequestBody ProcureRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            return ResponseEntity.badRequest().body("Error: 'prompt' field cannot be empty.");
        }
        String response = p2PAgentService.executeProcurementRun(request.prompt());
        return ResponseEntity.ok(response);
    }
}
