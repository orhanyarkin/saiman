# Progress

## Current milestone
M1 — x402 starter + first paid endpoint: **in progress** on branch `m1-x402`. Plan: architect design pass approved 2026-09-28 (ADR-0008 native x402 v2, ADR-0009 secrets). Tasks: T0 orchestrator (catalog, publishing convention, ADRs) · T1 payments-engineer starter core/evm · T2 server + facilitator + FakeFacilitator · T3 client + SpendGuard + console buyer · T4 seller-api endpoint · T5 infra (compose payTo check, make x402-*, CI) · T6 README quickstart + live Base Sepolia payment.
Status: T0, T1, T5 done (committed on `m1-x402`); T2 and T3 in review/fix rounds. M0 merged to main.

## Log
<!-- Newest first. One entry per merged task: date, what changed, how it was verified, what's next, open questions. -->

### 2026-09-28 — M1 in progress (T0 done)

**What changed**
- T0 (orchestrator): catalog entries (web3j crypto 6.0.0, Resilience4j 2.4.0 core, Boot-managed restclient/data-redis/validation/configuration-processor, Testcontainers core); `saiman.published-library` convention (sources/javadoc jars, Apache-2.0 POM, resolved versions, no Boot BOM import); `@Tag("testnet")` tests excluded unless `-PincludeTestnet`; ADR-0008 (native x402 v2), ADR-0009 (secrets and wallet keys), ADR-0006 amendment (source-side redaction); PLAN/ARCHITECTURE/ADR-0005 wording; `X402_NETWORK` dropped from `.env.example`; shared contract in `docs/design/m1-x402.md`.

**How it was verified**
- `./gradlew build spotlessCheck` green; `publishToMavenLocal` POM inspected (concrete versions, no `dependencyManagement`).

**Done since**
- T5 (infra): `make up` requires a valid `X402_SELLER_PAYTO_ADDRESS` (never echoes it); compose-policy scans every profile for key material (env_file, secrets, configs, mounts, env) with 17 fixture tests in CI; seller-api gets payTo + healthy Valkey; `make x402-*` targets. Reviewed by reviewer + security-auditor; blocking findings fixed and verified by the fixture tests.
- T1 (payments-engineer): x402 v2 records + codec, canonical EIP-3009 authorization, low-s signature policy, EIP-712 digest over a fixed Base Sepolia USDC domain. Known-answer tests: EIP-712 Mail vector; the x402 spec payment payload recovers to its payer; domain separator and typehash pinned; `@Tag("testnet")` check of `DOMAIN_SEPARATOR()` on chain (passed locally). 93 tests. Review round 1 found 8 reviewer + 3 security blocking issues (non-canonical inputs defeating the replay key, high-s signatures, payload in exception causes, strict decoding of facilitator bodies, `extra` typing); all fixed. POM pins jackson-databind 3.1.5 and bcprov 1.86; Vert.x/ConnId/KZG/tuweni excluded with a build check.

**Status (updated 2026-09-28 evening)** — resume here if a session stops
- Committed after T1: codec log-injection fix (3fcd40e; T1 security re-check otherwise all resolved), starter build deps for T2/T3 (8ed1e28) and micrometer-core (71a29d1), `make` reads only the public `X402_SELLER_PAYTO_ADDRESS` from `.env` via an allowlisted reader (28b7943).
- T2 (server, facilitator, observation, FakeFacilitator): implemented, 119 tests green together with T3; uncommitted in the main checkout (copied from `.claude/worktrees/agent-a382207546068b271`). Reviewer + security-auditor round 1 in progress. Known gaps: `x402.payments` counter / `x402.payment.amount` summary not yet added; no dedicated facilitator-timeout test.
- T3 (client, SpendGuard, console buyer): round 1 blocking findings being fixed in `.claude/worktrees/agent-aaeb3ed3f8bab648a` (release after a sent signature, PropertiesSpendGuard race, signing-failure leak, redirects, missing fail-closed tests). The main checkout has T3's pre-fix version — replace it with the worktree version when done.
- Next: commit T2/T3 after review, T4 seller-api, re-add the sample to `make test`/`make lint`/CI, T6.

**Carry-forward review findings (must land in later tasks)**
- T1–T3: no Bean Validation on key/address properties (Boot prints the rejected value); validate in code without echoing; output-capture tests.
- T2: facilitator host allowlist in code (x402.org + loopback), https, no redirects, timeouts, response size cap, `/supported` handshake at startup (fail closed); log the host at INFO. M4: reconcile tx hashes on chain, don't trust facilitator `success`.
- T3: key read at runtime only (bootRun not configuration-cache compatible); `new-wallet` writes repo-root `secrets/buyer.key`, refuses to overwrite, creates 0600 atomically; `testnet-check` signs a self-transfer with minimal value and short validity; sample excludes `testnet` tags itself, `mavenLocal()` limited to `io.github.orhanyarkin`, sample spotless in `make lint`; re-add the sample build to `make test` (after `./gradlew check`) and CI.
- T6: README still says "built on the official x402 Java SDK"; add security-auditor's key-custody/facilitator-trust text to `docs/THREAT_MODEL.md`.

### 2026-09-28 — Housekeeping: Valkey port, Dependabot cooldown

**What changed**
- Compose publishes Valkey on host port 16380 by default (6379 and 16379 clash with a native Redis on Windows); `.env.example` and README follow.
- Dependabot: `cooldown.default-days: 3` for all four ecosystems; semver-major TypeScript updates ignored until typescript-eslint and openapi-typescript support 6.x.
- Dependabot PRs: redpanda v26.2.3 (#2), valkey 9.1.2 (#3) and actions/checkout 7.0.1 (#4) merged on green CI; TypeScript 6 (#5) closed.

**How it was verified**
- `docker compose config` shows Valkey published on 16380; the running stack already used 16380.
- `./gradlew spotlessCheck check` green, including `--rerun-tasks`; `make lint && make test` green.

**What's next**
- **Follow-up PR after 2026-09-29 07:00Z:** raise pnpm `minimumReleaseAge` to 4320 (3 days, matching the Dependabot cooldown) and update the reviewer and frontend agent-memory notes that still describe 1440. Not done now because 12 lockfile entries (vitest 5.0.2, @tanstack/react-query 5.104.0, @types/node 26.6.3, csstools) are younger than 3 days until 2026-09-26 06:37Z + 3 days, so a frozen install fails.

**Open questions / watch**
- Possible flake: one local `./gradlew spotlessCheck check -q` run exited 1 with no output; it did not reproduce in two further runs (one with `--rerun-tasks`). Watch CI for an unexplained backend failure.

### 2026-09-28 — M0 Skeleton (T0–T6)

**What changed**
- **T0 (orchestrator):** Gradle 9.8 multi-project build, version catalog, `build-logic/` convention plugins (`saiman.java-conventions`, `saiman.java-library`, `saiman.spring-boot-service`), Spotless + palantir-java-format, Error Prone + NullAway (JSpecify mode), Boot BOM as a Gradle platform (ADR-0007). `.env.example` OTLP fix: Boot 4.1 maps `OTEL_EXPORTER_OTLP_ENDPOINT` (verified in the 4.1.1 jar), so it is `http://localhost:4318` (HTTP), not `:4317` (gRPC).
- **T1 (infra):** `deploy/compose` with pgvector, Redpanda, Valkey, OTel Collector 0.161 and Jaeger 2.21, all on 127.0.0.1; services in the `apps` profile with `pull_policy: never` and no `.env` passthrough; `Makefile`; CI (backend, web, compose-policy, native amd64/arm64 images, SHA-pinned actions); Dependabot; `verify-trace.sh`, `wait-for-health.sh`, `check-compose-policy.sh`.
- **T2 (agent-engineer):** orchestrator `GET /api/v1/ping` (`JdbcClient` `select now()`), Flyway schema `orchestrator`, JDBC spans via datasource-micrometer.
- **T3 (payments-engineer):** empty `X402AutoConfiguration` registered via `AutoConfiguration.imports`; seller-api (8081) and ledger (8082) skeletons.
- **T4 (ai-engineer):** ingest (8083) skeleton; evals CLI (`web-application-type=none`, exempt from an HTTP health endpoint per the human's decision; runner gated by `saiman.evals.run-on-startup`).
- **T5 (frontend):** React 19 + Vite 8 + strict TS 5.9 + TanStack Router/Query + Tailwind 4/shadcn; OpenTelemetry web SDK gated by `VITE_OTEL_ENABLED`; System check card with a Jaeger link; pnpm `minimumReleaseAge` strict with no exclusions.
- **T6 (orchestrator):** review fixes across services: actuator limited to health/info (access, not just exposure), health details hidden, OTLP metrics export off unless the runtime enables it, `RestTestClient` tests with 404 checks for other actuator endpoints, Testcontainers as a context-scoped bean, a tracing test that checks span parentage. Images renamed to `ghcr.io/orhanyarkin/saiman-<svc>:dev` because the Docker Hub user `saiman` belongs to a third party. Valkey host port configurable (`VALKEY_HOST_PORT`). README quickstart.
- Decisions: ADR-0006 (tracing, Jaeger behind the collector) and ADR-0007 (build-logic, bootBuildImage, native arm64 runners) accepted. jqwik dropped; the property-testing approach is decided by an ADR at the start of M4.

**How it was verified**
- Fresh clone of `m0-skeleton`: `make test && make lint` green; `./gradlew check --no-build-cache --rerun-tasks` green — 46 JVM tests (7 classes) + 7 web tests.
- `make up`: all four services UP in ~11 s after start (run with `VALKEY_HOST_PORT=16380`, see open questions).
- `make verify-trace` (no browser): orchestrator + JDBC spans found in Jaeger for a generated `traceparent` — this also proves the `OTEL_EXPORTER_OTLP_ENDPOINT` mapping end to end.
- Through the Vite dev server: `/api` and `/otlp` proxies work, and `make verify-trace TRACE_ID=…` accepts a trace with `saiman-web`, `orchestrator` and JDBC spans.
- Browser check (human, 2026-09-28): a real click on the System check card produced a trace that `make verify-trace TRACE_ID=<id>` accepted (web → orchestrator → Postgres).
- `bootBuildImage` succeeds for all five services on amd64 with Java 25 (Liberica 25.0.4). arm64 is built by the CI `images` job only.
- `check-compose-policy.sh` passes on the real file and fails on a deliberately bad one.
- Every task went through test-runner and reviewer; T1 and T3 also through security-auditor.

**What's next**
- Human reviews and merges the M0 PR. The arm64 image job runs on push to main only, so it proves itself after the merge.
- M1: x402 starter + first paid endpoint, starting with an architect design pass.

**M1 open items / carried risks**
- Resolved: a native Redis on the Windows host holds 6379 and 16379, so compose now publishes Valkey on 16380 by default (`VALKEY_HOST_PORT` still overrides it).
- Before M1 (security-auditor): the core collector cannot redact attributes; decide between source-side redaction (Micrometer `ObservationFilter`) and a collector build with the `redaction` processor, so `X-PAYMENT` payloads never reach Jaeger or Grafana Cloud. Record it in ADR-0006.
- Before M1: per-service secrets (compose `secrets:` / configtree), and only the orchestrator container gets the buyer key.
- M1 starter contract (security-auditor): fail closed — missing or invalid payment config stops startup, no `enabled` flag, network allowlist `eip155:84532` enforced in code.
- Before the first ledger REST endpoint: service-to-service authn and ownership checks (ADR).
- Deferred as planned: OpenAPI client generation (M3/M5), per-service Postgres roles (M4), cross-origin CORS for live mode (M5/M6), `docs/THREAT_MODEL.md` (security-auditor's baseline is in its agent memory).
