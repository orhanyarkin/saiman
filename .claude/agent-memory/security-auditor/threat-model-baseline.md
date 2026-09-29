---
name: threat-model-baseline
description: Running Saiman threat model - mitigations and open items for x402/ledger/spend code (M0 T3, M1 T5, T1, T3 client, T2 server: async settle-without-service, interceptor fail-open, header leaks; 2026-09-28)
metadata:
  type: project
---

Baseline after M0 T3 (skeleton; audited 2026-09-27, diff 11b8306..941da3b). No payment logic yet.

Mitigated at skeleton level:
- Actuator web exposure pinned to health,info in seller-api and ledger application.yaml; health details/components rely on Boot default (never), not pinned.
- Compose publishes 8081/8082 on 127.0.0.1 only.
- .env.example: X402_NETWORK=eip155:84532 (Base Sepolia) - testnet only.

Deferred checks (verify when the code lands):
- X402 auto-config must be fail-closed: @RequiresPayment with missing facilitator/payTo/network => startup failure, not silent pass-through. No `x402.enabled` defaulting to false.
- Network allowlist hardcoded to testnet (eip155:84532) and validated at bind time (@Validated @ConfigurationProperties).
- 402 `resource` URL built from configured base URL, never Host/X-Forwarded-* headers.
- X-PAYMENT / PAYMENT-SIGNATURE headers never recorded in spans, logs or Problem Details (sampling is 1.0 everywhere).
- Actuator on same port as paid endpoints: x402 filter must not cover /actuator/**, and actuator must not be reachable behind the ALB publicly beyond health.
- Ledger has no authn yet; IDOR on ledger/run ids once APIs exist.

M1 T5 audit (2026-09-28, infra: Makefile/compose/CI/check scripts, uncommitted on m1-x402):
- Verified empirically (docker compose v5.5.1): compose does NOT auto-load repo-root .env when run with -f deploy/compose/...; merge keys/anchors and non-active profiles ARE resolved/included by `--profile apps config --no-interpolate`, so jq over .services sees them.
- Found: compose-policy check 4 only inspects services.*.environment; env_file, service secrets: (top-level secret with environment:/file: source), configs:, bind mounts of secrets/ all bypass it. Orchestrator exempt even via env (ADR-0009 wants secrets:+configtree only).
- Found: check-x402-env.sh echoes the rejected value (private key pasted by mistake from `cast wallet new` would hit the terminal).
- Deferred to libs/seller-api: Boot's bind-validation failure analyzer prints `Value:` of rejected properties -> never put Bean Validation on x402.client.private-key; pay-to errors must not echo value. X402_FACILITATOR_URL does not relax-bind to x402.server.facilitator.url (needs explicit ${} in application.yaml). Facilitator host allowlist + no redirects + /supported handshake recommended. Sample build: mavenLocal content filter, testnet tag exclusion, config-cache/daemon env capture, new-wallet no-overwrite + atomic 0600 create, testnet-check signed auth is settleable by facilitator (use self payTo).

**Why:** skeleton decisions become defaults later payment code inherits.
**How to apply:** re-run these checks on every payments/ledger/spend-control audit; update this file as items close.

M1 T1 audit (2026-09-28, starter core/ + evm/, uncommitted on m1-x402). Verified empirically with a javac/java probe in the scratchpad against web3j 6.0.0:
- web3j StructuredDataEncoder is lax: uint accepts "0x2710"/"010000"/"+10000"; address/bytes32 accept any case, missing 0x, odd length, and NON-HEX chars (Numeric.hexStringToByteArray maps digit -1, so "zz" == 0xEF, "1z" == 0x0F); short addresses left-padded; null fields are SKIPPED from the encoding (validAfter=null/validBefore=B collides with validAfter=B/validBefore=null). => raw-string nonce-store keys are bypassable; canonicalise before keying.
- Sign.recoverFromSignature: no low-s, no r/s<n, s=0 recovers an arbitrary address; r=0 -> IAE "Invalid point compression". web3j signing IS low-s (toCanonicalised) + RFC6979.
- Private key: ctor accepted d>=n and >32 bytes (address of d mod n); failure only at sign time (BC "Scalar is not in the interval").
- Jackson 3.1.x defaults: FAIL_ON_NULL_FOR_PRIMITIVES on (missing int fails), nesting 500, number len 1000, trailing tokens fail; duplicate keys last-wins; scalar coercion on (1.5->1, "2"->2, 1e4->"1e4"); cause messages echo input values.
- KAT: spec example payload signature (x402 commit c84154b) recovers to 0x857b06519E91e3A54538791bDbb0E22373e36b66 under domain USDC/2/84532/0x036C...; domain separator 0x71f17a3b2ff373b803d70a5a07c046c1a2bc8e89c09ef722fcb047abe94c9818.
- Deps: plain POM lists only direct deps -> non-Boot-BOM consumers get web3j's jackson-databind 3.1.0 (9 GHSAs, fixed 3.1.4/3.1.5) and bcprov 1.80 (4 CVEs, fixed 1.85; 1.86 clean). vertx/connid arrive via jc-kzg-4844 (only BlobUtils); tuweni(+kotlin-stdlib) only Blob/RawTransaction/TransactionDecoder paths - probe runs without them.
Deferred to T2/T3: key nonce on canonical lowercase (from,nonce) + recovered signer; forward re-serialized payload (not raw header) to facilitator; x402Version==2 and scheme checks; front-running of settle (seller paid, buyer 402'd) -> reconcile via authorizationState in M4; /actuator/heapdump,/env never exposed (key String is un-zeroizable).

M1 T1 re-check (2026-09-28, aaa0c35+8ed1e28): canonical auth/canonicalNonceKey, sig policy (v/r/s/130-hex), pk range, dup keys, coercion, length pre-check, KAT, POM (databind 3.1.5, bcprov 1.86, 4 exclusions, no fixtures/compileOnly/BOM) all verified by probe. Still open:
- X402Codec.pathSuffix echoes attacker-controlled property names (UnrecognizedPropertyException path) verbatim: CR/LF/ESC and ~16 KB reach the "safe to log / Problem Details" message. Fix = drop/sanitise unknown segment ([A-Za-z0-9_] + length cap).
- toSignatureHex takes last byte of multi-byte v and skips low-s; not reachable from web3j signMessage (low).
- Base64 basic decoder accepts unpadded input -> never key anything on the raw header text (T2).

M1 T3 audit (2026-09-28, client/ + X402ClientAutoConfiguration + samples/console-buyer, uncommitted on m1-x402). Probes in scratchpad against build/classes + sample bootJar libs:
- CONFIRMED: second 402 on the paid retry -> SpendGuard.release -> same Idempotency-Key signs again; hostile /keep402 seller collected 3 live signatures for one key. Also hits the honest M1 server: settle failure/timeout -> 402 by design, tx may still mine. Fix: never release once a signature left the process; hold until validBefore (M3: + authorizationState check); count outstanding amount against budget.
- CONFIRMED: PropertiesSpendGuard reserve/commit TOCTOU (committed.contains, then reserved.add; commit removes from reserved) -> 412/200k same-key double reserve+commit. Fix: one ConcurrentHashMap<key,State>, putIfAbsent/replace/remove(key,RESERVED).
- CONFIRMED: Boot 4.1 HttpClientSettings.redirects=null -> JDK Redirect.NORMAL; paid retry 302 cross-host forwards PAYMENT-SIGNATURE + Idempotency-Key to the new host. Fix: DONT_FOLLOW for paying clients (sample default props / documented + helper), test.
- Low: x402.asset low-card tag uses seller-cased asset (equalsIgnoreCase) -> metric cardinality; x402.tx_hash unvalidated <=16KB; allowlist entries not validated as 0x+40hex; pre-sign exceptions leave key reserved; last-payment.txt default umask; secrets/ dir default perms; no cumulative cap in M1 guard (rule 3 gap until M3); interceptor double-registration pays twice (skip if request already has PAYMENT-SIGNATURE).
- Closed from T5 carry-forward: key read at run time only (notCompatibleWithConfigurationCache), new-wallet O_EXCL+0600, testnet-check self-transfer value 1 / 60 s / verify-only, sample excludes testnet tag, mavenLocal includeGroup; PrivateKeyPaymentSigner messages never echo input (probed); X402ClientProperties toString redacts, no Bean Validation.

M1 T3 re-check (2026-09-28, same uncommitted tree; probes in scratchpad t3/: HostileSeller JDK HttpServer + ClientProbe vs starter jar, sample bootJar run end-to-end):
- RESOLVED: #1 no release after send (hostile /keep402: 1 signature per key; 2nd/3rd attempt SpendDenied), #2 single-CHM putIfAbsent/replace/remove (0 doubles/20k 2-thread rounds + unit test), #4 asset tag = constant, network/scheme exact-equal constants, tx_hash regex-sanitised, #5 signing failure releases (test), #7 pass-through when PAYMENT-SIGNATURE present (double-registered interceptor -> 1 signature), #9 last-payment.txt 0600 via createTempFile attr + ATOMIC_MOVE, #10 secrets/ 0700 only when created (accepted).
- PARTIAL: #3 library still follows redirects on Boot defaults (probe: other host got PAYMENT-SIGNATURE); sample sets spring.http.clients.redirects=dont-follow (verified end-to-end, 302 -> Ambiguous) + real-socket test. Orchestrator (M2/M3) must pin DONT_FOLLOW on its paying client with a test. #6 allowlist not format-validated at bind, but harmless: Eip3009Authorization rejects non-0x40hex `to` before signing (probed). #11 sample prints `basescan.org/tx/` + raw settlement.transaction() -> ESC reaches terminal (the SafePrint line above it is fine).
- NEW: settlement success with missing "transaction" -> commit, then sanitizeTxHash(null) NPE: caller loses paid response, response unclosed, outcome tag "settled"+error. Same NPE in sample BuyCommand.
- Deviation (retry 402 returned, not thrown, reservation held): acceptable for M1; outcome tag "rejected" conflates "nothing signed" with "signed+sent, refused" -> needs distinct tag; before orchestrator wiring prefer a typed exception (money-may-have-moved) since exchange()/onStatus callers see a plain 402 and re-pay under a new key (probe: 2nd sig). M3 guard must count held reservations against budget.
- Spring 7.0.9 HttpClientErrorException message replaces control chars with '?' and CR/LF with <EOL> (observed), body still included.

M1 T2 audit (2026-09-28, server/ facilitator/ observation/ + server/observation autoconfig, uncommitted on m1-x402). Probes: standalone SpringApplication mains compiled against the starter's test runtime classpath (init script in scratchpad prints sourceSets.test.runtimeClasspath; javac needs -parameters for @RequestParam), FakeFacilitator from testFixtures, raw java HttpClient/Socket.
- CONFIRMED High: async return types (Callable, StreamingResponseBody, CompletableFuture; DeferredResult/SseEmitter same path) on @RequiresPayment -> filter settles at end of initial dispatch (status still 200), interceptor re-runs preHandle on ASYNC dispatch -> "replayed" -> buyer gets 402 + PAYMENT-REQUIRED + PAYMENT-RESPONSE, empty body. Paid, no service.
- CONFIRMED High: app extending WebMvcConfigurationSupport ignores WebMvcConfigurer -> interceptor never registered -> filter flushes paid handler body with no payment (fail-open; filter comment at afterDispatch admits the case).
- CONFIRMED Medium: settle failure uses resetBuffer() only -> handler-set headers (Set-Cookie, signed-URL headers, Location) reach the 402.
- CONFIRMED Medium: Tomcat response header buffer ~8KB (7000-char header ok, 9000 -> HeadersTooLargeException 500, empty body); PAYMENT-RESPONSE re-encodes full facilitator SettlementResponse (up to 64KB) -> settled-but-500.
- CONFIRMED Low: Host header reflected into PAYMENT-REQUIRED resource.url (getRequestURL) and events.
- Open design items: handler runs before settle (drain-between-verify-and-settle compute theft; unfunded-key signatures each cost a facilitator /verify -> CB/rate-limit DoS); events carry facilitator payer/tx/network unvalidated and no (from,nonce) -> M4 authorizationState reconciliation impossible; client resource/extensions forwarded to facilitator; unconditional nonce release; redaction filter strips spring.security.authorization.* keys globally; late-registered handlers unpaid; 3xx pass-through.
- Verified OK: claim atomicity + canonical key after local sig recovery, TTL aligned with expiry check, exact offer equality, flush()/flushBuffer() don't commit CCRW (Spring 7.0.9), OPTIONS unpaid (Spring options handler), facilitator allowlist/no-redirect/own request factory/TimeoutHandler/64KB cap/handshake, client interceptor not auto-attached, fixed-string Problem Details.
