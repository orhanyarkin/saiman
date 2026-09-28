---
name: x402-v2-and-web3j-crypto
description: x402 v2 wire field names, EIP-712/EIP-3009 signing approach with web3j crypto 6.0.0, and Jackson 3 coordinates -- for the x402-spring-boot-starter core/evm packages
metadata:
  type: project
---

Wire format ground truth (fetched directly from github.com/x402-foundation/x402 at commit
`c84154b5d6a31d77fd5b9dbb01213053fd9cb9eb`, specs/x402-specification-v2.md,
specs/transports-v2/http.md, specs/schemes/exact/scheme_exact_evm.md):
- Headers: `PAYMENT-REQUIRED` (402 resp), `PAYMENT-SIGNATURE` (client retry req), `PAYMENT-RESPONSE`
  (success resp) -- base64 JSON, non-URL-safe standard alphabet.
- `PaymentRequired{x402Version,error?,resource,accepts[],extensions?}`,
  `PaymentRequirements{scheme,network,amount,asset,payTo,maxTimeoutSeconds,extra?}`,
  `PaymentPayload{x402Version,resource?,accepted,payload,extensions?}`,
  exact-EVM `payload{signature,authorization}`,
  `Authorization{from,to,value,validAfter,validBefore,nonce}` (all wire strings; value/validAfter/
  validBefore decimal, nonce is `0x`+64 hex chars/32 bytes).
- `SettlementResponse{success,errorReason?,payer?,transaction,network,amount?,extensions?}`,
  `VerifyResponse{isValid,invalidReason?,payer?,extensions?,extra?}` -- these are facilitator HTTP
  JSON *bodies*, not headers, so they don't go through the base64 codec.
- `extra.name`/`extra.version` on `PaymentRequirements` are the EIP-712 domain name/version for the
  token (required for eip3009 asset transfer method); `extra.assetTransferMethod` optional, default
  `"eip3009"`.

EIP-712/EIP-3009 signing (see [[boot4-modularisation-gotchas]] for the general "verify against the
resolved jar, not memory" lesson -- same principle applied here to web3j):
- `org.web3j:crypto` 6.0.0's `StructuredDataEncoder` (built from a `StructuredData.EIP712Message`
  constructed programmatically, not by parsing JSON) does all EIP-712 hashing correctly out of the
  box: `hashDomain()`, `hashMessage(primaryType, HashMap<String,Object>)`, `hashStructuredData()`
  (the final `0x1901`-prefixed digest). `EIP712Message`'s `message` field must actually be a
  `HashMap<String,Object>` instance (not just any `Map`), since internals do an unchecked cast.
  `uint256` struct fields accept decimal-string values directly (`"10000"`); `address` fields take
  the plain `0x...` string; `bytesN` fields (e.g. `bytes32` nonce) take a `0x` hex string of the
  right byte length.
- Sign with `Sign.signMessage(digest, ecKeyPair, /*needToHash=*/false)` (digest is already the
  final EIP-712 hash, no further prefixing). Recover with
  `Sign.recoverFromSignature(recId, new ECDSASignature(r,s), digest)` where
  `recId = v[last] - 27`; wire signature is `0x` + 65 bytes `r‖s‖v`.
- Verified against the EIP-712 spec's own `Mail` example: the "Cow" test private key from that
  example is `keccak256("cow")` = `0xc85ef7d79691fe79573b1a7064c19c1a9819ebdbd1faaab1a8ec92344438aaf4`
  (64 hex chars -- easy to lose the last character when transcribing by hand; always regenerate/
  verify programmatically rather than hand-copying). Reproducing domain separator, struct hash,
  digest and the final signature against the spec's published values is a strong end-to-end proof
  the crypto plumbing (ours + web3j's) is correct.

Jackson 3 / dependency facts (Spring Boot 4.1.1, resolved 2026-09-28):
- Jackson 3 databind/core moved to groupId `tools.jackson.core` (`tools.jackson.core:jackson-databind`,
  `:jackson-core`), class packages under `tools.jackson.*` (e.g. `tools.jackson.databind.json.JsonMapper`).
  `jackson-annotations` is the one module that DIDN'T move: it's still
  `com.fasterxml.jackson.core:jackson-annotations` with 2.x-style versioning (2.21 under Boot 4.1.1)
  even in a pure-Jackson-3 stack. Don't be alarmed seeing that one `com.fasterxml` coordinate next to
  a wall of `tools.jackson` ones -- it's expected, not a Jackson-2/3 mix.
- `JsonMapper.builder()...build()` API is close to Jackson 2's `ObjectMapper.builder()`;
  `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES` still exists with the same name.
  `readValue`/`writeValueAsBytes` throw unchecked `tools.jackson.core.JacksonException` (extends
  `RuntimeException` directly) instead of Jackson 2's checked `IOException`/`JsonProcessingException`
  -- no declared-exception handling needed, but don't forget to still catch it.
- `org.web3j:crypto:6.0.0` on the **compileClasspath** is lean (crypto/abi/rlp/utils + BouncyCastle
  `bcprov-jdk18on:1.80` + `tools.jackson.core:jackson-databind` transitively, resolved by Boot's BOM
  to 3.1.5 -- matches our own Jackson 3 usage, no conflict). On the **runtimeClasspath** it is much
  heavier than "crypto module only" suggests: it also pulls `io.consensys.tuweni:tuweni-bytes`, which
  drags in `io.vertx:vertx-core` (full Netty HTTP stack), `org.connid:framework`, Kotlin stdlib, and
  `com.fasterxml.jackson.core:jackson-core` (Jackson 2, a *different* artifact from our Jackson 3
  `tools.jackson.core:jackson-core` -- no classpath conflict, just extra weight), plus
  `io.consensys.protocols:jc-kzg-4844` (EIP-4844 KZG, unrelated to EIP-712/3009). None of this is
  used by `Hash`/`Sign`/`Keys`/`ECKeyPair`/`StructuredDataEncoder` (pure in-memory computation, no
  I/O), but it's real weight on the published starter's runtime/container image. Flagged for
  reviewer/security-auditor rather than fixed unilaterally (excluding transitives without verifying
  web3j-crypto doesn't touch them at runtime is risky to do blind).
- `org.web3j.crypto.Sign.SignatureData` appears in this starter's own public method signatures
  (`Eip3009TypedData.toSignatureHex`/`parseSignatureHex`), so `web3j-crypto` must be declared `api`,
  not `implementation`, in `build.gradle.kts` -- otherwise consumers of the published starter jar get
  compile errors referencing `Eip3009TypedData` without adding web3j themselves. Checked by inspecting
  the generated `pom-default.xml` dependency scope (`compile` vs `runtime`).

Security-review pattern for key/address validation (applies to any future
`@ConfigurationProperties`/plain constructor taking a private key or address in this starter):
never let a parsed key/address value escape into an exception message -- Spring's own
config-property bind-failure report, and any generic exception logger downstream, will print it
verbatim. Validate with a regex/format check first (message states the *rule*, not the *input*),
and if you still need to catch a library exception (e.g. `NumberFormatException` from
`new BigInteger(hex, 16)`), throw a fresh exception with a fixed message and no cause -- don't chain
the original, since its own message may contain the input.
