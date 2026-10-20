package com.kaustubh.ai_microservice.repository;

import com.kaustubh.ai_microservice.model.PolicyChunkCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class HybridPolicySearchRepository {

  private static final Logger log = LoggerFactory.getLogger(HybridPolicySearchRepository.class);
  private static final int RRF_K = 60;
  private static final double SEMANTIC_WEIGHT = 0.60;
  private static final double LEXICAL_WEIGHT = 0.40;

  private final JdbcClient jdbcClient;
  private final EmbeddingModel embeddingModel;

  public HybridPolicySearchRepository(JdbcClient jdbcClient, EmbeddingModel embeddingModel) {
    this.jdbcClient = jdbcClient;
    this.embeddingModel = embeddingModel;
  }

  public List<PolicyChunkCandidate> hybridSearchRrf(
      String queryText, String vendorIdFilter, int topK) {
    float[] queryVector = embeddingModel.embed(queryText);
    String pgVectorLiteral = toPgVectorLiteral(queryVector);

    String sql =
        """
            WITH semantic_search AS (
                SELECT chunk_id,
                       ROW_NUMBER() OVER (ORDER BY embedding <=> CAST(:queryVector AS vector)) AS vec_rank
                FROM compliance_policy_chunks
                WHERE (:vendorId IS NULL OR vendor_id = :vendorId OR vendor_id IS NULL)
                  AND embedding IS NOT NULL
                ORDER BY embedding <=> CAST(:queryVector AS vector)
                LIMIT 20
            ),
            lexical_search AS (
                SELECT chunk_id,
                       ROW_NUMBER() OVER (
                           ORDER BY ts_rank_cd(fts_doc, websearch_to_tsquery('english', :queryText)) DESC
                       ) AS lex_rank
                FROM compliance_policy_chunks
                WHERE (:vendorId IS NULL OR vendor_id = :vendorId OR vendor_id IS NULL)
                  AND fts_doc @@ websearch_to_tsquery('english', :queryText)
                ORDER BY ts_rank_cd(fts_doc, websearch_to_tsquery('english', :queryText)) DESC
                LIMIT 20
            )
            SELECT c.chunk_id,
                   c.doc_code,
                   c.vendor_id,
                   c.category,
                   c.section_title,
                   c.content,
                   s.vec_rank,
                   l.lex_rank,
                   (COALESCE(:semWeight / (:rrfK + s.vec_rank), 0.0) +
                    COALESCE(:lexWeight / (:rrfK + l.lex_rank), 0.0)) AS rrf_score
            FROM semantic_search s
            FULL OUTER JOIN lexical_search l ON s.chunk_id = l.chunk_id
            JOIN compliance_policy_chunks c ON c.chunk_id = COALESCE(s.chunk_id, l.chunk_id)
            ORDER BY rrf_score DESC
            LIMIT :topK
            """;

    log.info(
        "Executing RRF Hybrid Search for query='{}', vendorId='{}'", queryText, vendorIdFilter);

    return jdbcClient
        .sql(sql)
        .param("queryVector", pgVectorLiteral)
        .param("queryText", queryText)
        .param(
            "vendorId",
            (vendorIdFilter == null || vendorIdFilter.isBlank()) ? null : vendorIdFilter)
        .param("semWeight", SEMANTIC_WEIGHT)
        .param("lexWeight", LEXICAL_WEIGHT)
        .param("rrfK", RRF_K)
        .param("topK", topK)
        .query(
            (rs, rowNum) ->
                new PolicyChunkCandidate(
                    rs.getObject("chunk_id", UUID.class),
                    rs.getString("doc_code"),
                    rs.getString("vendor_id"),
                    rs.getString("category"),
                    rs.getString("section_title"),
                    rs.getString("content"),
                    (Long) rs.getObject("vec_rank"),
                    (Long) rs.getObject("lex_rank"),
                    rs.getDouble("rrf_score")))
        .list();
  }

  public void ingestChunk(
      String docCode, String vendorId, String category, String sectionTitle, String content) {
    String embeddingInput = docCode + " | " + sectionTitle + " | " + content;
    float[] vector = embeddingModel.embed(embeddingInput);

    String sql =
        """
            INSERT INTO compliance_policy_chunks (doc_code, vendor_id, category, section_title, content, embedding)
            VALUES (:docCode, :vendorId, :category, :sectionTitle, :content, CAST(:embedding AS vector))
            """;

    jdbcClient
        .sql(sql)
        .param("docCode", docCode)
        .param("vendorId", vendorId)
        .param("category", category)
        .param("sectionTitle", sectionTitle)
        .param("content", content)
        .param("embedding", toPgVectorLiteral(vector))
        .update();
  }

  private String toPgVectorLiteral(float[] vector) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < vector.length; i++) {
      sb.append(vector[i]);
      if (i < vector.length - 1) sb.append(",");
    }
    sb.append("]");
    return sb.toString();
  }
}
