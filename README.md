# sAIman

*From Turkish "sayman" — treasurer.*

**The AI treasurer for research agents.** Agents research BIST companies and crypto assets, pay for data and tools per request over [x402](https://x402.org) (HTTP 402 + stablecoin, Base Sepolia testnet), stay inside budgets enforced in code — never by the model — and every payment lands in a double-entry ledger that always balances and is reconciled against the chain.

> Status: under construction. See [docs/PLAN.md](docs/PLAN.md) for milestones and [docs/PROGRESS.md](docs/PROGRESS.md) for what's done.

## What's inside

- **x402 Spring Boot starter** — `@RequiresPayment` for sellers, a `RestClient` interceptor with a spend guard for buyers. A native x402 v2 implementation (`exact` scheme, EVM, Base Sepolia only; the official Java SDK is v1-only and is used as a reference — ADR-0008).
- **Agents on Spring AI 2.0** — planner → researcher → risk → synthesis, with a model router (cheap models for routine steps, stronger ones where it matters) and a data-classification policy.
- **Spend control** — per-run budgets, daily caps, payee allowlists, idempotency and human approval above a threshold, all checked before anything is signed.
- **Ledger** — double-entry, inbox/outbox, Kafka events, on-chain reconciliation.
- **RAG + evals** — hybrid retrieval over public KAP disclosures, with an eval harness that reports quality *and* USD per task.

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

Jaeger UI: http://localhost:16686. Valkey is published on host port **16380** (not 6379), because
6379 and 16379 clash with a native Redis on Windows; if 16380 is taken too, run
`export VALKEY_HOST_PORT=<port>` before `make up`. `make down` stops everything; `make clean` also
drops volumes.

## Secrets for M2 (RAG)

`ingest` needs the MKK API credential and an OpenAI key; `seller-api` needs the OpenAI key.
Both live as files under the ignored `secrets/` directory and reach only those containers as
compose secrets (ADR-0009); they are never environment variables. `make up` creates missing
files as empty ones (the apps then fail closed when they need them).

```bash
mkdir -p secrets && chmod 700 secrets
read -rs MKK && printf '%s' "$MKK" > secrets/mkk_credentials && unset MKK && chmod 600 secrets/mkk_credentials   # paste the portal's base64 value
make secrets-from-dotenv        # copies only OPENAI_API_KEY from .env to secrets/openai_api_key (0600); FORCE=1 to overwrite
make secrets-check              # present / empty / absent + file mode per secret, never contents
```

Then `make infra-up && make ingest-backfill` loads disclosures, `make ingest-status` shows
per-ticker progress, and `make rag-ask` pays (test USDC) for a question.

## Quickstart (x402, Base Sepolia testnet)

The x402 starter is fail-closed: `make up` refuses to start without a valid seller payout
address, and seller-api won't come up without one either. Everything here is **testnet only**
(rule 1) — test USDC has no value, and every wallet below is throwaway.

1. **Create two throwaway wallets** (a seller payout address, a buyer signing key) with any
   wallet tool, e.g. [Foundry](https://getfoundry.sh)'s `cast wallet new`, or:
   ```bash
   make x402-new-wallet                    # writes secrets/buyer.key (0600), prints only the address
   ```
   Put the seller address in `.env` (copy it from `.env.example` first) as
   `X402_SELLER_PAYTO_ADDRESS=0x...` — never the private key, and never commit `.env` or
   `secrets/`. `make` reads only this one public variable from `.env`; everything else (LLM
   keys, the buyer key) must be exported in your shell or left in `secrets/`.

2. **Fund the buyer** from the [Circle testnet faucet](https://faucet.circle.com/) (Base Sepolia,
   20 test USDC every 2 hours, no account needed) — paste the buyer's **address**, not the key.
   The buyer needs no ETH: EIP-3009 payments are gasless for the payer.

3. **Start the stack** and pay for the one paid endpoint:
   ```bash
   make up                                 # fails fast if X402_SELLER_PAYTO_ADDRESS is missing/invalid
   export X402_BUYER_PRIVATE_KEY=0x...     # or rely on secrets/buyer.key from step 1
   make x402-buy                           # pays seller-api's disclosure summary (0.01 test USDC) and prints the tx hash
   ```
   `make x402-buy` prints a [BaseScan](https://sepolia.basescan.org) link for the settlement
   transaction. `make x402-replay` resends the same signed payload and must get **402** back
   (replay protection); `make x402-testnet-check` does a read-only `/verify` call against the
   public facilitator (never `/settle`, moves no funds).

See [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md) for what's guaranteed (and what isn't yet) about
key custody, replay and settlement, and [docs/design/m1-x402.md](docs/design/m1-x402.md) for the
wire-level contract.

## Stack

Java 25 · Spring Boot 4.1 · Spring AI 2.0 · PostgreSQL + pgvector · Kafka (Redpanda) · Valkey · React + Vite · OpenTelemetry · Terraform (AWS ECS Fargate)

## Docs

[Architecture](docs/ARCHITECTURE.md) · [Decisions (ADRs)](docs/adr/) · [Plan](docs/PLAN.md) ·
[Threat model](docs/THREAT_MODEL.md)

## License

Apache-2.0
