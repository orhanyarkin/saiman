# Architecture

## What it does

A user (or a scheduled job) asks a research question about a BIST company or a crypto asset. The **orchestrator** plans the work, calls tools, and — when a tool sits behind a paywall — pays for it per request over **x402** on Base Sepolia, within budgets enforced in code. Answers come from **RAG** over public KAP disclosures (a frozen 2023 snapshot, ADR-0010), with citations. Every payment is recorded in a **double-entry ledger** and reconciled against the chain. An **eval harness** measures answer quality against cost.

## Services

| Component | Stack | Responsibility |
|---|---|---|
| `seller-api` | Spring Boot 4.1 | Paid endpoints (disclosure summary, news sentiment, order-book snapshot from TickForge), protected by the x402 starter; same capabilities as MCP tools (`@McpTool`) |
| `orchestrator` | Spring Boot 4.1 + Spring AI 2.0 | Agents on `ChatClient`, model router, spend-control plane, x402 client, SSE run stream |
| `ledger` | Spring Boot 4.1 | Double-entry ledger, inbox + Spring Modulith outbox, reconciliation with Base Sepolia (JSON-RPC via `libs/evm-rpc`; ADR-0016..0018) |
| `ingest` | Spring Boot 4.1 + Spring AI 2.0 | Owns the corpus (ADR-0012): MKK KAP API → normalise → chunk → embed → `PgVectorStore`; serves internal hybrid retrieval (vector + Turkish full-text, RRF, plus a recency leg for "latest" questions, ADR-0025) |
| `evals` | Spring Boot CLI (compose one-shot) | Golden-set evals with deterministic metrics (no LLM judge, ADR-0025), cost/quality reports in `docs/evals/` |
| `web` | React 19 + Vite | Static SPA, served by nginx in compose and on AWS; the replay build goes on Cloudflare Pages (ADR-0026) |
| `model-router` | Java library | The only path to LLM/embedding providers (ADR-0011): tiers, data-class policy, daily USD cap, metrics |
| `x402-spring-boot-starter` | Java library | Open-source Spring Boot starter implementing x402 v2 (`exact` scheme on EVM, Base Sepolia only; ADR-0008): filter, `@RequiresPayment`, `RestClient` interceptor, auto-config |

## Data and messaging

- **Migrations (ADR-0024, ADR-0027).** Every service image has two entry points. `SAIMAN_RUN_MODE=migrate` (`libs/db-migrate`) runs Flyway as `<svc>_owner` and exits; the service itself runs as the DML-only `<svc>_app` role with Flyway off and no owner secret. Compose runs a `<svc>-migrate` one-shot first (the service depends on `service_completed_successfully`); ECS runs it as a non-essential container with `dependsOn: SUCCESS`.

- **PostgreSQL 17 + pgvector** — one instance, one schema per service (ADR-0001).
- **Apache Kafka** in KRaft mode (single node locally and as an ECS Fargate task in `demo-lite`; MSK in the `enterprise` Terraform profile; ADR-0020).
- **Redis** (ADR-0020) — nonce claims, the router's daily and per-run LLM cost counters, per-payer run limits, idempotency keys, LLM response cache (M6). Payment budgets and approvals live in **Postgres** (ADR-0013).

Topics (schemas in `docs/events/`; `payments.challenge-issued.v1` is reserved and not produced, no money moves at a challenge): `payments.authorized.v1`, `payments.settled.v1`, `payments.failed.v1`, `ledger.entry-posted.v1`, `ledger.reconciliation-mismatch.v1`, `ingest.document-indexed.v1`, `agent.run-step.v1`.

## Paid call flow

1. Agent decides to call a tool → orchestrator's x402 client sends the request.
2. Seller returns **402** with payment requirements (price, asset, network, payTo, scheme).
3. **Spend guard** (code, not LLM): payee on allowlist? run budget and daily cap sufficient? idempotency key unused? above approval threshold → park for human approval.
4. Client signs (testnet wallet from env), retries with the payment header.
5. Seller claims the payment nonce (replay guard), verifies via the facilitator, runs the handler with the response buffered, settles, then releases the body with `PAYMENT-RESPONSE` (no settlement → no body, no charge). Emits events via outbox from M4.
6. Ledger posts entries; reconciliation later matches the tx hash on-chain.

## Model routing and cost (ADR-0003)

| Tier | Use | Default | Fallback | $/MTok in/out (verified 2026-09) |
|---|---|---|---|---|
| tier0 | routing, extraction, classification (low reasoning effort) | GPT-6 Luna | GPT-5 nano | 0.10 / 0.50 |
| tier1 | tool-using agent steps | GPT-5.6 Luna | DeepSeek V4.1 Flash (`public` data only), Gemini 3.8 Flash | 0.20 / 1.20 |
| tier1-premium | eval comparison, hard runs | Gemini 3.8 Flash | Claude Sonnet 5 | 0.75 / 3.75 (doubles 2027-01-01) |
| tier2 | final synthesis, eval judge (batch) | Claude Sonnet 5 (batch) | Gemini 3.8 Flash | 1 / 5 (batch) |
| embed | embeddings | text-embedding-3-small | — | 0.02 |

Why: on Finance Agent v2 (Sept 2026) Gemini 3.8 Flash scores 61.4%, GPT-5.6 Luna 55.0%, Claude Sonnet 5 53.9%, DeepSeek V4.1 Flash 53.5%, while Claude Haiku 4.5 (thinking) scores 31.0% — so Haiku is not used. The eval harness re-checks these choices on our own tasks; config wins over this table.

Guards: per-IP rate limit, per-run token budget, **global daily LLM cap** (default $0.70/day). When the cap is hit the public demo switches to **replay mode** (pre-recorded runs). Prices change often — the router reads them from config, and `make cost-report` shows actuals.

## Deployment (ADR-0004, ADR-0028)

- **Real deployment, on demand**: Terraform `demo-lite` on AWS `eu-central-1` (ADR-0028). One Fargate ARM task (2 vCPU / 8 GB) holds every container on localhost, Kafka and Redis included, plus a private RDS `db.t4g.micro` (PostgreSQL 17 + pgvector). No NAT and no load balancer: nothing is exposed to the internet and the human reaches the `web` container through an SSM port-forward. Start-up order inside the task: `assets` (S3 sync) and `db-init` (roles), the four `<svc>-migrate` one-shots, `corpus-restore` (a private dump of the public KAP corpus from the state bucket, not a rebuild from MKK), then the services and `readiness`. Secrets are SSM SecureString parameters injected as ECS environment variables; Terraform never reads them.
- **Bootstrap and CI**: the human applies the bootstrap stack once (state bucket, GitHub OIDC provider, `plan`/`apply`/`destroy` roles with a permissions boundary). CI runs `fmt`, `validate`, `test` and `plan` only, never `apply`. `demo-up` and `demo-down` are triggered only by the human; an expiry reaper (cron workflow plus EventBridge Scheduler) stops an expired demo. `enterprise` (EKS + MSK + Helm) is a stretch goal.
- **Cost**: about $0.55-0.80 per 4-hour session (hand estimate in ADR-0028, supersedes the older $1-3 in ADR-0004) and about $0.003/month idle. See the README cost table for sources and what is still pending.
- **Capture**: `make capture-demo` runs against the deployed stack and exports run event streams, ledger/reconciliation, seller and eval snapshots as JSON (ADR-0026).
- **Public demo**: the SPA in replay mode (`VITE_DEMO_MODE=replay`) on Cloudflare Pages, replaying captured runs with a "Recorded on <environment>, <date>" banner. Idle cost is about $0/month (the URL does not exist until the human deploys it).
- **Observability**: OpenTelemetry collector; Grafana Cloud export is optional (`grafana_otlp_endpoint`), Jaeger locally.

## Out of scope

Mainnet, real money, custody, KYC, fiat rails, trading execution. The DTL (Digital Turkish Lira) adapter is a mock interface demonstrating rail-agnostic design, not an integration.
