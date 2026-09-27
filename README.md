# sAIman

*From Turkish "sayman" — treasurer.*

**The AI treasurer for research agents.** Agents research BIST companies and crypto assets, pay for data and tools per request over [x402](https://x402.org) (HTTP 402 + stablecoin, Base Sepolia testnet), stay inside budgets enforced in code — never by the model — and every payment lands in a double-entry ledger that always balances and is reconciled against the chain.

> Status: under construction. See [docs/PLAN.md](docs/PLAN.md) for milestones and [docs/PROGRESS.md](docs/PROGRESS.md) for what's done.

## What's inside

- **x402 Spring Boot starter** — `@RequiresPayment` for sellers, a `RestClient` interceptor with a spend guard for buyers. Built on the official x402 Java SDK.
- **Agents on Spring AI 2.0** — planner → researcher → risk → synthesis, with a model router (cheap models for routine steps, stronger ones where it matters) and a data-classification policy.
- **Spend control** — per-run budgets, daily caps, payee allowlists, idempotency and human approval above a threshold, all checked before anything is signed.
- **Ledger** — double-entry, inbox/outbox, Kafka events, on-chain reconciliation.
- **RAG + evals** — hybrid retrieval over public KAP disclosures and news, with an eval harness that reports quality *and* USD per task.

## Quickstart (local)

Prerequisites are in [docs/SETUP.md](docs/SETUP.md): JDK 25, Node 22 + pnpm, Docker.

```bash
make help                      # list targets
make up                        # build images, start infra + services, wait for health
make test && make lint         # Gradle check (incl. Testcontainers) + web tests/lint
make web-dev                   # Vite on http://localhost:5173, proxies /api and /otlp
```

Open http://localhost:5173, press **Check again** on the System check card, then verify the
trace (browser → orchestrator → Postgres) in Jaeger:

```bash
make verify-trace TRACE_ID=<traceId shown on the card>   # or open the card's Jaeger link
make verify-trace                                       # no browser: orchestrator → Postgres only
```

Jaeger UI: http://localhost:16686. If port 6379 is taken on your host (e.g. a native Redis on
Windows), run `export VALKEY_HOST_PORT=16379` before `make up`. `make down` stops everything;
`make clean` also drops volumes.

## Stack

Java 25 · Spring Boot 4.1 · Spring AI 2.0 · PostgreSQL + pgvector · Kafka (Redpanda) · Valkey · React + Vite · OpenTelemetry · Terraform (AWS ECS Fargate)

## Docs

[Architecture](docs/ARCHITECTURE.md) · [Decisions (ADRs)](docs/adr/) · [Plan](docs/PLAN.md)

## License

Apache-2.0
