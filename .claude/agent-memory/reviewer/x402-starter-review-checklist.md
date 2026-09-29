---
name: x402-starter-review-checklist
description: Verified pitfalls when reviewing libs/x402-spring-boot-starter (codec strictness, Jackson 3 leaks, web3j leniency, spec KAT; client release semantics, SpendGuard races, tag keys; server auto-config ordering, handshake network calls, filter fail-open/async)
metadata:
  type: project
---

Facts verified 2026-09-28 (M1 T1 review) with a scratch probe against the built classes and x402 commit c84154b5:

- The x402 v2 spec example `payment-payload.json` signature is REAL: it recovers to `from` 0x857b06519E91e3A54538791bDbb0E22373e36b66 over domain USDC/2/84532/0x036C…dCF7e. It is the known-answer vector for TransferWithAuthorization; demand a test that uses it (ADR-0008 requires spec-vector verification). Self-consistent sign->recover tests prove nothing about the domain/type string.
- TransferWithAuthorization typehash = 0x7c7c6cdb67a18743f49ec6fa9b35f50d52ed05cbed4cc592e13b44501c1a2267.
- Reference TS types (typescript/packages/core/src/types/facilitator.ts) have `invalidMessage`, `errorMessage`, `extensionResponses`, `extra` on Verify/SettleResponse; `extra` is `Record<string, unknown>`. Zod strips unknown keys and accepts explicit nulls. So FAIL_ON_UNKNOWN_PROPERTIES on facilitator/402 responses breaks interop.
- Jackson 3: FAIL_ON_UNKNOWN_PROPERTIES defaults to false; missing record components decode to null even under @NullMarked; scalar coercion on (60.7 -> int 60, "true" -> boolean, number -> String); duplicate keys last-wins. Exception messages redact source but still echo token/values ("Unrecognized token 'X'", `from String "X"`) -> never chain Jackson causes on payment payloads (ADR-0006 amendment).
- web3j 6 StructuredDataEncoder: uint256 accepts "0x2710"/"010000" (same digest as "10000"), short addresses are left-padded, invalid values throw raw RuntimeException (not IAE); Numeric.hexStringToByteArray silently accepts non-hex; Sign.recoverFromSignature accepts high-s (FiatToken ECRecover rejects s > n/2).

**Why:** these are easy to miss by reading code alone; each maps to a money or interop failure mode.
**How to apply:** on any change to core/, evm/, facilitator/ or the FakeFacilitator, check these first. Probe recipe: `java -cp build/classes/java/main:<web3j,jackson3,bcprov,jackson-annotations jars from ~/.gradle/caches> Probe.java` in the scratchpad.

Client side (M1 T3 review, 2026-09-28):

- A 402 on the payment-carrying retry is NOT proof of "no charge": the server answers 402 when /settle fails or times out, and the signed EIP-3009 authorization stays settleable by the seller until validBefore. Releasing the SpendGuard reservation there frees the idempotency key/budget while a live authorization exists. Only a failure *before* the signature left the process is safe to release.
- In-memory guards built from two concurrent sets (reserved/committed) with check-then-act are racy (reserve checks committed, commit moves key, reserve adds to reserved -> second payment under the same key). Demand one map with atomic putIfAbsent/replace/remove(key, expected).
- Observation low-cardinality keys must be set on every path (early rejects happen before network/scheme/asset are known); otherwise the same meter name gets different tag-key sets (Prometheus drops them).
- Spring Framework 7.0 InterceptingClientHttpRequest composes interceptors with andThen, so calling execution.execute twice re-runs downstream interceptors (verified in spring-web 7.0.9 sources). Boot 4.1 RestClientAutoConfiguration does NOT auto-attach ClientHttpRequestInterceptor beans (verified by javap).
- EIP-3009 validAfter back-dating: reference TS client uses now-600; a 5 s skew breaks on WSL2 clock drift.
- `./gradlew -p <standalone sample>` does not read the root gradle.properties (config cache, auto-download=false, jvmargs are not applied).

Server side (M1 T2 review, 2026-09-28), verified by probe/test XML:

- `@ConditionalOnBean(StringRedisTemplate)` inside X402ServerAutoConfiguration without `afterName = DataRedisAutoConfiguration` silently picks the in-memory nonce store ("io..." sorts before "org..."; probe with WebApplicationContextRunner + DataRedisAutoConfiguration showed InMemoryPaymentNonceStore). Demand an auto-config test with Boot's Redis auto-config, not a hand-built template.
- HttpFacilitatorClient's /supported handshake (afterPropertiesSet) runs whenever MVC is on the classpath, even with zero @RequiresPayment handlers; the imports test hit real x402.org (grep build/test-results XML for "facilitator host: x402.org"). Client-only MVC apps (orchestrator) would need the facilitator to start.
- Filter/interceptor split: check the "filter created the attempt but the interceptor never ran" path (fail-open if 2xx is flushed), async handler return types (settle before body), and exceptions from synchronous ApplicationEvent listeners after /settle (charged but 500).
- Valkey in tests: `@ServiceConnection(name = "redis")` on a GenericContainer works (Boot 4.1.1 RedisContainerConnectionDetailsFactory accepts connection name "redis"; image-name match is only redis / redis-stack).
