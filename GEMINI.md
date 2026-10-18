# System 1: Enterprise Procure-to-Pay (P2P) & Supply Chain Risk Agent

## Tech Stack & Constraints
- **Language & Framework:** Java 21, Spring Boot 3.x, Spring AI 2.0+, Maven
- **Database:** Serverless PostgreSQL (Neon DB) with `pgvector` and `tsvector` (BM25 hybrid search)
- **LLM Models:** `gemini-2.5-flash` (primary orchestrator/tool calling), `gemini-2.5-flash-lite` (fast guardrail router)
- **Code Style:** Constructor injection only (`@RequiredArgsConstructor` or explicit constructors), Java `record` for all DTOs and `@Tool` request/response payloads, strict SLF4J structured logging.

## Week 1 Architecture Rules
1. All agent tools (`ProcurementTools`) must use Spring AI `@Tool` annotations with explicit, unambiguous descriptions for the LLM.
2. Enforce a deterministic Human-in-the-Loop (HITL) approval gate: Purchase Orders (POs) <= ₹50,000 auto-approve (`APPROVED`); POs > ₹50,000 must be persisted with status `PENDING_MANAGER_APPROVAL`.
3. Enforce loop step-limits (max 5 iterations) to prevent infinite agent tool-calling cycles.