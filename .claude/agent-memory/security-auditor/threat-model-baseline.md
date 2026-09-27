---
name: threat-model-baseline
description: Running Saiman threat model - what is mitigated so far and the deferred checks to run when x402/ledger/spend code lands (from M0 T3 audit, 2026-09-27)
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

**Why:** skeleton decisions become defaults later payment code inherits.
**How to apply:** re-run these checks on every payments/ledger/spend-control audit; update this file as items close.
