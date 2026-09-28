---
name: threat-model-baseline
description: Running Saiman threat model - mitigations so far and deferred checks for x402/ledger/spend code (M0 T3 2026-09-27, M1 T5 2026-09-28)
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
