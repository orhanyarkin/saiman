# Saiman

*sAIman — from Turkish "sayman", treasurer.* The AI treasurer for research agents.

Agentic-payments reference platform: financial research agents that discover and **pay for** data/tools per request over **x402** (HTTP 402 + stablecoin, testnet only), with a spend-control plane, a double-entry ledger with on-chain reconciliation, RAG over Turkish capital-markets disclosures, and an eval harness. Portfolio project — optimise for correctness, clarity and low running cost over feature count.

**One backend stack (Java 25 + Spring Boot 4.1 + Spring AI 2.0), one frontend stack (React + Vite).** See ADR-0005. Don't introduce another language or framework without a new ADR.

**Prefer widely known tools** (Redis, Kafka, Postgres and the like). If you want a less common alternative, justify it in an ADR and ask the human for approval first (ADR-0020).

Read `docs/ARCHITECTURE.md` and `docs/PLAN.md` before starting any milestone. Track progress in `docs/PROGRESS.md`.

## Repository layout (Gradle multi-project, Kotlin DSL)

```
settings.gradle.kts
gradle/libs.versions.toml         version catalog — every dependency version lives here
libs/
  x402-spring-boot-starter/       x402 server filter + @RequiresPayment + RestClient interceptor, auto-configured (open-source, publishable)
  shared/                         event contracts (records), Money type, retrieval contract, OTel helpers
  model-router/                   the only path to LLM/embedding providers: tiers, data-class policy, daily USD cap (ADR-0011)
services/
  seller-api/                     paid endpoints behind x402 + MCP tools (@McpTool)
  orchestrator/                   agents (Spring AI ChatClient) via libs/model-router, spend control, SSE/WebSocket run stream
  ledger/                         double-entry ledger, inbox/outbox, reconciliation job
  ingest/                         MKK KAP API → chunk → embed → pgvector (Spring AI VectorStore); internal hybrid retrieval
  evals/                          golden-set evals, cost/quality reports (Spring Boot CLI app)
web/                              React 19 + Vite + TypeScript + TanStack Router/Query + Tailwind + shadcn/ui
deploy/
  compose/                        Postgres+pgvector, Apache Kafka (KRaft), Redis, OTel collector, all services
  terraform/aws/                  bootstrap (state, OIDC) + module demo-lite (ECS Fargate ARM); enterprise (EKS + Helm) is a stretch goal
scripts/capture-demo/             exports a deployed run as JSON for the static replay demo (ADR-0004)
docs/                             ARCHITECTURE, PLAN, PROGRESS, SETUP, KICKOFF, adr/, events/
```

## Non-negotiable rules

1. **Testnet only.** x402 runs on Base Sepolia with test USDC. Never add mainnet networks, real private keys or real funds. Wallet keys come from env vars and are never committed or logged.
2. **Secrets.** Never read, print or commit `.env`, `*.pem`, `*.key` or anything under `secrets/`. Add new variables to `.env.example`.
3. **Spend limits live outside the LLM.** Budget checks are deterministic code in the spend-control plane (Redis + Postgres), never a prompt instruction. Every paid call needs: per-run budget, global daily cap, payee allowlist, idempotency key.
4. **Money is integers.** `long` atomic units + asset decimals (USDC = 6), wrapped in a `Money` record. No `double`/`float`; no `BigDecimal` on the wire. Format only at the UI edge.
5. **Idempotency + outbox.** Every state change that emits an event goes through the transactional outbox; consumers dedupe through an inbox table keyed by event id, in the same transaction as the state change.
6. **LLM calls go through the model router** (`libs/model-router`, a `ChatClient`/`EmbeddingModel` facade over Spring AI; ADR-0011). No code calls a provider directly. The router enforces the data-classification policy (ADR-0003).
7. **No real cloud changes.** Terraform `init`/`validate`/`fmt`/`plan` are fine; `apply`/`destroy` are for the human only.
8. **Public data only** in RAG. Respect KAP terms; store source URL + retrieval time per chunk; answers cite chunk ids.
9. **Don't install toolchains** (no `curl | bash`, no SDKMAN installs). Everything is preinstalled (docs/SETUP.md). If something is missing, stop and tell the human.

## Conventions

- Java 25, Spring Boot 4.1, Spring Framework 7, Spring AI 2.0. Gradle Kotlin DSL + version catalog. Virtual threads enabled.
- Group id `io.github.orhanyarkin`; base package `io.github.orhanyarkin.saiman.<service>`; the starter uses `io.github.orhanyarkin.x402`.
- Package by feature inside each service (`payment`, `budget`, `run`…), not by layer. Records for DTOs and events. JSpecify nullness annotations.
- Web: Spring MVC + `RestClient`; Problem Details (RFC 9457) for errors; springdoc-openapi for OpenAPI specs.
- Data: Spring Data JDBC or `JdbcClient` (no JPA/Hibernate), Flyway migrations, one Postgres with one schema per service, no cross-schema queries.
- Messaging: Spring Kafka against Apache Kafka (KRaft, single node locally). Topics `<domain>.<event>.v1`; schemas in `docs/events/`. Spring Modulith's event publication registry may back the outbox (ADR if used).
- Resilience: Resilience4j (retry with jitter, circuit breaker, timeout) on every outbound client.
- Observability: Micrometer + OpenTelemetry (traces + metrics) on every endpoint, consumer and LLM/payment call; LLM and payment paths record tokens and USD.
- Tests: JUnit Jupiter, AssertJ, Testcontainers via `libs/test-support` (one Postgres, Kafka and Redis per test JVM, a database per context; ADR-0020), `@SpringBootTest` slices; the property-testing approach is decided by an ADR at the start of M4 (not jqwik: its maintainers ask AI agents not to use it). Spotless (palantir-java-format) + Error Prone.
- Web app: strict TypeScript, TanStack Query, typed client from OpenAPI (`openapi-typescript`), Vitest + Playwright.
- Commits: Conventional Commits, one logical change each.

## Commands

```bash
make up            # docker compose up (deploy/compose)
make test          # ./gradlew check + web tests
make lint          # ./gradlew spotlessCheck + web lint
make eval          # run evals against local stack
make cost-report   # LLM + infra cost summary from telemetry
make capture-demo  # export runs from a deployed stack for the replay demo
```
If a `make` target doesn't exist yet, create it when you add the thing it runs.

## Definition of done (every task)

- Tests written and passing, linters clean.
- Container image builds for linux/amd64 (local) and linux/arm64 (Fargate ARM) — Buildpacks (`bootBuildImage`) or Dockerfile.
- OTel spans/metrics on new paths; cost metrics on LLM/payment paths.
- `docs/PROGRESS.md` updated (what changed, how verified, what's next, open questions).
- New design decision → new or updated ADR in `docs/adr/`.

## Agent orchestration protocol

The main session is the **orchestrator**: it plans, delegates, integrates and verifies — it doesn't write large features itself.

1. Start each milestone with a read-only design pass from `architect`; check it against the acceptance criteria in `docs/PLAN.md`.
2. Split work into tasks touching **disjoint directories** and delegate to the owner:
   `payments-engineer` (libs/x402-spring-boot-starter, services/seller-api, services/ledger) · `agent-engineer` (services/orchestrator, libs/model-router) · `ai-engineer` (services/ingest, services/evals, prompts, router configs) · `frontend` (web) · `infra` (deploy, CI, Makefile).
   Shared contracts in `libs/shared` and `gradle/libs.versions.toml` change only through the orchestrator, before parallel work starts.
3. After each task: `test-runner` verifies and `reviewer` reviews the diff **once**. `security-auditor` reviews **once per task**, and only for code touching payments, wallets, budgets or auth; plus **one audit at the end of each milestone** (`security-auditor-milestone`).
4. Send blocking findings back to the owner; `test-runner` verifies the fixes. Re-review follows the active profile's `reReview` policy (see Usage profiles). Then merge, run `make test && make lint`, update `docs/PROGRESS.md`.
5. **Stop and ask the human** before: pushing to remote, anything in rule 7, adding a paid external service, changing an accepted ADR, or when blocked by a missing tool or credential.

Delegation prompts must be self-contained (goal, owned dirs, acceptance criteria, relevant ADRs) and must **list the exact files to read** (paths, not "read the docs"): subagents don't see this conversation.

### Usage profiles (lean mode)
The active profile is in `.claude/profile` (`pro` or `max`); its values live in `.claude/profiles/<name>.json`.
Switch with `make profile-pro` / `make profile-max` (shows the diff, then rewrites agent frontmatter and settings); `make profile-show` prints the active one.

Profile-dependent (read them from the active profile, never hard-code them here):
- Concurrent subagents (`CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS` in `.claude/settings.json`) and each agent's model, effort and `maxTurns` (`.claude/agents/*.md` frontmatter).
- The milestone-end audit uses `security-auditor-milestone` (its own `maxTurns`); per-task audits use `security-auditor`.
- Per-invocation model overrides (`invocationOverrides`): which tasks call `payments-engineer` or `security-auditor` with `model: opus`.
- Re-review policy (`reReview`): `pro` = no re-check rounds, re-review only for Critical findings; `max` = additionally one security-auditor re-review of the fix commits after High or Medium findings in payment, ledger or budget code.
- Advisor use (`advisor.consultOn`): `max` consults the advisor on architecture decisions and the milestone-end audit.

Profile-independent:
- Main session model `opusplan` (Opus in plan mode, Sonnet when executing).
- A PreToolUse hook (`.claude/hooks/filter-test-output.sh`) rewrites a plain `./gradlew` check/test/build, `make test|lint` or `pnpm test|lint|typecheck` command into one literal call of `.claude/hooks/run-filtered.sh`, which prints only failures/errors and the final summary and keeps the exit status. Compound commands (`;`, `&&`, `|`, `$(…)`, redirects) are never rewritten; append `# nofilter` for full output.
- **Same error twice → stop.** An agent (or the orchestrator) that gets the same tool error twice must stop and report it instead of retrying the same command.

## The human is learning Spring

The author is strong in .NET and building depth in Spring. In every task report, add a short **"Spring notes"** section: which Spring features were used and why, and the one trade-off worth being able to explain in an interview. Prefer idiomatic, well-documented Spring over clever code.

## Where work runs

- **Local (WSL2 terminal, Claude Code CLI)**: orchestrator session, LSP diagnostics, integration and merges.
- **Cloud sessions**: self-contained tasks on one service; `scripts/cloud-setup.sh` installs Java 25, pnpm and Terraform. No LSP in cloud, so run the full test suite before reporting done.

# Compact instructions

When compacting, keep: current milestone and acceptance criteria, task ownership and status, decisions (with ADR numbers), failing tests and causes, open questions for the human. Drop file contents, full logs and dead-end exploration.
