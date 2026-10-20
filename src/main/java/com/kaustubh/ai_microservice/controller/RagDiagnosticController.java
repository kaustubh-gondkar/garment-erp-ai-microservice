package com.kaustubh.ai_microservice.controller;

import com.kaustubh.ai_microservice.model.PolicyChunkCandidate;
import com.kaustubh.ai_microservice.model.RerankedPolicyView;
import com.kaustubh.ai_microservice.repository.HybridPolicySearchRepository;
import com.kaustubh.ai_microservice.service.CrossEncoderRerankerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/debug/rag")
public class RagDiagnosticController {

  private final HybridPolicySearchRepository repository;
  private final CrossEncoderRerankerService reranker;

  public RagDiagnosticController(
      HybridPolicySearchRepository repository, CrossEncoderRerankerService reranker) {
    this.repository = repository;
    this.reranker = reranker;
  }

  @PostMapping("/seed")
  public ResponseEntity<Map<String, String>> seedSamplePolicies() {
    repository.ingestChunk(
        "SLA-VEND-101",
        "VEND-101",
        "VENDOR_SLA",
        "Clause 4.2: Cotton Yarn Lead Time & Defect Penalty",
        "Vendor VEND-101 (Shree Textiles) guarantees a maximum lead time of 5 business days for 40s Combed Cotton Yarn (HSN 5205). "
            + "Lot defect rates exceeding 1.5% trigger an automatic 8% invoice penalty and temporary vendor freeze.");
    repository.ingestChunk(
        "POL-PROC-2026-09",
        null,
        "PROCUREMENT_POLICY",
        "Section 2.1: Split Purchase Order Prohibition & 50k HITL Gate",
        "Purchase Orders exceeding INR 50,000 require mandatory Human-in-the-Loop (HITL) manager approval (PENDING_MANAGER_APPROVAL). "
            + "Artificially splitting a single material requisition into multiple POs below INR 50,000 to bypass the approval gate is a Class-A compliance violation.");
    repository.ingestChunk(
        "SLA-VEND-204",
        "VEND-204",
        "VENDOR_SLA",
        "Clause 3.1: Industrial Indigo Dye Emergency Dispatch",
        "Vendor VEND-204 guarantees 48-hour emergency dispatch for Indigo Vat Dye (HSN 3204) for orders above 100 kg, subject to a 3% expedited freight surcharge.");
    return ResponseEntity.ok(Map.of("status", "SEEDED_3_CHUNKS_ZERO_CHAT_RPD_USED"));
  }

  @GetMapping("/search")
  public ResponseEntity<List<RerankedPolicyView>> testHybridSearch(
      @RequestParam String query, @RequestParam(required = false) String vendorId) {
    List<PolicyChunkCandidate> candidates = repository.hybridSearchRrf(query, vendorId, 15);
    return ResponseEntity.ok(reranker.rerank(query, candidates, 4));
  }
}
