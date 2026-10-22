package com.kaustubh.ai_microservice.tools;

import com.kaustubh.ai_microservice.model.PolicyChunkCandidate;
import com.kaustubh.ai_microservice.model.RerankedPolicyView;
import com.kaustubh.ai_microservice.repository.HybridPolicySearchRepository;
import com.kaustubh.ai_microservice.service.CrossEncoderRerankerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class ComplianceRagTools {

  private final HybridPolicySearchRepository hybridRepository;
  private final CrossEncoderRerankerService rerankerService;
  private final JdbcTemplate jdbcTemplate;

  public ComplianceRagTools(
      HybridPolicySearchRepository hybridRepository,
      CrossEncoderRerankerService rerankerService,
      JdbcTemplate jdbcTemplate) {
    this.hybridRepository = hybridRepository;
    this.rerankerService = rerankerService;
    this.jdbcTemplate = jdbcTemplate;
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


    @Tool(description = "Checks vendor compliance and procurement policy using PostgreSQL full-text search against natural language queries.")
    public String checkCompliancePolicy(String query) {
        try {
            log.info("[COMPLIANCE_RAG] Executing FTS search for query: '{}'", query);

            // Production-grade approach: Use PostgreSQL plainto_tsquery to parse natural language
            // sentences from the LLM without brittle string slicing.
            String searchSql = """
                SELECT policy_text 
                FROM compliance_policies 
                WHERE to_tsvector('english', title || ' ' || policy_text) @@ plainto_tsquery('english', ?)
                LIMIT 1
                """;

            List<String> results = jdbcTemplate.query(
                    searchSql,
                    (rs, rowNum) -> rs.getString("policy_text"),
                    query
            );

            // Fallback: If full-text search yields no matches, fetch the master procurement policy
            if (results.isEmpty()) {
                log.warn("[COMPLIANCE_RAG] No FTS match for query '{}'. Fetching general procurement policy fallback.", query);
                String fallbackSql = "SELECT policy_text FROM compliance_policies ORDER BY policy_id ASC LIMIT 1";
                results = jdbcTemplate.query(fallbackSql, (rs, rowNum) -> rs.getString("policy_text"));
            }

            if (results.isEmpty()) {
                return "WARNING: No compliance policies found in database. Proceed with standard enterprise caution.";
            }

            return "Compliance Policy Guidelines (Verified):\n" + results.get(0);

        } catch (Exception e) {
            log.error("[TOOL_ERROR] Failed to execute checkCompliancePolicy", e);
            return "Error executing compliance check: " + e.getMessage();
        }
    }
}
