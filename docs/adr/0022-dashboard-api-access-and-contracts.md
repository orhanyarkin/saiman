# ADR-0022: Dashboard API access and contracts

Status: Accepted (2026-10-05). Implements M5; no accepted ADR changes (ADR-0006's note that cross-origin access belongs to a separate deployment stays valid; ADR-0002 is unchanged, landing pre-rendering moves to the M6 Cloudflare build).

## Context
The dashboard is a static React SPA (ADR-0002) that must call the orchestrator (:8080) and the ledger (:8082). Both APIs guard `/api/**` with an exact `Host` allowlist (port stripped) and an `X-Saiman-Csrf` header, and have no authentication until M6. The browser must never hold key material, and the contract between backends and the SPA should be checked on every build, not trusted by convention.

## Decision
- **Same origin, path-routed, no CORS.** `/api/v1/ledger/**` and `/api/v1/reconciliation/**` go to the ledger, the rest of `/api/**` to the orchestrator, `/otlp` to the OTel collector. No path rewriting for `/api/**`, so `eventsUrl` works as returned; the one deliberate exception is `/otlp/v1/traces`, whose `/otlp` prefix is stripped because the collector serves `/v1/traces` (Vite and nginx do the same, and only that path is allowed). The two services' path namespaces must stay disjoint.
  - Dev and e2e: Vite `server.proxy` (and the same under `vite preview`), `changeOrigin: false` so the browser's `Host: localhost:<port>` reaches the backends and their guards keep checking. Never `allowedHosts: true`.
  - `make up`: compose service `web` (nginx, exact pinned tag, profile `apps`, `127.0.0.1:8088:80`) serving `web/dist` read-only plus `deploy/compose/nginx.conf`. `proxy_set_header Host $http_host`, `server_name localhost 127.0.0.1` plus a default server that answers 444, SSE locations without buffering and with a read timeout above the 15 s heartbeat, no `Access-Control-*`, no OPTIONS handling. No secrets, no environment on this service (compose policy enforces it).
  - CORS on the backends is rejected: it would add an allowed-origin policy to code with no authentication. CSRF stays covered because the SPA sends `Content-Type: application/json` plus `X-Saiman-Csrf: 1`, which a cross-site page cannot do without a preflight that nothing answers.
- **Contracts are checked in.** springdoc-openapi (API only, no UI, `springdoc.api-docs.enabled=false` at runtime) in orchestrator and ledger; an `OpenApiContractTests` per service compares the normalised `/v3/api-docs` with `docs/api/<service>.openapi.json` (`-Dsaiman.openapi.update=true` rewrites it). The web generates `web/src/lib/api/generated/*.ts` with `openapi-typescript` (checked in; CI runs `--check`) and calls through `openapi-fetch`.
- **SSE is not in OpenAPI.** `docs/events/agent.run-step.v1.schema.json` plus golden fixtures (one per `RunEventData` type, generated and compared by a write-mode test) are the contract; the web keeps a hand-written discriminated union with a runtime guard, tested against the same fixtures.
- **Read surface is minimal and GET-only** (orchestrator: runs list, run payments, approvals, spend; ledger: payments list/detail, revenue, reconciliation history). Bodies never contain a nonce, a key or `payment_key` (it embeds the nonce). Money is `{atomicUnits, asset, decimals}`, integers ≤ 2^53-1; the SPA formats only at the edge with BigInt/string arithmetic and refuses unsafe integers.
- **Replay-compatible by construction.** All reads go through one `apiGet(path)` and run streams through `subscribeRunEvents`; the capture format `{capturedAt, environment, responses: {"<GET path>": body}, runEvents: {runId: Envelope[]}}` is read by the e2e fixture server now and by M6's replay mode and `make capture-demo` later.
- **Accessibility is measured, not hoped for:** `@axe-core/playwright` in every e2e spec (fail on serious/critical) and Lighthouse (accessibility category, ≥ 0.90 on every route) against `vite preview` with the fixture server.

## Consequences
+ One origin, no cross-origin policy to get wrong; the backend guards stay the second line of defence.
+ API drift fails the build twice (Java snapshot, TypeScript `--check`).
+ M6 replay needs no second data layer.
− nginx is one more container; any local process can still call `/api` (no authn until M6), so the UI says "testnet, local only".
− Code-first OpenAPI changes whenever the code does; the snapshot makes that deliberate.
− Playwright and Lighthouse need a browser, so they run in `make e2e` / `make lighthouse` and CI, not in `make test`.

## Alternatives
- **CORS with an origin allowlist on the backends:** more surface in code without authn.
- **springdoc Gradle plugin:** boots the real app (DB, Kafka), not hermetic.
- **`@lhci/cli`:** pins an older Lighthouse than the Node API.
