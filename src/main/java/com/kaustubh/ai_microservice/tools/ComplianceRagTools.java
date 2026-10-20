package com.kaustubh.ai_microservice.tools;

import com.kaustubh.ai_microservice.model.PolicyChunkCandidate;
import com.kaustubh.ai_microservice.model.RerankedPolicyView;
import com.kaustubh.ai_microservice.repository.HybridPolicySearchRepository;
import com.kaustubh.ai_microservice.service.CrossEncoderRerankerService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ComplianceRagTools {

  private final HybridPolicySearchRepository hybridRepository;
  private final CrossEncoderRerankerService rerankerService;

  public ComplianceRagTools(
      HybridPolicySearchRepository hybridRepository, CrossEncoderRerankerService rerankerService) {
    this.hybridRepository = hybridRepository;
    this.rerankerService = rerankerService;
  }

  @Tool(
      description =
          """
        Searches Vendor SLA contracts, lead-time guarantees, defect penalty clauses, and enterprise
        procurement/tax policies using Hybrid Search (pgvector + BM25 RRF) and Cross-Encoder Reranking.
        ALWAYS call this tool to verify vendor SLA compliance before generating a Purchase Order.
        """)
  public List<RerankedPolicyView> verifyVendorSlaAndProcurementPolicy(
      @ToolParam(
              description =
                  "Specific compliance query, SLA clause, material name, or policy rule to verify")
          String searchQuery,
      @ToolParam(
              description =
                  "Optional vendor ID filter such as VEND-101, or empty string for global policies")
          String vendorId) {

    List<PolicyChunkCandidate> rrfCandidates =
        hybridRepository.hybridSearchRrf(searchQuery, vendorId, 15);
    return rerankerService.rerank(searchQuery, rrfCandidates, 4);
  }
}
