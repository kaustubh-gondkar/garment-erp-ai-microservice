# ADR-008: Zero-Trust MCP Server Boundary, Cross-JVM Tool Audit Aspect, and Bridge Diagnostics

- **Status:** Accepted
- **Date:** 2026-10-29
- **Component:** System 1 — Enterprise P2P & Supply Chain Risk Agent (Phase 3 MCP)

## Context
Extracting `@Tool` methods (`ProcurementTools`, `ComplianceRagTools`) into a standalone MCP Server (`:8081`) introduced a cross-JVM security and audit boundary gap:
1. Tool calls invoked over JSON-RPC/SSE executed inside `:8081` without recording server-side RBAC decisions in `agent_security_audit_log`.
2. The `:8081` `/sse` and `/mcp/message` endpoints lacked transport-level origin verification.
3. Operators on `:8080` needed a deterministic control-plane health probe to inspect remote MCP tool schemas and latency without triggering an LLM call.

## Decision
1. **Non-Invasive AOP Audit Boundary (`P2pMcpServerAuditAspect`):** Intercept all `@Tool` executions in `mcp-server` and `mcp-stdio` profiles and delegate authorization + immutable audit logging to `RbacGuardService.authorizeOrThrow(...)` without modifying existing domain beans.
2. **Transport Guard (`McpServerTransportGuardFilter`):** Enforce loopback origin verification and configurable `X-MCP-API-Key` validation on `:8081` while tracking SSE handshake and JSON-RPC message counters.
3. **Control-Plane Bridge Probe (`P2pMcpBridgeDiagnosticsController`):** Expose `GET /api/v1/p2p/control-plane/mcp-bridge-status` on `:8080` using `P2pMcpToolRouter.resolveActiveToolCallbacks()` to verify remote tool count, schema readiness, and round-trip latency.

## Consequences
- **Positive:** Eliminates the MCP "Confused Deputy" vulnerability; guarantees 100% audit coverage in `agent_security_audit_log` across both JVMs; preserves strict domain-bean reuse.
- **Trade-off:** Adds ~3–6ms per remote tool call for the synchronous audit `INSERT` into Neon PostgreSQL.