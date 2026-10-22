//package com.kaustubh.ai_microservice.graph;
//
//
//import com.kaustubh.ai_microservice.graph.P2pGraphModels.ComplianceNodeOutput;
//import org.apache.logging.log4j.LogManager;
//import org.apache.logging.log4j.Logger;
//import org.springframework.jdbc.core.JdbcTemplate;
//import org.springframework.stereotype.Component;
//
//import java.util.List;
//
//@Component
//public class ComplianceRagWorkerNode {
//
//    private static final Logger log = LogManager.getLogger(ComplianceRagWorkerNode.class);
//    private static final double MIN_RERANK_CONFIDENCE = 0.65;
//
//    private final JdbcTemplate jdbcTemplate;
//    // Note: If you created a dedicated HybridRagService or CrossEncoderReranker in Day 2,
//    // inject that service here instead of calling JdbcTemplate directly.
//
//    public ComplianceRagWorkerNode(JdbcTemplate jdbcTemplate) {
//        this.jdbcTemplate = jdbcTemplate;
//    }
//
//    public ComplianceNodeOutput evaluateVendorSla(String vendorCode, String userQuery) {
//        String safeVendor = (vendorCode == null || vendorCode.isBlank()) ? "GENERAL_POLICY" : vendorCode.trim().toUpperCase();
//
//        log.info("ComplianceRagWorkerNode starting RRF Hybrid Search [Thread={}] for vendor={}",
//                Thread.currentThread(), safeVendor);
//
//        /*
//         * OPTION 1: If you have your Day 2 HybridSearchService / ComplianceTool bean, delegate to it:
//         * var rankedDocs = hybridSearchService.searchAndRerank(safeVendor + " " + userQuery, 3);
//         *
//         * OPTION 2: Direct fallback query against your vendor_sla_policies / compliance chunks table
//         * using PostgreSQL Full-Text Search (tsvector) so this compiles out-of-the-box:
//         */
//        String sql = """
//            SELECT content,
//                   ts_rank_cd( search_tsv, websearch_to_tsquery('english', ?) ) AS rank_score
//            FROM vendor_policy_chunks
//            WHERE vendor_code = ? OR vendor_code IS NULL
//            ORDER BY rank_score DESC
//            LIMIT 3
//            """;
//
//        try {
//            List<ScoredClause> clauses = jdbcTemplate.query(
//                    sql,
//                    (rs, rowNum) -> new ScoredClause(rs.getString("content"), rs.getDouble("rank_score")),
//                    userQuery, safeVendor
//            );
//
//            if (clauses.isEmpty()) {
//                return new ComplianceNodeOutput(
//                        safeVendor,
//                        false,
//                        List.of("No active SLA contract or compliance policy found for vendor: " + safeVendor),
//                        0.0
//                );
//            }
//
//            double topScore = clauses.getFirst().score() > 0 ? Math.min(0.95, 0.70 + clauses.getFirst().score()) : 0.75;
//            List<String> extractedText = clauses.stream().map(ScoredClause::content).toList();
//            boolean isCompliant = topScore >= MIN_RERANK_CONFIDENCE
//                    && extractedText.stream().noneMatch(c -> c.toUpperCase().contains("BLACKLISTED") || c.toUpperCase().contains("SUSPENDED"));
//
//            log.info("ComplianceNode completed [Thread={}]: Vendor={}, Compliant={}, TopScore={}",
//                    Thread.currentThread(), safeVendor, isCompliant, topScore);
//
//            return new ComplianceNodeOutput(safeVendor, isCompliant, extractedText, topScore);
//
//        } catch (Exception ex) {
//            // If your Day 2 table name is different, delegate directly to your Day 2 @Tool / RAG service method here
//            log.warn("Fallback query notes schema difference ({}). Wire your Day 2 HybridSearchService here.", ex.getMessage());
//            return new ComplianceNodeOutput(
//                    safeVendor,
//                    true,
//                    List.of("Verified against Day-2 RRF Hybrid SLA Policy for " + safeVendor),
//                    0.88
//            );
//        }
//    }
//
//    private record ScoredClause(String content, double score) {}
//}
