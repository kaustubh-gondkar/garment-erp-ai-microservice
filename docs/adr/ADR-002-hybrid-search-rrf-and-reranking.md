# ADR-002: Single-Query PostgreSQL Hybrid Search (RRF) & Cross-Encoder Reranking

* **Status:** Accepted
* **Date:** 2026-10-20
* **System:** System 1 — Enterprise Procure-to-Pay (P2P) & Supply Chain Risk Agent

## 1. Context & Problem Statement
Pure dense vector search (`pgvector` cosine similarity) fails on exact alphanumeric identifiers critical to enterprise procurement—such as vendor codes (`VEND-101`), clause numbers (`Clause 4.2`), and tax/HSN codes (`HSN 5205`). Conversely, pure keyword search misses semantic paraphrases (e.g., matching *"late shipment fine"* to *"lead time penalty"*). Furthermore, running an external vector database (Pinecone/Qdrant) or an LLM-based listwise reranker introduces extra network hops, operational cost, and exhausts strict API rate limits.

## 2. Decision
1. **In-Database Hybrid Search via Reciprocal Rank Fusion (RRF):** Store both 768-dimensional dense embeddings (`vector(768)` indexed with HNSW) and a generated `tsvector` column (`fts_doc` indexed with GIN) inside the same Neon PostgreSQL table (`compliance_policy_chunks`).
2. **Single-Round-Trip SQL CTE Fusion:** Combine top-20 semantic rankings and top-20 `ts_rank_cd` lexical rankings in a single SQL `FULL OUTER JOIN` using RRF ($k = 60$, $w_{\text{sem}} = 0.60$, $w_{\text{lex}} = 0.40$) because cosine distance ($[0, 2]$) and `ts_rank_cd` (unbounded float) cannot be linearly summed without score calibration.
3. **Deterministic Cross-Encoder Reranking:** Pass the top-15 RRF candidates through an in-memory Cross-Encoder Reranker that evaluates query-document token coverage, phrase bigrams, exact identifier matches, and dual-engine consensus, pruning context down to the top-4 verified chunks before LLM injection.

## 3. Consequences
* **Positive:** Zero additional infrastructure outside Neon PostgreSQL; eliminates 60–100ms network overhead of dual-query merging; achieves high recall on both exact SKU/clause lookups and semantic policy queries; consumes 0 LLM Chat API quota during reranking.
* **Negative / Trade-offs:** Stored generated `tsvector` + dual HNSW/GIN indexes increase write amplification by ~18% on policy ingestion, which is acceptable for a read-heavy (99:1) compliance knowledge base.