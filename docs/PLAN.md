# Plan

Each milestone ends with something demo-able and a short write-up in `docs/PROGRESS.md`. Don't start a milestone until the previous one's acceptance criteria pass.

## M0 — Skeleton (2–3 days)
- Monorepo layout from `CLAUDE.md`, `Makefile`, `deploy/compose` with Postgres+pgvector, Redpanda, Valkey, OTel collector.
- Gradle multi-project skeleton (version catalog, Spotless, Error Prone) + web app; every service with an Actuator health endpoint (`evals` is a CLI app: no HTTP endpoint, its in-context health is asserted by a test), OTel traces visible locally, CI running build+test+lint per service.

**Accept:** `make up && make test && make lint` pass on a clean clone; CI green; one trace spans web → orchestrator → Postgres (in M0 verified by a manual click in the UI plus `make verify-trace` against Jaeger; browser automation arrives in M5).

## M1 — x402 Spring Boot starter + first paid endpoint (1 week)
- `libs/x402-spring-boot-starter`: a native x402 v2 implementation (`exact` scheme on EVM only; ADR-0008, the official Java SDK is a reference) — server filter + `@RequiresPayment` (`exact` scheme), `RestClient` interceptor, `SpendGuard` hook, auto-configuration, fake facilitator for tests, real facilitator client for Base Sepolia.
- `seller-api`: one paid endpoint (`GET /v1/disclosures/{ticker}/summary`, price in test USDC).
- Spec-conformance tests against the x402 Foundation examples; EIP-712/EIP-3009 signing verified against the spec test vectors.

**Accept:** a console client pays the endpoint on Base Sepolia and gets data; replayed payload is rejected; README quickstart works copy-paste; starter publishes to a local Maven repo and a sample app uses it with zero config beyond properties.

## M2 — Ingest + RAG (1 week)
- `ingest` (Spring AI `PgVectorStore`): KAP disclosure + news pipeline into pgvector, idempotent, DLQ.
- Hybrid retrieval (vector + Turkish full-text, RRF) exposed to seller-api; answers cite chunk ids.
- Paid endpoints use RAG; also exposed as MCP tools.

**Accept:** ≥5k chunks indexed; a question returns an answer with ≥2 valid citations; re-running ingest creates no duplicates.

## M3 — Orchestrator, model router, spend control (1–1.5 weeks)
- Agents: planner → researcher (calls paid tools) → risk → final synthesis.
- Model router with tiers, provider fallbacks, data-classification policy, cost metrics.
- Spend control: per-run budget, daily cap, payee allowlist, idempotency, human approval above threshold.
- SSE stream of run steps.

**Accept:** a research run completes end to end with ≥2 paid calls; exceeding the budget blocks payment *before* signing (test proves it); a prompt-injection document cannot raise a budget (test proves it); per-run USD cost visible in traces.

## M4 — Ledger + reconciliation (1 week)
- `ledger`: double-entry, inbox/outbox, consumers for payment events.
- Reconciliation job against Base Sepolia; mismatch → suspense + event.

**Accept:** balanced-postings property test passes; duplicate and out-of-order events handled; a deliberately corrupted record shows up as a mismatch in the report.

## M5 — Dashboard (1 week)
- Run view, spend control + approvals, ledger/reconciliation, seller revenue, landing page.

**Accept:** a new user can start a run, approve a payment and see it land in the ledger without reading docs; Lighthouse accessibility ≥ 90.

## M6 — Evals, deploy, publish (1 week)
- Golden set, `evals` harness, model-route comparison report (tier1: GPT-5.6 Luna vs Gemini 3.8 Flash vs DeepSeek V4.1 Flash).
- Terraform `demo-lite` + bootstrap (state bucket, OIDC roles); `demo-up` / `demo-down` workflows; `make capture-demo`.
- Human runs `demo-up`, the captured runs + 3-minute video + Grafana screenshots are recorded, then `demo-down` (verify nothing but the state bucket remains).
- SPA replay mode on Cloudflare Pages; blog post (EN + TR).

**Accept:** public replay URL live and honest about being recorded; `terraform plan` output and Infracost estimate in the README; `make eval` report published in the repo; README has architecture diagram, cost table, eval table, and "how I'd scale this" section.

## Stretch (only after M6)
- Terraform `enterprise` profile (EKS + MSK + Helm charts); `upto` scheme; MPP adapter; mock DTL (programmable payment) adapter; ERC-8004 identity/reputation registration for seller agents; AWS AgentCore Payments comparison write-up.
