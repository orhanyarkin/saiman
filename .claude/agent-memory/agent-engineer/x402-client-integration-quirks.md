---
name: x402-client-integration-quirks
description: Non-obvious facts when wiring the x402 starter's paying RestClient into the orchestrator (final exceptions, request attributes, worktree bash guard)
metadata:
  type: reference
---

- `io.github.orhanyarkin.x402.client.SpendDeniedException` is `final`: a guard cannot throw a subtype (e.g. ApprovalRequired). The orchestrator reads the `payment_intent` status after the denial instead.
- The interceptor filters offers (payTo allowlist, per-request max) BEFORE `SpendGuard.reserve`, so a bad payee surfaces as `PaymentRejectedException`, never at the guard. To record a DenyReason, an inner interceptor (`OfferRecorder`) keeps the raw 402 header.
- Spring Framework 7: `RestClient...attribute(k, v)` is visible to `ClientHttpRequestInterceptor`s via `HttpRequest.getAttributes()`, also on the starter's wrapped paid retry. Good for per-call state without ThreadLocal.
- Test signer: a `PaymentSigner` bean replaces the starter's key-backed one (`@ConditionalOnMissingBean`) and the interceptor still gets created; no private-key property needed.
- Worktree bash guard refuses `mkdir`/`javap` commands whose paths contain "github" or use `$VAR` in a command position; use the Write tool (it creates directories) and literal paths.
