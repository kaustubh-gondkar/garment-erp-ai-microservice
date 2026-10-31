# ADR-009: Deterministic MCP Wire Contract & Split-Latency Verification Harness

* **Status:** Accepted
* **Date:** 2026-10-31
* **System:** System 1 – Enterprise Procure-to-Pay (P2P) & Supply Chain Risk Agent

## Context
With tools extracted to a standalone MCP Server (`:8081`) in ADR-007 and secured via AspectJ RBAC auditing (`P2pMcpServerAuditAspect`) in ADR-008, operators and CI/CD pipelines need a deterministic mechanism to verify end-to-end tool execution across the SSE/JSON-RPC boundary without invoking `gemini-2.5-flash`. Routing readiness checks through the LLM incurs token costs, risks HTTP 429 quota exhaustion, introduces non-deterministic latency (2–5s), and obscures whether latency originates in model inference or on the MCP transport + database wire.

## Decision
1. **Zero-Token Direct `ToolCallback` Wire Probes:** Implement `P2pMcpContractVerifierService` (`@Profile("mcp-client")`) to resolve remote `ToolCallback` instances via `P2pMcpToolRouter.resolveActiveToolCallbacks()`, inspect live JSON Schemas (`inputSchema()`) dynamically, and invoke read-only remote tools (`checkInventoryLevels`, `verifyVendorSlaAndProcurementPolicy`) over HTTP/SSE JSON-RPC.
2. **Intentional Exclusion of HITL Approval from MCP Surface:** Verify that `confirmHighValuePurchaseOrder` remains excluded from the remote MCP tool registry so autonomous agents cannot self-approve POs exceeding ₹50,000.
3. **Cross-Process Audit Correlation:** Assert within the same probe window that `:8081` persisted `MCP-EXEC-%` `ALLOWED` rows in `agent_security_audit_log`.

## Empirical Benchmarks Captured (Neon DB + Local Loopback SSE)
* **`checkInventoryLevels` (MCP JSON-RPC + SQL + AspectJ RBAC Audit):** `754 ms` (`0` LLM tokens)
* **`verifyVendorSlaAndProcurementPolicy` (MCP JSON-RPC + Embedding + pgvector/tsvector RRF k=60 + Cross-Encoder Rerank + AspectJ RBAC Audit):** `2,171 ms` (`0` LLM tokens)