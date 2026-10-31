# ADR-010: Headless MCP STDIO Subprocess Harness & Strict STDOUT Framing

* **Status:** Accepted
* **Date:** 2026-10-31
* **System:** System 1 – Enterprise Procure-to-Pay (P2P) & Supply Chain Risk Agent

## Context
While enterprise microservices consume the P2P MCP Server over HTTP/SSE (`mcp-server` on Port `8081`), developer CLI hosts (`@google/gemini-cli`, Claude Desktop) and local CI contract runners launch MCP servers as headless child processes communicating over `STDIN` and `STDOUT`. Any non-JSON output written to `STDOUT` (Spring Boot ASCII banners, SLF4J startup logs, HikariCP pool notices) corrupts the newline-delimited JSON-RPC 2.0 stream and crashes the MCP host handshake.

## Decision
1. **Dedicated `mcp-stdio` Profile:** Configure `spring.main.web-application-type=none`, `spring.main.banner-mode=off`, `spring.main.log-startup-info=false`, and `spring.ai.mcp.server.stdio=true` with Hikari maximum pool size capped at `2` (`-Xmx512m`) to respect the 12 GB RAM workstation budget.
2. **Strict `STDOUT` Silence:** Blank out `logging.pattern.console` and route error diagnostics exclusively to `./logs/mcp-stdio-server.log` so `STDOUT` carries only valid JSON-RPC frames.
3. **Zero-Token Subprocess Verification:** Provide `.gemini/settings.json` for `@google/gemini-cli` alongside `scripts/verify_mcp_stdio.py` to test `initialize`, `tools/list`, and `tools/call` over raw pipes without consuming LLM API quota.

## Consequences
* **Positive:** Identical `@Tool` domain beans (`ProcurementTools`, `ComplianceRagTools`) and `P2pMcpServerAuditAspect` RBAC guarantees work seamlessly across both HTTP/SSE microservice deployments and local CLI subprocesses.
* **Trade-off:** Standard console logs are suppressed in `mcp-stdio` mode; operators must tail `./logs/mcp-stdio-server.log` when troubleshooting subprocess startup failures.