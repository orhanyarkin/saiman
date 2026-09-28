# Progress

## Current milestone
M0 — Skeleton: **done** (branch `m0-skeleton`, PR to main awaiting the human's review and merge). Next: M1 — x402 Spring Boot starter + first paid endpoint.

## Log
<!-- Newest first. One entry per merged task: date, what changed, how it was verified, what's next, open questions. -->

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
