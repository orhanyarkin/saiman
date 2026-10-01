---
name: x402-facts
description: Verified x402 facts (2026-09-28): official Java SDK status, v2 headers, facilitator, Base Sepolia USDC + EIP-712 domain, Mogami
metadata:
  type: reference
---

Verified during the M1 design pass (2026-09-28):
- Official Java SDK (x402-foundation/x402/java): `org.x402:x402:1.0.0-SNAPSHOT`, not on Maven Central (build locally), x402 **v1 only** (`X-PAYMENT`, network `base-sepolia`), Jackson 2.17, no EIP-712/3009 signer (`CryptoSigner` left to the user). Reference only (ADR-0008).
- Spec v2: headers `PAYMENT-REQUIRED`, `PAYMENT-SIGNATURE`, `PAYMENT-RESPONSE` (base64 JSON), CAIP-2 networks, default order verify -> resource -> settle -> respond. Spec: specs/x402-specification-v2.md, specs/transports-v2/http.md.
- Facilitator: https://x402.org/facilitator (`/supported`, `/verify`, `/settle`), free, no key, lists v2 `exact` on `eip155:84532`. CDP facilitator unverified (pricing/keys).
- Base Sepolia USDC: `0x036CbD53842c5426634e7929541eC2318f3dCF7e`, 6 decimals, EIP-712 domain name "USDC", version "2", chainId 84532 (Circle docs + x402 Go SDK DefaultAssets).
- Mogami x402-spring-boot-starter: Apache-2.0, v1+v2, Spring Boot 3.5, tied to Mogami libs; not adopted.
- web3j `org.web3j:crypto` 6.0.0 is on Jackson 3. Resilience4j 2.4.0 core modules.
- Circle faucet: 20 USDC / 2 h, no account; buyer needs no ETH (EIP-3009 is gasless for the payer).

Verified during the M4b design pass (2026-10-01):
- Spec v2 §6.1 defines three payment flows: `authorization` (default, verify→resource→settle), `upfront` (settle→resource) and `escrow` (settle→resource→settle). A non-default flow MUST appear as `accepts[].extra.paymentFlow`; clients MUST NOT pay for an unknown flow and SHOULD prefer `authorization`. The spec says nothing about refunds. The `exact` EVM scheme doc does not mention flows.
- x402.org `/verify` ignores `extra.paymentFlow`: absent, `"upfront"` and `"bogus"` all reached the on-chain balance simulation (probe with an unfunded throwaway key, 2026-10-01).
- Prior art: geekinasuit/obolus PR #82 normalises `extra.paymentFlow="upfront"`.
- M4b decisions (human): upfront on both seller RAG endpoints; full credit note for any non-2xx after settlement; new ADR-0021 with pointers in 0008/0015/0017; CREDITED seller state, CREDIT_NOTE = Dr revenue:credit-notes / Cr liability:customer-credits; new topic payments.credit-note-issued.v1.
