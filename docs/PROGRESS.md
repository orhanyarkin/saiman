# Progress

## Current milestone
M2 — Ingest + RAG: **in progress** on branch `m2-rag` (not pushed yet). Architect design pass approved 2026-09-29 with the human's answers: corpus = the official MKK KAP API free tier, a frozen 2023 snapshot (ADR-0010); no news source; MCP tools moved to M3; ~20 BIST tickers; questions endpoint 0.02 USDC; OpenAI key provided (hard limit $10 at the provider, daily cap in code). Tasks: T0 orchestrator (done: catalog, `Money` + retrieval contract in `libs/shared`, `libs/model-router` interfaces, ADR-0010/0011/0012, `docs/design/m2-rag.md`) · T1 agent-engineer `libs/model-router` · T2 ai-engineer `services/ingest` · T4 payments-engineer seller-api RAG endpoints · T5 infra (compose secrets, make targets, CI) · T7 orchestrator: live backfill to ≥5k chunks, re-run delta 0, paid question with ≥2 citations. Milestone-end security audit (`model: opus`) at the end.
Status: T0 committed; T1 and T2 running in parallel.
M1 (x402 starter + first paid endpoint) is done and merged (PR #10).

## Log
<!-- Newest first. One entry per merged task: date, what changed, how it was verified, what's next, open questions. -->

### 2026-09-29 — M1 closing: milestone-end security audit

**What changed**
- Ran the milestone-end `security-auditor` pass (`model: opus`) over the full M1 diff (178 files vs. `main`), with an explicit focus on T2's server flow — the one per-task review that got skipped under lean mode's "no re-check rounds" policy.
- Found one blocking issue, **M1-A (Medium)**: `X402SettlementFilter` matched a settlement's transaction hash against a regex without a null check. A facilitator answering `/settle` with `success:true` but no `transaction` field (malformed, buggy, or hostile) threw an NPE that was caught, logged and rethrown by the outer dispatch handler — but the `finally` block still unconditionally flushed the already-buffered handler body (with its headers) to the client, because nothing had reset it first. Net effect: content served with no confirmed settlement.
- Fixed: a null/malformed transaction hash now takes the same "ambiguous, reset, 402, keep the nonce claim" path as any other settle failure; the rest of the verified-settlement handling (building the client-facing response, publishing the settled event) is now wrapped so *any* unexpected exception in that zone routes through the same safe path instead of escaping to the filter's fail-open `finally`. `FakeFacilitator` gained `injectSettleSuccessWithoutTransaction()` to reproduce this from a test.
- `docs/THREAT_MODEL.md` corrected per the audit: exact retry/4xx-vs-429 behaviour, the Redis-vs-in-memory nonce store fallback and its clock-skew assumption, that facilitator-response records (unlike wire-input records) aren't null-checked by a compact constructor, and four new known-gaps rows (declared-vs-runtime async handler types, one circuit breaker shared by verify/settle counting 4xx as failures, the starter itself still following redirects by default, Valkey reachable from every app container).

**How it was verified**
- The audit itself probed rather than only read: 64 concurrent requests (half re-cased nonce/from) against replay protection — 1×200, 63×402, one `/verify`, one `/settle`; injected settle timeouts, 5xx, 429 and the missing-transaction case, each producing exactly one `/settle` call and a held claim; read the Resilience4j retry predicate by reflection to confirm `/settle` has no retry policy at all and 4xx/429 are excluded from `/verify`'s retry.
- After the fix: `./gradlew :libs:x402-spring-boot-starter:check` green (a new regression test, `settleSuccessWithoutAWellFormedTransactionIsTreatedAsAmbiguousNotCharged`, asserts 402, no leaked handler headers, no `PAYMENT-RESPONSE`, the failed (not settled) event fires with reason `ambiguous`, and a replay is rejected without reaching the facilitator again). `make test && make lint` both green.
- Per `CLAUDE.md`'s lean-mode review policy, M1-A is Medium (not Critical), so `test-runner`-equivalent verification (the test suite passing) was sufficient — no re-review round.

**What's next**
- Ask the human before pushing `m1-x402` and opening the PR to `main` (per their explicit instruction and rule 5).
- M2 (ingest + RAG) starts with its own architect design pass, per `docs/PLAN.md`.

**Open questions**
- None blocking. The known-gaps table in `docs/THREAT_MODEL.md` carries everything deferred to M2/M3/M4.

### 2026-09-29 — M1 T6: README quickstart, THREAT_MODEL.md, live Base Sepolia payment

**What changed**
- README: fixed the stale "built on the official x402 Java SDK" claim (ADR-0008: native v2, the official SDK is a reference only) and added a copy-paste x402 quickstart (two throwaway wallets, fund the buyer from the Circle testnet faucet, `make up`, `make x402-buy`/`-replay`/`-testnet-check`).
- `docs/THREAT_MODEL.md` (new): consolidates every payments-touching security review from M1 into one document — invariants K1–K3 (key custody, no-echo, hermetic CI), wire format and signing, server settlement, client payment, facilitator trust, seller-api, a known-gaps table, and how to re-verify each invariant on future changes. Linked from the README.
- Live payment on Base Sepolia: created a fresh throwaway buyer wallet (`make x402-new-wallet`), funded it from the Circle faucet, rebuilt and restarted the stack (`make up`, picking up T4's seller-api endpoint), then `make x402-buy` against `GET /v1/disclosures/THYAO/summary`.

**How it was verified**
- `curl` against the unpaid endpoint returned 402 with a decodable `PAYMENT-REQUIRED` header (amount `10000`, network `eip155:84532`, asset the Base Sepolia USDC contract, payTo the configured seller address).
- `make x402-buy`: HTTP 200, the fixture summary body, and a settlement tx hash.
  - **Tx:** `0xe9811f2d8473aa1fd4c7bef4483f50adf1edf586812f3df6f9f63272a5e9d50a` — https://sepolia.basescan.org/tx/0xe9811f2d8473aa1fd4c7bef4483f50adf1edf586812f3df6f9f63272a5e9d50a
  - Confirmed independently via `eth_getTransactionReceipt` on the public Base Sepolia RPC (not just the app's own report): `status: success`, block `47469579` (2026-09-29T18:44:06Z), a USDC `Transfer` event for exactly `10000` atomic units (0.01 test USDC) from the buyer to the configured seller payTo address.
- `make x402-replay`: HTTP 402, "this payment authorization has already been used" — the same signed payload is rejected the second time. Confirms AC1 (a console client pays on Base Sepolia and gets data) and AC2 (a replayed payload is rejected).
- README quickstart's doc links checked to resolve; the commands match the actual `make` targets and `.env.example`.
- Buyer key never left `secrets/buyer.key` (0600, git-ignored); the orchestrator never read `.env` or the key file directly, only ran the allowlisted `make check-x402-env` / `make x402-*` targets.

**What's next**
- Milestone-end security audit (`model: opus`), covering T2's server flow end to end: replay protection, verify → handler → settle order, no retry on `/settle`, 4xx/429 handling — since T2's per-task re-check round was skipped under the lean review policy.
- Then M1 closes; M2 (ingest + RAG) starts with its own architect design pass.

**Open questions / carried to later milestones**
- M2/M3 (orchestrator): paying `RestClient` must use the starter's non-redirecting request factory (`X402RestClients`), with its own test; M3's `SpendGuard` counts *held* reservations against run/daily budgets and reconciles them via `authorizationState(from, nonce)` after `validBefore`; 402 and seller response bodies are untrusted tool output (prompt injection) once fed back to an LLM; idempotency keys should be opaque random values; don't wire the client interceptor into an LLM-driven retry loop before the M3 budget plane exists.
- M3 design/ADR: paid handlers run before settlement succeeds, so an attacker can make one run for free by draining the buyer's balance between verify and settle — needs per-payer/IP limits on unsettled attempts and, for expensive (LLM-backed) handlers, an opt-in settle-before-serve mode.
- M4: reconcile ambiguous settlements on chain; events already carry `(from, nonce)` as the dedupe key; don't trust the facilitator's `success` field as ground truth.
- Infra: add `osv-scanner` on the generated starter POM in CI; Valkey has no auth in the compose stack (any container on the network could flush the nonce store) — add `requirepass`/ACL or network segmentation before anything beyond the local demo.
- T4 follow-ups (non-blocking): the nonce is claimed before the ticker's `@Pattern` validation, so a malformed-ticker request burns a valid authorization (buyer-side self-inflicted, no seller-side exploit); add a seller-api test where the service throws (500, not settled, no leaked handler headers); springdoc/OpenAPI for seller-api; M2's RAG-backed `DisclosureSummaryService` must keep "no request-time file/DB access keyed by raw client input".
- All details in `docs/THREAT_MODEL.md`.

### 2026-09-28 — M1 in progress (T0 done)

**What changed**
- T0 (orchestrator): catalog entries (web3j crypto 6.0.0, Resilience4j 2.4.0 core, Boot-managed restclient/data-redis/validation/configuration-processor, Testcontainers core); `saiman.published-library` convention (sources/javadoc jars, Apache-2.0 POM, resolved versions, no Boot BOM import); `@Tag("testnet")` tests excluded unless `-PincludeTestnet`; ADR-0008 (native x402 v2), ADR-0009 (secrets and wallet keys), ADR-0006 amendment (source-side redaction); PLAN/ARCHITECTURE/ADR-0005 wording; `X402_NETWORK` dropped from `.env.example`; shared contract in `docs/design/m1-x402.md`.

**How it was verified**
- `./gradlew build spotlessCheck` green; `publishToMavenLocal` POM inspected (concrete versions, no `dependencyManagement`).

**Done since**
- T5 (infra): `make up` requires a valid `X402_SELLER_PAYTO_ADDRESS` (never echoes it); compose-policy scans every profile for key material (env_file, secrets, configs, mounts, env) with 17 fixture tests in CI; seller-api gets payTo + healthy Valkey; `make x402-*` targets. Reviewed by reviewer + security-auditor; blocking findings fixed and verified by the fixture tests.
- T1 (payments-engineer): x402 v2 records + codec, canonical EIP-3009 authorization, low-s signature policy, EIP-712 digest over a fixed Base Sepolia USDC domain. Known-answer tests: EIP-712 Mail vector; the x402 spec payment payload recovers to its payer; domain separator and typehash pinned; `@Tag("testnet")` check of `DOMAIN_SEPARATOR()` on chain (passed locally). 93 tests. Review round 1 found 8 reviewer + 3 security blocking issues (non-canonical inputs defeating the replay key, high-s signatures, payload in exception causes, strict decoding of facilitator bodies, `extra` typing); all fixed. POM pins jackson-databind 3.1.5 and bcprov 1.86; Vert.x/ConnId/KZG/tuweni excluded with a build check.

**Task history** (T0–T6, all committed on `m1-x402`)
- T0: catalog, `saiman.published-library` convention, ADR-0008/0009, ADR-0006 amendment, shared design contract (`docs/design/m1-x402.md`).
- T1 (payments-engineer): x402 v2 wire model, codec, canonical EIP-3009 authorization, low-s signature policy, EIP-712 digest over the fixed Base Sepolia USDC domain. Known-answer tests (EIP-712 Mail vector, the spec's own payment payload, a `@Tag("testnet")` check against the live contract). Review found 8 reviewer + 3 security blocking issues (non-canonical inputs defeating the replay key, high-s signatures, payload in exception causes, facilitator-body decoding too strict, `extra` typing) — all fixed; POM pins patched Jackson/BouncyCastle versions.
- T2 (payments-engineer): server filter, `@RequiresPayment`, `HttpFacilitatorClient`, observability. Review found blocking issues (async handlers settling without a body, fail-open when the interceptor isn't registered, handler headers leaking on settle failure, PAYMENT-RESPONSE built from unvalidated facilitator output, Redis nonce store never actually selected, `/supported` handshake breaking CI's hermeticity) — all fixed; 198 starter tests green, no test contacts x402.org. Its per-task security re-check was skipped once lean mode's "no re-check rounds" policy landed — covered instead by the milestone-end audit.
- T3 (payments-engineer): client interceptor, `SpendGuard`, console-buyer sample. Two review rounds found and fixed: releasing a spend reservation after a signature had already been sent, a TOCTOU race in `PropertiesSpendGuard`, redirects forwarding `PAYMENT-SIGNATURE` to another host, a settlement response with no valid tx hash being committed anyway. Security re-check confirmed all resolved.
- T4 (payments-engineer): seller-api's first paid endpoint (`GET /v1/disclosures/{ticker}/summary`, 10000 atomic units). Reviewer and security-auditor approved with no blocking findings. Fixture data covers 3 BIST tickers, clearly labelled, each citation carrying a source URL and retrieval time.
- T5 (infra): `make up` requires a valid `X402_SELLER_PAYTO_ADDRESS` (never echoed, read from `.env` via an allowlisted script); compose-policy scans every profile for key material with 17 fixture tests in CI; `make x402-*` targets.
- T6: see the entry above.
- Also on this branch: a lean-mode PreToolUse hook broke worktree-isolated agents ("command name computed at runtime") — fixed by rewriting to a single literal script call, merged via PR #9.

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
