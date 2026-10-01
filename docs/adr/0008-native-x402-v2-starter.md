# ADR-0008: Implement x402 v2 natively in the starter (exact scheme, EVM, Base Sepolia only)

Status: Accepted (2026-09-28). Amends ADR-0005.

## Context
ADR-0005 planned `x402-spring-boot-starter` "built on the official x402 Java SDK", or a contribution to an existing Spring integration if one existed. The M1 design pass (2026-09-28) found:
- The official Java SDK (`x402-foundation/x402/java`) is `org.x402:x402:1.0.0-SNAPSHOT`, not on Maven Central (consumers must build it locally), speaks x402 **v1 only** (`X-PAYMENT`, network name `base-sepolia`), depends on Jackson 2.17 and leaves the EIP-712/EIP-3009 signer to the user.
- The current spec is **v2**: `PAYMENT-REQUIRED`, `PAYMENT-SIGNATURE` and `PAYMENT-RESPONSE` headers (base64 JSON), CAIP-2 network ids, and "verify → resource → settle → respond" by default.
- The public facilitator at `https://x402.org/facilitator` is free, needs no key, and supports v2 `exact` on `eip155:84532`.
- One third-party Spring starter exists (Mogami, Apache-2.0): v1 + v2, but on Spring Boot 3.5 and tied to its vendor's client and commons libraries.

Depending on a local-only snapshot breaks two M1 acceptance criteria (copy-paste quickstart; a sample app consuming the starter from a Maven repo) and pins us to v1.

## Decision
- The starter implements **x402 v2** itself, limited to the **`exact` scheme on EVM** (EIP-3009 `transferWithAuthorization`). Other schemes and non-EVM networks are out of scope.
- Cryptography (secp256k1, Keccak, EIP-712 hashing, signature recovery) comes from **web3j `org.web3j:crypto` 6.0.0** (Jackson 3, matching Boot 4). No other web3j modules.
- EIP-712 and EIP-3009 signing is verified against the spec test vectors (the EIP-712 `Mail` example and v2 spec payload examples, vendored with a NOTICE and the source commit SHA).
- The official SDK is a **reference only** (behaviour, edge cases, e.g. buffering the response until settlement). Mogami is not adopted: a Boot 3.5 dependency and a vendor stack don't fit a Boot 4 starter.
- **Testnet lock in code**: `TestnetAssets` fixes network `eip155:84532` and test USDC `0x036CbD53842c5426634e7929541eC2318f3dCF7e` (EIP-712 domain name `USDC`, version `2`, 6 decimals). There is no network or asset property. The server only offers these; the client rejects anything else; the signer only builds that domain.
- **Fail closed, no `enabled` flag**: a `@RequiresPayment` handler without a valid `x402.server.pay-to` stops startup; a client interceptor without a key, a per-request maximum or a payee allowlist stops startup.
- **Replay**: the server claims `(network, asset, from, nonce)` in a `PaymentNonceStore` (Valkey `SET NX` with TTL) before calling the facilitator; the on-chain EIP-3009 nonce is the final guard. `/settle` is never retried.
- Artifact `io.github.orhanyarkin:x402-spring-boot-starter`, package `io.github.orhanyarkin.x402`, published with a plain POM (no Boot BOM import) via the `saiman.published-library` convention. The starter does not depend on `libs/shared`.

## Alternatives
- Official SDK as a dependency: v1 only, snapshot only, Jackson 2; rejected for the reasons above.
- Contribute to Mogami: would mean building on Boot 3.5 and a vendor's libraries; the migration to Boot 4 is theirs to make.
- Contribute v2 to the official SDK first: the right long-term move, but it blocks M1 on an external review cycle. Revisit once our implementation is stable.

## Consequences
+ The starter speaks the current spec and resolves from any Maven repository.
+ The whole payment path (wire format, signing, replay) is ours to explain and test.
− We own spec conformance; mitigated by vendored spec fixtures pinned to a commit and a local live `/verify` check against x402.org (`make x402-testnet-check`, read-only, moves no funds).
− Security-sensitive crypto glue is our code; security-auditor reviews every change to `core/`, `evm/`, `server/` and `client/`.
Revisit if: the official SDK ships a v2 release on Maven Central, or we need a scheme other than `exact`.

## Amendment (2026-10-01): Redis and Apache Kafka (ADR-0020)
The seller-api nonce store runs on Redis instead of Valkey. The starter itself is unchanged: `RedisPaymentNonceStore` speaks the Redis protocol and works with either server.
