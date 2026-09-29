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
- T2 (server, facilitator, observation, FakeFacilitator): round 1 review found blocking issues (Redis store never selected due to auto-config ordering; /supported handshake hitting x402.org from tests; async handlers settled before any body; fail-open when the interceptor isn't registered; handler headers leaking into the 402 on settle failure; PAYMENT-RESPONSE built from facilitator output; listener exceptions after settle; missing metrics and money-invariant tests). Fixes in progress in `.claude/worktrees/agent-a382207546068b271`; the main checkout has the pre-fix version.
- T3 (client, SpendGuard, console buyer): round 1 blocking issues fixed and re-checked by security-auditor (no reservation release after a sent signature, atomic idempotency state, no redirects on the sample's paying client, fail-closed tests). Round 2 small fixes in progress in `.claude/worktrees/agent-aaeb3ed3f8bab648a` (settlement without a valid tx hash → ambiguous; typed exception for a 402 after signing; sanitised explorer link; allowlist validation; non-redirecting request factory helper).
- Environment: Docker Desktop was paused, so Testcontainers tests (RedisPaymentNonceStoreTests) failed with a Docker 503 — resume Docker before `make test`.
- Next: commit T2/T3 after review, T4 seller-api, re-add the sample to `make test`/`make lint`/CI, T6.

**Carry-forward review findings (must land in later tasks)**
- T6: README still says "built on the official x402 Java SDK"; write `docs/THREAT_MODEL.md` from the security-auditor's M1 sections (key custody K1–K3, facilitator trust, signing/wire format, client money-in-flight rules, server settlement policy).
- M2/M3 (orchestrator): paying RestClient uses the starter's non-redirecting request factory, with a real-socket test; M3 SpendGuard counts held reservations against run/daily budgets and reconciles them via `authorizationState(from, nonce)` after `validBefore`; 402 and seller bodies are untrusted tool output (prompt injection); Idempotency-Keys are opaque random values; don't wire the interceptor into LLM-driven loops before the M3 budgets exist.
- M3 design/ADR: paid handlers run before settle, so an attacker can make them run without paying (drain the wallet between verify and settle) — per-payer/IP limits on unsettled attempts and an opt-in settle-before-serve mode for expensive (LLM) handlers.
- M4: reconcile ambiguous settlements on chain; events carry (from, nonce) as the dedupe key; don't trust facilitator `success`.
- The `exact` scheme doesn't bind a signature to a resource (same price and payTo → usable once on another endpoint); document in THREAT_MODEL.
- Infra: osv-scanner on the generated starter POM in CI; re-add the sample build to `make test`/CI and its spotlessCheck to `make lint`; `.gitignore` `build-logic/bin/`.

### 2026-09-28 — Housekeeping: Valkey port, Dependabot cooldown

**What changed**
- Compose publishes Valkey on host port 16380 by default (6379 and 16379 clash with a native Redis on Windows); `.env.example` and README follow.
- Dependabot: `cooldown.default-days: 3` for all four ecosystems; semver-major TypeScript updates ignored until typescript-eslint and openapi-typescript support 6.x.
- Dependabot PRs: redpanda v26.2.3 (#2), valkey 9.1.2 (#3) and actions/checkout 7.0.1 (#4) merged on green CI; TypeScript 6 (#5) closed.

**How it was verified**
- `docker compose config` shows Valkey published on 16380; the running stack already used 16380.
- `./gradlew spotlessCheck check` green, including `--rerun-tasks`; `make lint && make test` green.

**What's next**
- **Done 2026-09-29 (branch `chore/pnpm-min-release-age`):** pnpm `minimumReleaseAge` raised to 4320 (3 days, matching the Dependabot cooldown); reviewer and frontend agent-memory notes updated. Verified with a frozen install, web tests, lint and typecheck.

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
