---
name: x402-starter-review-checklist
description: Verified pitfalls when reviewing libs/x402-spring-boot-starter (codec strictness vs reference TS impl, Jackson 3 cause leaks, web3j EIP-712 leniency, spec KAT)
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
