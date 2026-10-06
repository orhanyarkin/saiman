# Threat model

Scope: the x402 payment path added in M1 — `libs/x402-spring-boot-starter` (wire format, signing,
server settlement, client payment), `services/seller-api`'s first paid endpoint, and the secrets
that make them work. Later milestones (spend-control plane, ledger, RAG, orchestrator) get their
own sections here as they land; this file is a living document, updated by every
payments/wallets/budgets/auth-touching security review (`CLAUDE.md`, "Agent orchestration
protocol").

Everything below assumes **testnet only** (`CLAUDE.md` rule 1): Base Sepolia, test USDC with no
real value. The mitigations are still real — the point is to build the habits and the code paths
that would matter on a network where the money is real.

## Assets

- The buyer's EIP-3009 private key (testnet). Whoever holds it can sign payments up to whatever
  budget the holder enforces.
- Signed EIP-3009 authorizations in flight. A signed authorization is a **bearer instrument**:
  anyone holding it can settle it (once) until `validBefore`, regardless of who sent it or to whom.
- The seller's payout address (public, not a secret, but wrong values are funds-destroying: the
  zero address or the USDC contract itself would burn a payment).
- The facilitator's trust: it is believed for *liveness* (does `/verify`/`/settle` respond), never
  for *truth* (its claims are re-derived locally wherever that's possible).
- The Redis-backed payment nonce store (replay defence-in-depth ahead of the on-chain nonce).

## Trust boundaries

```
buyer (console / M3 orchestrator) --PAYMENT-SIGNATURE--> seller-api --/verify,/settle--> x402.org facilitator
                                                              |
                                                        Redis nonce store
```

- The **facilitator** (`https://x402.org/facilitator`) is a third party outside this codebase's
  control. It sees the resource being purchased, the payer's address and the payment amount.
- The **seller** never holds a signing key (ADR-0009): it only has a public payout address, so a
  compromised seller-api container cannot move funds.
- The **buyer's key** stays as close to the payer as possible: the console buyer in M1, and only
  the orchestrator container from M3, via compose `secrets:` + configtree, never an environment
  variable (ADR-0009).

## Invariants

These are checked mechanically, not just by review:

- **K1 — key custody.** Only the orchestrator (from M3) and the local console buyer may hold the
  buyer key, and the orchestrator only through compose `secrets:` + configtree, never an
  environment variable. Enforced by `scripts/check-compose-policy.sh` (rejects `env_file`,
  `environment`, `secrets:` on a non-orchestrator service, `configs:` and bind mounts sourcing a
  key), with fixture tests in `scripts/test-check-compose-policy.sh` run in CI.
- **K2 — no echo.** No tool, script, validation error, exception message or log line ever prints a
  value that might be a key or a signature. Validation failures state *what's* wrong, never the
  rejected value (`scripts/check-x402-env.sh`, `X402Codec`, `Eip3009Authorization`,
  `PrivateKeyPaymentSigner`, `X402ClientProperties.toString()`, all covered by tests that plant a
  marker string and assert it never reaches output).
- **K3 — hermetic CI.** CI never holds a wallet key and never contacts `x402.org`. Tests tagged
  `@Tag("testnet")` are excluded from every build, including the standalone console-buyer sample,
  which doesn't inherit the root build's convention plugins and has to exclude them itself.

## Wire format and signing (`core/`, `evm/`)

- The network and asset are fixed in code (`TestnetAssets`: `eip155:84532`, the Base Sepolia USDC
  contract), never a runtime property — the server only ever offers this pair, the client rejects
  any other, and the signer only ever builds this one EIP-712 domain. The domain is never built
  from wire-supplied `extra`/chainId fields. Known-answer tests: the EIP-712 spec's `Mail` example
  (domain separator, struct hash, digest and the published signature, byte for byte) and the x402
  v2 spec's own payment-payload example, which recovers to its stated payer under this domain
  (separator `0x71f17a3b2ff373b803d70a5a07c046c1a2bc8e89c09ef722fcb047abe94c9818`); a
  `@Tag("testnet")` test additionally checks that separator against `DOMAIN_SEPARATOR()` on the
  live contract.
- **Canonical form before hashing or keying.** web3j's EIP-712 encoder alone is lax — it accepts
  `"0x2710"`/`"010000"`/`"+10000"` as equivalent uints, any letter case or missing `0x` for
  addresses, even non-hex characters (silently mapped to a digit), and it *skips* null fields
  entirely (so a missing `validAfter` collides with a missing `validBefore`). Left unchecked, that
  would let an attacker resend a payload with the nonce's case flipped and defeat the replay key.
  `Eip3009Authorization`'s compact constructor requires strict canonical hex/decimal forms before
  anything is signed, hashed or used as a key; `canonicalNonceKey()` (lowercased `from` + `nonce`)
  is the only key the nonce store ever uses, and it's taken only after the signature has been
  recovered — never from the payload's claimed `from`.
- **Signature policy.** 65-byte `r‖s‖v`, `v ∈ {27, 28}`, `1 ≤ r < n`, `1 ≤ s ≤ n/2` — the same
  low-`s` rule FiatToken's on-chain `ecrecover` enforces, so nothing verifies locally that the
  chain would reject. The signer itself always emits low-`s` (RFC 6979, via web3j).
- **No payload in errors.** No exception message or cause chain — codec, signer, key parsing —
  carries a header value, a payload, a signature or key material (ADR-0006 amendment). This
  extends to the decoder's own machinery: unrecognised-property paths are sanitised to short
  identifier segments so an attacker-chosen JSON key can't inject control characters into a log
  line or a Problem Details body.
- **Decoder limits.** A 16 KB cap on the decoded payload, checked by encoded length *before*
  base64 decoding; strict duplicate-key detection; no scalar coercion (a JSON number can't become
  a wire string); required fields enforced by each record's compact constructor rather than
  arriving as silent nulls — for wire-input records (`PaymentPayload`, `Eip3009Authorization`, …).
  Facilitator-response records (`SettlementResponse`, `VerifyResponse`) are decoded tolerantly and
  have no such constructor, so a field a hostile or buggy facilitator omits (e.g. `transaction`)
  really can come back `null` at runtime despite not being `@Nullable` in source — every call site
  that reads one of these fields must check for that itself (M1-A was exactly this gap).
- **Residual risk.** The private key's `String`/`BigInteger` forms can't be zeroised from the JVM
  heap — mitigated only by never exposing `/actuator/heapdump` or `/actuator/env`. A third party
  can front-run settlement (see "Ambiguous settlement" below): the seller gets paid but a given
  buyer's own `/settle` call may still fail; only on-chain reconciliation (M4) resolves this for
  certain.
- **Supply chain.** web3j's `crypto` module is the only dependency used for signing; its heavier
  optional pieces (Vert.x, ConnId, a KZG blob library, the tuweni ecosystem) are excluded and a
  build check keeps them from reappearing. Jackson and BouncyCastle are pinned directly in the
  starter's own `build.gradle.kts`, above the versions web3j would otherwise pull in transitively,
  so a consumer without the Spring Boot BOM still gets patched versions in the published POM.

## Server settlement (`server/`, `facilitator/`)

- **Order: verify → serve (buffered) → settle.** The response is captured in a
  `ContentCachingResponseWrapper` and only flushed to the real client after a successful
  `/settle` on a 2xx handler response — including on the paths the happy-path description above
  doesn't cover: any unexpected exception while finishing a verified settlement (a malformed
  facilitator response, an unforeseen failure) is caught and routed through the same
  ambiguous-failure path (M1-A, fixed and covered by a test) rather than left to fall through and
  flush the still-buffered body regardless. `/settle` is never retried (an ambiguous double-settle
  is worse than a false 402); `/verify` and the startup `/supported` handshake retry on network
  errors, timeouts, 5xx, and an undecodable or oversized body, never on 4xx/429 or while the
  circuit breaker is open (the facilitator's own rejection is authoritative, not a transient
  condition) — a 4xx on `/verify` releases the nonce claim (this server will never settle under
  it), while a well-formed `isValid:false` response keeps it.
- **Fail closed at startup**, for every configuration where the verify→settle order can't hold:
  no `@RequiresPayment` handler may return an async type (`Callable`, `DeferredResult`,
  `CompletableFuture`, `SseEmitter`, `StreamingResponseBody`, …) — an async dispatch would let the
  filter settle before the handler has produced a body; the interceptor must actually be
  registered on the handler mapping (an app overriding `WebMvcConfigurationSupport` directly would
  otherwise silently serve paid content for free); a missing, zero, or malformed
  `x402.server.pay-to` stops the app before it binds a port; the facilitator's `/supported` must
  confirm `exact` on `eip155:84532` before any paid handler is allowed to exist. This check is on
  the handler's *declared* return type; a handler declared to return a wider type (e.g. `Object`)
  that returns an async value at runtime is not caught here (tracked as a gap below).
- **Settlement failure never leaks the handler's response.** On a settle failure the buffered
  response is fully reset — body, status *and* headers the handler set (a signed download URL, a
  `Set-Cookie`) — before writing the 402; headers set by *outer* filters (CORS, security headers)
  are restored from a snapshot taken before the handler ran.
- **`PAYMENT-RESPONSE` is server-built**, from locally-known values only (the recovered signer as
  `payer`, the offer's own `network`/`amount`, a settlement transaction hash validated against
  `0x[0-9a-fA-F]{64}`, missing or malformed treated as an ambiguous failure, never as success) —
  never a re-serialisation of whatever the facilitator's response contained, which could otherwise
  carry attacker- or facilitator-sized `extensions` past Tomcat's response header limit and turn a
  *settled* payment into a 500.
- **Replay** is defence-in-depth ahead of the on-chain EIP-3009 nonce: an atomic pre-settle claim
  on `(network, asset, canonicalNonceKey)`, released with compare-and-delete so an expired claim
  someone else re-acquired is never deleted out from under them. It's held in Redis (`SET NX`,
  TTL tied to `validBefore`) when a `StringRedisTemplate` bean exists — seller-api always has one —
  or, failing that, per-JVM-instance in memory with a startup `WARN` (single-instance only, so it
  offers no protection across a fleet); a Redis outage answers `503`, never silently falling back
  to an unprotected state. This assumes every instance's clock is within a few seconds of the
  others'; a lagging instance can accept a replay its peers would already reject. Cross-endpoint
  reuse of one signed payload at the *same* price and payee is inherent to the `exact` scheme, not
  a bug in this starter — a signature doesn't bind to one specific resource.
- **Request-derived metadata is never trusted.** The resource URL sent to the facilitator and
  recorded in events comes from an explicit `x402.server.public-base-url` (or the request path
  alone), never `Host`/`X-Forwarded-*`. The payload forwarded to the facilitator is rebuilt
  server-side, never the client's raw `resource`/`extensions`.
- **Events carry `(from, nonce, value, validBefore, payer)`** — enough for M4 to reconcile an
  ambiguous settlement via `authorizationState(from, nonce)` on chain — and never the signature.
- **Ambiguous settlement / side effects before settle** (open design item, not a bug): the handler
  runs *before* settlement succeeds, so an attacker can make a paid handler run for free by
  draining the payer's balance (or using a `validBefore` that's about to expire) between `/verify`
  and `/settle`; every signed payload, funded or not, costs one `/verify` call, which is a resource
  a flood of unfunded signatures could exhaust (the circuit breaker limits the blast radius, not
  the cost). Mitigation is **not yet built**: a per-payer/IP limit on unsettled attempts (M3,
  Redis) and, for expensive handlers (LLM calls from M2 on), an opt-in settle-before-serve mode
  need their own ADR before M2/M3 land.
- **Per-request deadline.** A per-request deadline is min(deadline, validBefore - now -
  settleMargin); a refusal for lack of time is per-request and never negative-cached; settleMargin
  includes the facilitator connect timeout.

## Client payment (`client/`)

- **A signature that left the process is money in flight until `validBefore`.** The client's
  `SpendGuard` reservation is released *only* when nothing was ever sent (a rejection before
  signing, or a signing/encoding failure) — never after a `PAYMENT-SIGNATURE` request has actually
  gone out. A 402 on the paid retry does **not** prove no charge happened (the seller's own 402 on
  a settle timeout is exactly this case) and raises a distinct, typed exception rather than a
  plain 402, so a caller can't mistake "declined before anything was signed" for "signed and sent,
  outcome unknown" and simply retry under a fresh idempotency key.
- **Idempotency state is one atomic map**, not a check-then-act pair of sets — the original
  two-`Set` design allowed two threads to both reserve and commit the same key under contention.
- **The seller's `PAYMENT-RESPONSE` is untrusted input**, validated before it can commit a
  reservation (a missing or malformed `transaction` field is treated as ambiguous, not success) and
  sanitised before it reaches metrics, logs or the terminal (control characters stripped, only a
  well-formed transaction hash gets an explorer link printed).
- **Redirects must be disabled on every paying client.** Spring Boot 4's HTTP client follows
  redirects by default; an unpatched paying client would forward `PAYMENT-SIGNATURE` and
  `Idempotency-Key` to whatever host a 302 on the paid retry points at. The starter ships
  `X402RestClients.nonRedirectingRequestFactory()` for this; the M2/M3 orchestrator's paying
  `RestClient` uses it: `PaidResourceClientConfiguration` builds a JDK client with `HttpRedirects.DONT_FOLLOW`, and two
  real-socket tests prove a 302 (on the first request and on the paid retry) is not followed.
- **Plaintext is refused except for loopback and exact allowlisted hosts.** `x402.client.allowed-plaintext-hosts`
  (empty by default) holds exact host names only: entries with `*`, `/`, `:`, `@`, a leading or
  trailing dot or non-DNS characters fail startup, matching is against `URI#getHost()` (so
  `http://seller-api@evil.com` is `evil.com`), and a non-empty list fails startup on any network
  other than Base Sepolia. Tests cover sibling (`seller-api2`), suffix (`seller-api.evil.com`),
  prefix (`evilseller-api`) and userinfo tricks.
- **The plaintext allowlist checks the first hop only.** Redirects, JVM proxies and single-label DNS
  outside compose are the operator's responsibility; the non-testnet check in
  `PlaintextHostAllowlist` is unreachable while the network is hard-coded (testnet-only is
  enforced by `TestnetAssets`).
- **`SpendGuard.signed` is the last fail-closed gate.** It runs after signing and before the paid
  retry is sent (never given the signature itself); if it throws, the signed authorization is
  dropped unsent, the reservation is released and the exception rethrown. A real-socket test proves
  zero `PAYMENT-SIGNATURE` requests reach the stub and the idempotency key is reusable afterwards.
- **402 and other seller response bodies are untrusted tool output**, not just untrusted HTTP: once
  the orchestrator (M2+) feeds tool results back to an LLM, a seller-controlled error message is a
  prompt-injection surface like any other retrieved text.
- Current gap, accepted for M1: `PropertiesSpendGuard` enforces only a per-request maximum and a
  payee allowlist. There is no per-run budget or daily cap yet (`CLAUDE.md` rule 3's full
  requirement) — **don't wire the client interceptor into an LLM-driven retry loop before the M3
  SpendGuard exists**, and M3's guard must count *held* reservations (signed, outcome unknown)
  against the budget, not just committed ones.
- Orchestrator `/api` requires bearer tokens since M6 (ADR-0023, section "M6 hardening" below); the Host allowlist (DNS rebinding) and JSON + `X-Saiman-Csrf` (CSRF) still apply first to every request regardless of path form (raw URIs containing `;`, `%` or `//` are refused). Spend limits: Postgres is the authority; the app DB role is `orchestrator_app` (DML only, ADR-0024) and `committed_atomic` is monotonic through an owner-owned trigger.

## Facilitator trust

- The facilitator is allowlisted in code (`x402.org` over https, or loopback for tests) — nothing
  reads a facilitator URL from a wire message or a client-controlled header. No redirects are
  followed on facilitator calls either. A `/supported` handshake at startup requires `exact` on
  `eip155:84532` before any paid handler is allowed to exist.
- What a malicious or compromised facilitator **cannot** do: redirect funds. The EIP-3009 signature
  binds `to`, `value` and `nonce`, and the EIP-712 domain binds the chain id and the USDC contract
  — none of that is something the facilitator controls.
- What it **can** do: lie about `/verify` or `/settle` (claim success without settling, or claim
  failure after actually settling), return a fabricated transaction hash, or simply be slow (bounded
  by timeouts and a circuit breaker, at the cost of availability, not correctness). This is why
  server-built response fields, the hash-format check, and — eventually — on-chain reconciliation
  in M4 exist: the facilitator's `success` field is a hint, never the source of truth.

## seller-api and future paid handlers

- `services/seller-api`'s first paid endpoint proves the starter's fail-closed contract end to end
  for a real handler, not just the starter's own synthetic test fixtures: no key material on the
  seller, a missing/invalid payout address refuses to start, 400/404 never settle.
- The fixture disclosure data is served from an in-memory map built once from a fixed classpath
  glob — the `{ticker}` path variable never reaches a filesystem or database lookup, so path
  traversal via that input is structurally impossible, not merely filtered. **This must be
  re-verified whenever `DisclosureSummaryService`'s implementation changes** — M2's RAG-backed
  version swaps the implementation behind the same interface, and it must not introduce
  request-time file or database access keyed by unsanitised client input.
- Any future `@RequiresPayment` handler inherits this starter's guarantees automatically (buffered
  settlement, fail-closed startup checks) but is responsible for its own input handling — validate
  path/query input the same way, and don't log or echo it before it's validated.

## Model router (`libs/model-router`, M2)

- **Assets:** the OpenAI API key (cost-bearing; the provider project has a hard limit) and the daily USD cap.
- **Trust boundaries:** the process environment (several `OPENAI_*` variables silently change the SDK's behaviour), in-process callers of `ModelRouter` (trusted; they choose the data class in code), and the provider (trusted only for usage numbers, and even those are cross-checked by an estimate).
- **Invariants:**
  - The key is only ever sent to `https://api.openai.com/v1`. The base URL is a constant: Spring AI would otherwise honour `OPENAI_BASE_URL` and send the key (and the prompt) wherever the variable points, which a probe confirmed. A child-JVM test proves an env-configured server receives nothing; the compose policy rejects `OPENAI_*`/`SPRING_AI_OPENAI_*`/`OPENAI_LOG` in any service environment, and the router logs a startup warning if they are set on a host run.
  - The key reaches a container only as a mounted secret file (ADR-0009 amendment); `toString()`, exceptions, logs and metric tags never carry it (a 401-stub test asserts this).
  - Every model call is preceded by an **atomic reservation** of its worst-case cost (estimated input + the tier's required `max-completion-tokens`, times `1 + maxRetries`) against the daily cap and settled to the real usage afterwards; when the outcome is unknown (cancelled or failed stream, missing or corrupt usage, a call that fails after being sent) the estimate stays. 200 concurrent callers never exceed the cap. A per-request model override is priced at that model's price (or the highest configured one).
  - The data class comes from code, never from a request or an LLM; no route may allow `SENSITIVE`; the check runs before any client exists.
  - It fails closed: Redis down, a corrupt or negative counter, a missing key, or no shared guard at startup (unless `saiman.router.cost-guard=memory` is set explicitly) all stop calls.
- **Cost scopes (M3):** a run's model spend is reserved against its scope (`run:{id}:llm`, Redis Lua, atomic, pinned budget, TTL) before the global day, and the scope reservation is released if the day refuses. The scope id and budget are advisor params set by trusted code only (never model output or user text); the id is restricted to `[A-Za-z0-9_-]{1,64}`, the budget is clamped to `max-scope-budget-usd-micros` and pinned by the first reservation, so a later call cannot raise it. The scope rides in the request context, so every round trip of Spring AI's tool-calling loop is charged under it. `require-cost-scope=true` (orchestrator) refuses an unscoped call before anything is sent; a scope handed to a router without a `ScopedCostGuard` is refused, never ignored. Fails closed like the day cap. Fallback to a backup OpenAI model happens only on connect/timeout/429/5xx, is priced at the dearer of the two models, and inherits the route's data classes.
- **Pinned ceiling (M3):** `DefaultModelRouter.HARD_CEILING_USD_MICROS = 700_000` is compiled in; a configured `daily-cap-usd-micros` above it (or negative) fails startup. Raising it is a code change plus an ADR-0011 amendment, never a property or environment variable, so a leaked or mistyped setting cannot lift the day cap.
- Callers cannot override route limits. Spring AI 2.0.1 does not merge per-request options with the model's defaults, so the router rebuilds every request from the route's options and copies only an allowlist (temperature, tool callbacks/context, output schema); `n>1`, `extraBody`, `customHeaders`, another `baseUrl`/`apiKey` are rejected, and the completion limit is clamped to the route's.
- **Residual:** the provider-side limit is the last hard stop; Redis has no authentication (known gap); the SDK's own env-driven logging cannot be pinned from code; tool-loop usage aggregation and per-run budgets arrive with M3.

## Ingest (`services/ingest`, M2)

- **Source and licence:** the official MKK KAP data API, free tier = a frozen 2023 test environment (ADR-0010). The Basic credential is a mounted secret file, sent only to the configured MKK host with redirects off, a 5 calls/minute limiter, retry with jitter honouring `Retry-After`, and a circuit breaker; it is never logged (tests plant a marker and assert its absence in logs, exceptions, the request log and the DLQ). No KAP text is committed (fixtures are synthetic).
- **Personal data:** `/blockedDisclosures` is honoured every run: blocked disclosures are never fetched, indexed chunks are deleted and the title is blanked; attachments are never fetched or redistributed.
- **Integrity:** deterministic chunk ids, content-hash skip before any paid embedding, embedding outside the DB transaction, crash repair without duplicates, correction/cancellation chains resolved in either processing order, an advisory lock on a dedicated non-pooled connection (a pooled connection would leak it on a failed unlock), a DLQ table that stores only status codes or the SQLState — never document text.
- **Retrieval input:** `RetrieveRequest` is bounded (query 500 chars, ≤10 tickers, topK ≤ 20); the lexical query is OR-joined words through `websearch_to_tsquery` (operators are inert); chunk ids are matched against a strict pattern before the database; all SQL is parameterised; query and chunk text never appear in logs.
- **Credential egress:** the MKK Basic credential is only sent to an allowlisted https MKK host (`apigwdev.mkk.com.tr`, `apigw.mkk.com.tr`), checked at startup (the MKK analogue of the constant OpenAI base URL); the compose policy forbids `SAIMAN_INGEST_MKK_*` and `SPRING_CONFIG_*` env overrides. `make ingest-backfill` mounts a directory containing only ingest's own two secrets.
- **Browser-borne attacks on loopback:** `/internal/**` rejects a foreign `Host` header (DNS rebinding) and non-JSON POST/PUT/PATCH/DELETE without `X-Saiman-Internal` (a simple cross-site request such as `retry-dlq`); no CORS is configured, so preflights are never answered. Both checks apply to every request regardless of path form (only `/actuator/health` probes skip the Host check), and raw request URIs containing `;`, `%` or `//` are refused with 400, so encoded or parameterised paths cannot dodge them. This is not authentication.
- **Residual:** `/internal/**` (retrieval, `admin/retry-dlq`) has **no in-app authentication**: it must stay on the compose network and `127.0.0.1` and must never sit behind a public load balancer (it would give paid content away for free). The corpus is a static 2023 snapshot; a changed KAP document at an already-indexed index is not re-fetched (by design).

## seller-api RAG endpoints (M2)

- **The exposure:** the handler calls a paid LLM **before** settlement (the verify → serve → settle order of M1), and its failures (422 no valid citations, 502 malformed output) are attacker-steerable. Left alone, a wallet holding test USDC could burn the daily cap with authorizations that never settle, then deny honest buyers.
- **Controls (bounded, not eliminated):** a handler that has spent money calls `X402PaymentContext.markWorkDone`, after which a non-2xx answer **keeps the nonce claim** (the same authorization can not buy a second run); `UnsettledRunGuard` (Redis, atomic, fail-closed) limits each recovered payer to 2 concurrent runs and 30 runs per hour, and the whole service to 100 *unsettled* runs per UTC day (a settled payment gives its slot back) — over any limit the answer is 429 and no model is called; LLM endpoints require an authorization valid for ≥45 s and run under a 25 s deadline (a late answer is a 503, never settled); body ≤ 4 KB is enforced before payment verification; unknown tickers cost no embedding; summary generation is single-flight with a short negative cache.
- **Injection surface:** the question and KAP text reach only the *user* message, inside delimited blocks with `<`/`>` neutralised; the reply must be a bounded JSON object and every citation is rebuilt from the retrieved chunks (ids, titles and `www.kap.org.tr` URLs are never taken from the model); links in the model's prose are stripped; cached summaries are re-validated on read. The answer text itself is still untrusted data for any downstream agent (M3).
- **Response hygiene:** every failure is a non-2xx with a fixed Problem Details message — no question, chunk text, exception detail or key — and a non-2xx is never settled.
- **Ordering fact:** `RequiresPaymentInterceptor` claims the nonce and calls `/verify` before argument resolution and body validation; a 400/404 then releases the claim and costs one facilitator `/verify`, while 422/502/503 after model work keep it.
- **Run guard first:** the per-payer run guard is taken at handler entry, before any ingest call, so a payer at its limit cannot replay one signature for free retrievals/embeddings.
- **Untrusted issuer text:** citation `title`/`excerpt` and answer text are KAP-issuer text passed through a link scrubber (scheme-relative, `javascript:`/`data:`, bare domains); the M3 agent must treat tool results as data, never instructions, and the web UI must render them as plain text.
- **Deadline vs. authorization window (deferred):** the 25 s deadline starts when the handler starts, so `/verify` time in `preHandle` is not counted and a model call can start with little time left; the 45 s `minWindowSeconds` is a constant while the deadline and facilitator timeouts are configurable. Worst case: a settle after `validBefore` (a free run or an ambiguous settle). Deriving the deadline from the authorization's `validBefore` belongs with the M3 settle-before-serve ADR.
- **Residual:** closed in M4b for both RAG endpoints (ADR-0021, upfront flow): the seller settles before the LLM call, so the unsettled day budget no longer applies to them. See "Upfront flow and credit notes (M4b)". An x402 payment does not bind the request body, so a signature can be spent once on a different question.

## Orchestrator and spend control (`services/orchestrator`, M3)

- **Assets:** the buyer key (compose file secret, never an env var), the run budget, the daily USDC cap and the LLM cost scope.
- **Trust boundaries:** the model and everything it reads (KAP text, the user's question, tool results) are untrusted; the HTTP caller of `/api` (bearer token since M6); the seller (trusted only after an x402 offer passes the payee allowlist and the per-request maximum).
- **Invariants (ADR-0013):** a payment is signed only after `BudgetSpendGuard.reserve` succeeds in one Postgres transaction (run row, then UTC day row locked in a fixed order; a DB CHECK and an immutable-budget trigger back it); only intents created by code (opaque 128-bit key, never from model text) can be paid; the model sees exactly two tools with `ticker`/`question` parameters and cannot name a URL, payee, amount, budget or key; approvals only open the threshold gate and can never raise a budget or the daily cap; a signed payment with an unknown outcome is HELD and keeps counting until M4 reconciles it; on restart RESERVED intents are released, SIGNED ones held, unfinished runs fail as `INTERRUPTED` with a terminal event.
- **Tool results:** only allowlisted fields reach the model (answer text, chunk id, KAP-pattern URL, title), stripped of control and bidi characters, delimited as data; citations are rebuilt by code from the run's retrieved evidence; the final answer is plain text.
- **Events:** the `run_event` log carries no keys, nonces or signatures (tx hash only); `seq` is gap-free per run; `agent.run-step.v1`.
- **Proven by tests (hermetic):** budget blocks before signing; a fully compromised scripted model with injected text cannot raise a budget, reach another payee or URL, or approve itself; root-span cost attributes equal the database totals.
- **Free text:** model and seller text is data: every free-text field leaving the process is scrubbed (no `<>`, links, controls, bidi, tag or invisible characters); UIs render it as plain text only. The free-text question is declared `DataClass.INTERNAL`: users must not enter personal data (UI note in M5).
- **Availability:** run availability is only partly protected (auth since M6, but no per-token rate limit); a run has a wall-clock deadline and spend stays bounded by the per-run, daily and LLM-scope caps.
- **HELD from honest paths.** Since M4b, a seller 4xx/5xx on the RAG endpoints is answered after an upfront settle: it moves money (paid and credited), so the HELD intent resolves as spent, not released. The buyer cannot tell a seller's deterministic refusal from an outcome it cannot know, so all of these become HELD and stay counted until M4's `authorizationState` reconciliation: a seller 429 (per-payer hourly limit), a seller 503 (router cap or run guard), a 402 for `window_too_short`/`window_too_large` (clock skew beyond about -15 s/+5 s; one compose host shares a clock, multi-host deployments need NTP), a buyer read timeout while the seller settles, and a seller 422/502 after work is done. The orchestrator therefore keeps its own hourly paid calls below the seller's per-payer limit, and its read timeout (65 s) covers the handler deadline, the facilitator timeouts and the margin up to `validBefore`.
- **Residual:** `/api` uses static bearer tokens (no expiry, no per-user identity; ADR-0023); the owner DB credential sits in the app container (see M6 hardening); a held reservation can block a run until M4; the final answer is free text from the model (a display field: the UI and any consuming agent must treat it as data).

## Ledger and reconciliation (M4)

- **Kafka is untrusted until M6.** It runs without authentication or ACLs, so anyone on the compose network can write `payments.*` records. The ledger treats every record as a claim, never as a fact: one forged record must not be able to break the consumer or blind the audit.
- **The ledger is a derived, auditable view.** The spend-control plane (budgets, allowlist, idempotency) never reads Kafka or the ledger; money only moves through the orchestrator's own checks and the chain. A forged record can at worst add wrong lines to a view that reconciliation then compares with Base Sepolia.
- **Every ledger input is range-bounded**, in the shared records and again in the schema (V3): network and asset fixed to Base Sepolia test USDC, `validBefore` before 2100, amounts up to 2^53-1, one payment per `(payer, nonce)`, `meta.occurredAt` within `[2025-01-01, now + 1 day]`, producer bound to the book (buyer facts from `orchestrator`, seller facts from `seller-api`), no scalar coercion, no duplicate JSON keys, no unknown fields. Time and amount arithmetic is overflow-checked; sums are `numeric`/`BigInteger`. Reconciliation batches reserve half their slots for payments with a reported tx, so a flood of forged authorizations cannot starve real ones; a bogus tx hash from one producer cannot freeze a payment; transfers are paired with their own `AuthorizationUsed` by log order.
- **DLT = quarantine needing a human.** Only deterministic rejections (malformed or conflicting facts, and database errors that always fail the same way for that record, such as constraint violations) are dead-lettered, with bounded `kafka_dlt-*` headers and no exception text or stack trace; conflicts also leave a CONFLICTING_FACT row in `reconciliation_mismatch`. Every string in a ledger input that reaches the database is charset-bounded; constraint, data and encoding errors are quarantined like malformed facts; connection, timeout and lock errors are retried, so no single bad record can stall a partition. Those transient errors are retried with back-off without limit and never dead-letter a valid fact; an unclassified error (a bug) is retried for at most an hour, then dead-lettered with `outcome=exhausted`. `saiman.ledger.dlt` counts quarantines; `outcome=failed` means even the DLT send failed and the record was skipped. Connection, insufficient-resource and operator-intervention SQLSTATEs (classes 08, 53, 57) count as transient whatever wraps them. Schema or permission drift is retried (up to an hour) rather than quarantined, so it stalls rather than loses events.
- **Reconciliation batches reserve half their slots for payments with a reported tx and order corroborated payments first;** forged facts with a bogus tx can still delay re-checks until broker authentication (M6); the due backlog and oldest-unchecked age are exported as gauges (`saiman.ledger.reconciliation.due`, `saiman.ledger.reconciliation.oldest_unchecked_seconds`). Uncorroborated (seller-only) and suspect (`TX_NOT_FOUND` / `TX_NOT_FOR_AUTHORIZATION`) payments queue one and six hours behind their last check, so they are delayed, never starved.
- **Nonces in `payments.*` are not bearer secrets** (an EIP-3009 authorization also needs the signature, which is never published), but they stay out of `ledger.*` events, reports and logs, and `org.springframework.modulith` must stay at INFO or above (its DEBUG output can include event payloads).
- **One RPC trust root.** The orchestrator's resolver and the ledger's reconciliation read the same Base Sepolia endpoint through `libs/evm-rpc` (chain id check, host allowlist, no redirects). A compromised RPC can mislead both at once; that is accepted for a testnet demo and noted for a production design (second independent RPC or a light client). The resolver trusts the RPC for both the safe block timestamp and the authorization state; the local-clock bound limits the damage from a skewed timestamp but does not remove it (one RPC trust root until a second source or block-hash pinning is added). Both the resolver and reconciliation use `min(safe.timestamp, now)` as chain time and skip their chain part when the safe block is more than 60 s ahead of the local clock (`outcome=safe_in_future` / `saiman.ledger.reconciliation.skipped{reason=safe_in_future}`).
- **Residual:** event ids are name-based and predictable, so a writer on the same topic can pre-empt a real event id with a valid-looking fact (bounded by the conflict checks and caught by reconciliation); closed by broker authentication in M6.
- **Residual:** Kafka (ADR-0020) exposes only the Kafka protocol on the compose network (Redpanda's Admin API, HTTP proxy and schema registry are gone); the image's JDK local JMX connector is loopback-only and unpublished, and auto topic creation is off (every topic is declared; `scripts/check-compose-policy.sh` enforces it). Until M6 any container on the network can still write `payments.*`, and also delete topics or records, shorten retention, move the `ledger` group's offsets or rewrite broker listener configs. Reconciliation checks only payments the ledger has seen, so such suppression hides payments from the audit; money movement is unaffected (spend control never reads Kafka). Closed by broker authentication and ACLs in M6.

## Upfront flow and credit notes (M4b)
- **What changed:** the RAG endpoints settle before the handler runs (x402 `upfront`, ADR-0021). Every non-2xx after settlement answers with `PAYMENT-RESPONSE` and creates a full credit note (seller-api `credit_note` row → `payments.credit-note-issued.v1` → ledger CREDIT_NOTE, a seller liability).
- **Controls:** the nonce claim is never released after an upfront settle (replays get 402 without a second settle); the short-window refusal stays before any facilitator call; the paid-failure event is published before the best-effort error body; the recorder inserts idempotently per payment key and publishes through the outbox; the ledger binds the producer to seller-api, bounds the record and treats a credit note that contradicts the seller's settle (failed, or another tx hash) as a CONFLICTING_FACT in either order.
- **Residuals:** (1) a payer can buy credit notes on purpose: per-payer in-flight/hourly limits run after settlement and bound model runs only, nothing per-payer runs before `/verify`/`/settle`, so a payer can buy any number of credit notes (each one costs it a real settle; credits cannot be redeemed); (2) a credit note lost to a recorder failure is only counted (`saiman.seller.credit_note_record_failures`) and reconciliation cannot see a missing liability; (3) an ambiguous upfront settle (facilitator timeout, chain settled) answers 402 with no credit note; the buyer resolves its HELD intent from the chain; (4) a credited payment found UNUSED on chain leaves the liability until a human REVERSAL; (5) a forged `CreditNoteIssued` on the unauthenticated Kafka is invisible to the chain check (no wallet account moves); reconciliation therefore corroborates each CREDITED payment with seller-api's `credit_note` row over `GET /internal/credit-notes/{paymentKey}` and reports `CREDIT_NOTE_UNCORROBORATED` (M4b audit fix); the endpoint's exact Host allowlist is not authentication (any compose peer can send the header, as with ingest `/internal/**`), so broker and service auth in M6 close the root cause. Corroboration defeats an attacker who can only write to Kafka; a holder of the shared Postgres superuser role (`saiman`, every compose peer until M6 per-service roles) can insert a matching `seller_api.credit_note` row and is out of scope, as it can already write `ledger.*`. A `CREDIT_NOTE_UNCORROBORATED` finding is not resolved automatically if the seller row appears later; (6) validation (`@Valid`, `@Pattern`, malformed JSON) runs after settlement, so a buyer's malformed request is paid and credited. (7) A process crash after an upfront settle and before the outcome is known leaves a SALE without a credit note; reconciliation shows MATCHED (no in-flight sweeper yet). (8) Forged-input edge cases (M6 class): with {seller failed, credit note, settled} the final state depends on delivery order, and a forged credit note with a wrong hash that lands first sends the real `settled` to the DLT as a CONFLICTING_FACT; the behaviour per order is pinned by a test.

## Dashboard read surface (M5, ADR-0022)
- **What changed:** the orchestrator (`/api/v1/runs`, `/runs/{id}/payments`, `/approvals`, `/spend`) and the ledger (`/api/v1/ledger/payments`, `/payments/{id}`, `/revenue`, `/api/v1/reconciliation/runs`) expose GET-only views behind the same exact Host allowlist and path-canonical guard; there is still no authentication until M6. The SPA reaches both through one same-origin proxy (Vite in development, nginx in compose), with no CORS.
- **Residuals:** (1) any local process that sends an allowed `Host` can enumerate every run (including the free-text question, `DataClass.INTERNAL`), every approval with the `(runId, approvalId)` pair the decide endpoint needs, the configured limits, payTo and buyer wallet addresses, internal seller resource URLs and seller revenue: run, approval and payment ids are not capabilities; cross-site pages stay blocked (Host allowlist, `X-Saiman-Csrf` + JSON on mutations, no CORS). (2) No response carries a nonce, signature, idempotency key or `payment_key` (marker tests in both services); a payer address is allowed in ledger bodies (public on chain). (3) Revenue is computed from the SELLER book: the per-books figures are **not chain-verified**, since reconciliation adjusts the wallet against suspense but never the revenue accounts. A forged `payments.*` sale or credit note on the unauthenticated Kafka therefore changes the per-books figure until M6 broker authentication; the API returns a chain-verified split and an open-findings count next to it and the UI labels the figure accordingly. (4) The reads are unauthenticated and unrate-limited and share each service's connection pool with the spend-control and ledger writers; statement timeouts, indexes and result caps bound the cost, but availability of runs under a local read flood is not protected until M6. (5) springdoc is on the runtime classpath but `/v3/api-docs` is disabled by configuration and compose policy forbids `SPRINGDOC_*` environment variables. (6) The nginx `web` service answers only for `localhost`/`127.0.0.1` hosts (everything else is closed with 444), forwards the browser's exact `Host`, publishes only on `127.0.0.1:8088`, and carries a strict `'self'` content-security-policy. (7) The `/otlp/v1/traces` relay accepts POST only (a cross-site preflight gets 405, a `text/plain` POST 415) with a 64 kB cap; the collector's own host port 4318 (127.0.0.1) has no Host check, so a DNS-rebinding page could still inject traces there (pre-existing, accepted). (8) A cross-site page can still send simple no-CORS GETs to the proxy; they have no side effects except opening an SSE stream, which needs a known run id. (9) The SSE location must not be shadowed: `location ^~ /api/` switches regex locations off, so the SSE block is nested inside it (`scripts/check-nginx-conf.sh` enforces it) and the orchestrator also sends `X-Accel-Buffering: no`; otherwise events, including approval requests, could sit in nginx's buffer and arrive after the approval expired (fails closed, but a bad experience). `server_tokens off` is set at http level so nginx's own error pages show no version.

## M6 hardening (ADR-0023 auth, ADR-0024 DB roles, ADR-0025 evals, ADR-0026 replay)

- **Authentication (ADR-0023).** Orchestrator and ledger `/api/v1/**` need a bearer token: READER for GETs (incl. SSE and `/me`), OPERATOR for starting runs, deciding approvals and triggering reconciliation; health is open; everything else is `denyAll`. seller-api's Spring Security chain covers `/internal/**` only (`SERVICE_<caller>` authorities: `ledger` for credit-note lookups, `evals` for the eval endpoint); the paid `/v1/**` path and the x402 filter never pass through it. The Host/canonical-path/CSRF guards run before authentication (order -110 vs -100). Tokens are static, held as SHA-256 digests (env, not secrets) with constant-time comparison over all digests; the verifier is one class behind Spring Security's resource-server seam (OIDC/JWT is the documented upgrade). Bearer token comes from the `Authorization` header only; a token in a query string is ignored (401), **but Spring Security's own DEBUG/TRACE logs print the URL with its query string, and nginx access logs would too: keep log levels at INFO and never put a token in a URL.** Raw tokens come only from `scripts/ensure-secret-files.sh` (random, 43+ chars); digests and the 8-hex principal prefix are safe only because of that, and `with-auth-digests.sh` rejects weak or `TestTokens` values. `saiman.auth.enabled=false` fails startup unless `saiman.auth.allow-disabled-insecure=true`, refuses digests alongside, and compose policy forbids it; with it off Boot's default chain locks every path (fail closed). Verified-authentication objects (`BearerTokenAuthentication.getToken()`) still hold the raw token: application code must never log them (the unverified token object is redacted).
- **Dashboard.** The token lives in memory (optionally `sessionStorage` for the tab), never `localStorage` or a URL; SSE is read with `fetch`. Any XSS in the page could read a held token; tokens have no expiry and rotate by redeploy. Replay mode is a static build that never asks for a token; a banner states the data is recorded; mutations are hidden and blocked client-side.
- **DB roles (ADR-0024).** Runtime roles `<svc>_app` have DML only (no DDL, no TRUNCATE, no `session_replication_role`, no trigger changes); ledger postings and journal entries are INSERT/SELECT only, and other insert-only ledger tables, `credit_note` (seller) and the orchestrator counters (`run`, `spend_day`: no DELETE, monotonic trigger) are protected the same way. The superuser password is a generated secret held by postgres and `db-init` only. Per-service roles stop SQL injection through the app connection; the owner-credential residual (code execution inside a service could disable its immutability triggers) is closed by the migrate one-shots, see "Migration privilege (ADR-0027)" below. The `postgres` and `template1` databases are not covered by the revoke-from-PUBLIC in the application database (revoked in `db-init` after the milestone audit).
- **Migration privilege (ADR-0027).** Flyway runs as `<svc>_owner` in a per-service one-shot (`<svc>-migrate`, `SAIMAN_RUN_MODE=migrate`); the owner password is mounted only there and lives for seconds. The running service holds `<svc>_app`, has `SPRING_FLYWAY_ENABLED=false` and no owner secret, so code execution in a service can no longer run DDL or disable its triggers. The migrator's exit code means "migrated as the expected non-superuser owner", verified against the database (`DbMigrate` checks the connected user and that it is not a superuser), not merely "Flyway returned". Compose policy (`scripts/check-compose-policy.sh`, one failing fixture or mutation per rule) pins, for every service named `<svc>-migrate` or mounting a `pg_*_owner_password`, whatever its profile: the owner secret only on its own migrator with target `spring.flyway.password`, an environment allowlist, no ports, restart "no", the app's image, no command/entrypoint, profiles `[apps]`. For every DB app (any profile): `SPRING_FLYWAY_ENABLED=false` and `service_completed_successfully` on its migrator. On every service: no `volumes_from`, no named volume bound to a host path, no privileged mode or extra capabilities, and no profile-less service outside the always-on infrastructure list. Config-injection keys (`SPRING_FLYWAY_URL`, `SPRING_APPLICATION_JSON`, `SPRING_CONFIG_*`, `SPRING_MAIN_*`, `SPRING_AUTOCONFIGURE_EXCLUDE`, `SAIMAN_SECRETS_DIR`, `JAVA_*OPTIONS`, `BPL_DEBUG_*`/`BPL_JMX_*`) are matched after collapsing `_` runs and stripping list indices. Residual: static check of one compose file; ECS is covered by the A3a Terraform tests. **Not yet equivalent on ECS:** the Terraform task definitions (A3a) need the same shape (non-essential migrate container with `dependsOn: SUCCESS` or a one-off task, owner secret only there, no injection variables) and a test that asserts it. `make ingest-backfill` still migrates as owner through `bootRun` (dev-only path).
- **seller-api.** `/internal/credit-notes` now needs the `ledger` service token (the old Host-allowlist-only residual is closed, the credit-note evidence table is append-only for `seller_api_app`, settlement updates are limited to three columns and an owner-owned upgrade-only trigger); Spring Security's `StrictHttpFirewall` applies to every request including the paid `/v1` path, so `;`, `//`, encoded slashes and dot segments there get 400 before any x402 claim or `/verify`; the `seller_api_owner` credential residual is as for the ledger. A 5 KB request body limit now also covers `/internal/*` (after authentication).
- **Compose.** Mounted secret files are 0644 inside a 0700 `secrets/` directory (container uid differs from the host uid; Compose 5.5 ignores `mode`/`uid` for file secrets); `build/evals` is written as the host uid. The service token travels in plain HTTP on the compose network and containers keep default capabilities (a container with `CAP_NET_RAW` could spoof the seller); local-only residual.
- **Reconciliation trusts the seller for credit-note corroboration.** A compromised or spoofed seller can create false `CREDIT_NOTE_UNCORROBORATED` findings, hide findings (401/5xx keeps payments PENDING; a rejected service token shows as `outcome=unauthorized` plus one ERROR log per run) or permanently clear a forged note (a cached 200). Findings are report-only; the event is published once per `(payment, kind)`. Spans carry the request path template, never the payment key (ledger client convention and a seller-api server convention; the server span carried the key until the milestone audit).
- **Eval endpoint.** `POST /internal/v1/eval/questions` is an unpaid path to an LLM function, bounded by the `evals` service token, a per-caller run guard (1 in flight, 60/h, 100/day, fail closed) and the router day cap. **It shares that day cap with paid traffic**: a looping `make eval` or a leaked evals token can use the whole cap, after which paid calls settle upfront and then get 503 plus a credit note, and the orchestrator answers 503 replay offers, until 00:00 UTC (hence the daily run limit); nginx never routes `/internal` (policy test); it settles nothing and writes no payment, credit-note, outbox or Kafka record (tests). Excerpts sent to the model carry a `published:` line (also on the paid questions endpoint).
- **Facilitator telemetry (T7).** A 2xx facilitator answer that decodes to `null` or a non-object, or exceeds the size bound, is MALFORMED, not transport. No classification or telemetry step may run outside the settler's fail-safe try: every facilitator answer, including garbage, must produce a 402, a WARN and `X402PaymentFailedEvent` with the claim held. Starting or finishing an observation must never change a payment outcome. A settle `transport_error` or `malformed` is ambiguous (money may have moved); the chain resolver decides. Metric tags are a closed enum; signature, payload, nonce and the facilitator's `errorMessage` never reach logs or tags (marker tests); `nonceRef` is 8 hex of sha256(payer+nonce). `/settle` is still never retried.
- **Capture files.** `make capture-demo` scrubs nonce, signature, key and token patterns and refuses to write on a hit, but published captures still contain free-text questions, model answers (which may quote KAP text), wallet addresses and role labels: review before publishing.
- **Not done in M6 (revisit):** Kafka and Redis authentication/ACLs, ingest `/internal` auth (cheap now with `libs/api-security`), per-run ownership between READERs (any READER can read any run's stream), per-service LLM keys/sub-caps, `cap_drop` on app containers.

## AWS demo-lite IAM (ADR-0028)

Three CI roles use GitHub OIDC. `plan` (PRs and `main`) is read-only: it has no S3 write (plans run with `-lock=false`) and explicit Denies on SSM secret values and on `artifacts/*` and `demo/*` objects, because PR code executes during `terraform plan`. `apply` and `destroy` are assumable only from the `demo-apply` and `demo-destroy` GitHub environments, both restricted to `main` with admin bypass off; `demo-apply` needs a reviewer. The three workload roles (task, task execution, scheduler) are fixed in the human-applied bootstrap stack under `/saiman/demo/`, trust service principals only (plus `aws:SourceAccount`) and carry `saiman-demo-boundary`. The apply role therefore cannot create, edit or re-trust any role; its only IAM write is `iam:PassRole` of those roles. The destroy role has no create/register/run/put action and no IAM write, and cannot delete the state object (only the lock object).

Residual risks:
- The boundary bounds what the workload roles can do, not who can use them. A malicious but approved apply can deploy a workload that runs as the execution role and reads `/saiman/demo/*` (testnet keys and a capped OpenAI key only).
- The single-task design means all containers share one task role, which weakens the ADR-0009 key separation. The task role has no SSM permissions; only the execution role does.
- EC2 write actions are scoped by region, not by tag. A dedicated AWS account for the demo is recommended.
- The destroy role can still modify or delete existing demo resources (cost denial of service, not escalation).
- The OIDC `sub` names only the environment; pinning `job_workflow_ref` (for example `demo-up.yml@refs/heads/main`) is a follow-up.
- No secret may enter Terraform state: plan-role holders can read it. Secrets reach containers only through ECS `secrets[].valueFrom`.
- **Shared loopback (single task):** all containers share one network namespace (four apps, Kafka, Redis, otel-collector, nginx and the aws-cli/postgres/busybox one-shots). Redis is password-protected on ECS; ingest `/internal/**` is still unauthenticated (known gap), so a compromised container or third-party image can call ingest retrieval/admin and produce to Kafka (credit notes are corroborated with seller-api and the chain). Mitigations: no ingress, TTL'd testnet demo, PID namespaces not shared (pidMode unset), secrets only in their own container's environment. The x402 client's plaintext-host allowlist is host-only (no port), so `127.0.0.1` allows any loopback port; the seller `/internal` allowlist is pinned to `127.0.0.1:8081`.
- **Exec and port-forward:** ECS Exec and SSM port-forwarding are human-only (no CI role has `ecs:ExecuteCommand`/`ssm:StartSession`); a port-forward reaches every loopback port and RDS, not just web:80 ("web is the target" is a convention, not a control); exec logging is NONE (CloudTrail records the call).
- **Runtime assets:** `db-init` executes `demo/assets/<session>/postgres/bootstrap-roles.sh` as the RDS master; the bundle (`assets_manifest_sha256`) and the corpus dump (`corpus_sha256`) are verified against digests passed to Terraform before anything is copied or restored; only the apply-trust `demo-up` job may write `demo/assets/*`, the task role never. The corpus metadata file is not covered by a digest. Third-party images from Docker Hub (kafka, otel collector) are pinned by digest; images from the `public.ecr.aws` mirror and GHCR are pinned by tag.
- **Owner credential on ECS:** `corpus-restore` also holds `ingest_owner` (fixed script, runs before ingest starts); it is a second holder next to `ingest-migrate`.
- **RDS TLS:** JDBC and libpq use `sslmode=require`, which encrypts but does not verify the RDS certificate; `verify-full` with the RDS CA bundle is a follow-up.
- **Lifecycle workflows:** `demo-up` is the only `terraform apply`; the `demo-apply` environment (main only, required reviewer) gates it. The expiry is written under the shared `demo-lite` concurrency lock before apply, and `demo-up` refuses to start over a live demo. The reaper destroys only when the `expires-at` tag is in the past (or the parameter was last modified more than 8.5 h ago, so a far-future tag cannot keep a demo alive) and re-checks under the lock; only the apply role or an admin can write the tag. `expires-at` is deleted last, after the teardown check passed, so a failed teardown is retried. Residual: the Scheduler backstop is derived from the same input.
- **Build-time supply chain:** third-party web build code (pnpm, Vite) runs only in the unprivileged `web` job (no `id-token`, no environment, no secrets, no cache); the privileged job verifies the dist digest before it holds any secret or credential. `check-workflow-run-blocks.sh` fails any job with `id-token: write` that sets up a toolchain, uses `cache:` or runs a package manager.
- **Image integrity:** `demo-up` requires `image_sha` to be an ancestor of `origin/main` and accepts an image digest only if it equals the digest recorded by the main push run of `ci.yml` (artifact `image-digests`, found with `gh run list --commit`); a re-pushed `sha-<12>` tag fails before any secret exists, and the recorded digests go to Terraform as `image_digests` (app and migrator containers pull `@sha256:`). The Paketo builder and run images are pinned by digest in `build-logic`. Residual: the image build job runs third-party build code (Gradle, dependencies) with `packages: write` and so can publish a malicious image that main CI then records; the `public.ecr.aws` mirror images are tag-pinned.
- **Teardown verification:** CI runs `check-demo-down.sh --allow-unverified`; the unverified categories are kinds no CI role can create; the strict check is human (`make demo-down-check`).
- **Plan job:** logs and artifacts of a public repository show the account id, host names and role ARNs (not secrets; accepted).

## Known gaps (tracked, not yet closed)

| Gap | Why it's open | Revisit |
|---|---|---|
| Redis (the nonce store) has no auth in the local compose stack | Local-only, 127.0.0.1-published port; any container on the compose network could otherwise flush the nonce store and defeat replay protection; Redis 8 also loads its search/JSON/bloom/timeseries modules by default, a larger unauthenticated command surface than Valkey (our code needs only strings, hashes and EVAL; M6 per-service ACL users restrict commands) | Before any shared or multi-tenant deployment (M3+ secrets/network-segmentation work) |
| No per-run budget / daily cap in the M1 `SpendGuard` | `CLAUDE.md` rule 3's full spend-control plane is M3 scope | M3 — don't wire the client into agent-driven loops before then |
| No per-payer/IP rate limit before `/verify`/`/settle` | More important since M4b: every upfront request settles, so over-limit and malformed requests are paid and credited | M6: a per-payer check on the recovered `from` before claim and `/verify` (or a pre-settle veto hook) |
| Handler side effects happen before settlement succeeds (default `authorization` flow only) | Verify-then-serve-then-settle keeps the resource unpaid-for on failure, but a drained/expiring-key attacker gets one free handler run | Closed for the LLM-backed endpoints by the M4b upfront flow (ADR-0021); remains by design for default-flow handlers |
| ~~Unpaid LLM runs can exhaust the per-day unsettled budget~~ | Closed in M4b: both RAG endpoints settle up front (ADR-0021) | Done (M4b) |
| ingest `/internal/**` (retrieval, admin/retry-dlq) has no authentication | Compose network + loopback only; a Host allowlist and a CSRF guard stop browser-borne attacks, not a hostile process on the network; a shared secret is straightforward | Before any deployment where another workload shares the network (M6) |
| Citation `title`/`excerpt` are untrusted issuer text | Scrubbed for links, but a buyer agent could still read instructions embedded in it | M3 agent must wrap tool results as untrusted data; web UI renders plain text |
| Upfront latency budget | verify retries + settle + recorders + handler must stay inside the buyer's 65 s read timeout; M4b checks the remaining window before the upfront settle and bounds the settled deadline by `validBefore` | Done in M4b; revisit if timeouts change |
| One `router:cost:<day>` Redis key = one $0.70/day shared by seller-api, ingest and the orchestrator; OPERATOR callers of `/api` (READER cannot spend) and, without any token, x402 payers on the seller port (`/v1/**`: any local process with faucet test USDC can buy answers across many wallets; per-payer limits run after settlement and only the 100-unsettled cap is global) can exhaust it (about five worst-case runs) and the seller then answers 503 to every payer; since M6 a cross-service surge also turns every orchestrator `POST /runs` into a 503 replay offer until 00:00 UTC, and `llmDay` shows the cross-service total | Spend is bounded; availability is not | Per-service keys or sub-caps (not done in M6) |
| `X402PaymentFailedEvent.errorReason` comes from the facilitator | Bounded to a `[a-z0-9_]{1,64}` code (else `unrecognised`) in both the log and the event since M3 | Closed |
| ingest/seller-api metrics: no cache hit/miss or 422-rate counters, breaker state not bound to Micrometer | Router cost metrics exist; the rest is observability polish | M3 |
| A resource's ticker/path reaches the third-party facilitator as part of the standard `/verify`/`/settle` call | Inherent to x402 — the facilitator has to know what it's authorizing; not PII here (public stock symbols) | Revisit if a future paid resource's identifier is sensitive |
| ~~No test drives a genuine 5xx from inside a real `@RequiresPayment` handler~~ | Closed in M4b: seller-api upfront tests drive real 503/404 answers through the controller advice | Done (M4b) |
| A handler declared with a wide return type (e.g. `Object`) that returns an async value at runtime bypasses the startup rejection of async handlers | The startup check only inspects the *declared* return type; Spring MVC picks the async handling path from the runtime value | Before M2's handlers grow return types wider than a concrete record: register `CallableProcessingInterceptor`/`DeferredResultProcessingInterceptor` to catch every async path regardless of declared type |
| One circuit breaker instance is shared by `/verify` and `/settle`, and counts a facilitator rejection (`FacilitatorClientErrorException`, including 429) as a breaker failure | A flood of unfunded-wallet signatures rate-limited by x402.org could open the breaker and short-circuit `/settle` for requests that already ran their (paid-for) handler | Higher priority since M4b (every upfront request calls both endpoints; an open breaker turns settles into ambiguous 402s). M6, alongside the per-payer limit above: separate breakers for verify and settle, and don't count 4xx (or at least not 429) as a breaker failure |
| The starter's client interceptor still follows redirects on Spring Boot's defaults; only the console-buyer *sample* pins `spring.http.clients.redirects=dont-follow` | `X402RestClients.nonRedirectingRequestFactory()` exists but isn't applied automatically | Orchestrator: done (pinned and real-socket tested, M3 T3). Other callers of the starter must pin it themselves; a non-empty plaintext allowlist already requires `dont-follow` at startup |
| `PaymentSigner` bean + pass-through can bypass the SpendGuard | unchanged starter design | M6 hardening |
| The orchestrator's LLM caps (run scopes, daily cap) depend on Redis integrity | Redis has no ACLs yet; the run row's `llm_cost_usd_micros` is the DB-side record of each run's LLM spend | M6: Redis ACLs and network scoping |
| ~~Shared superuser DB role can bypass the budget trigger~~ | Closed for SQL access through the app connection in M6 (ADR-0024: `<svc>_app` DML-only role, owner-owned monotonic trigger, no DELETE on the counters); the owner credential no longer sits in the running container (migrate one-shots, ADR-0027) | Done (M6, closed by ADR-0027 in compose) |
| Redis (the nonce store) is reachable from every app container, not only seller-api | Compose gives every service the same `SPRING_DATA_REDIS_URL`; a future service with an unrelated vulnerability (e.g. an SSRF-exposed fetcher in `ingest`) could reach it too | Same remediation as the no-auth gap above: scope network/credentials to the services that actually need it |

## How to re-verify

- Every payments/wallets/budgets/auth-touching change gets a `security-auditor` pass
  (`CLAUDE.md`, "Agent orchestration protocol"); update this file when a finding changes one of
  the invariants above or closes a gap in the table.
- `scripts/test-check-compose-policy.sh` (K1) and the starter's own test suite (K2 — every
  validation-message test plants a marker and asserts it's absent from output; K3 — testnet-tagged
  tests are excluded by default) run in CI on every change.
- The milestone-end audit for M1 covered T2's server flow end to end (replay protection, the
  verify → handler → settle order, no retry on `/settle`, 4xx/429 handling) as a single pass
  across the whole flow, since individual-task reviews only ever see one diff at a time — probed,
  not just read: concurrent replay with re-cased nonces, injected settle timeouts/5xx/429/missing
  transactions, and a circuit-breaker retry-predicate check by reflection. All four properties held
  except one gap this pass found and closed: a settlement response missing its transaction hash
  could fall through to flushing the paid response anyway (M1-A, fixed, now a regression test in
  `RequiresPaymentIntegrationTests`). M1's real payment on Base Sepolia (`docs/PROGRESS.md`) went
  through this exact fixed path.
