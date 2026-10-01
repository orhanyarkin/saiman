---
name: m4b-audit-lows-findings
description: PaymentBook is not redelivery-idempotent for contradictory seller reports; time-window test bands need ≥1 s cushion for epoch-second validBefore; settleMargin() is the single source.
metadata:
  type: project
---

Facts learned fixing the M4b audit Lows (2026-10-01, commit c4fbf8e on m4b-settle-first).

- `PaymentBook.apply` alone is NOT idempotent for contradictory seller reports: a seller Failed accepted before a
  CreditNoted conflicts when the same event is redelivered after it, and a quarantined CreditNoted (F,C,S order)
  is accepted if redelivered after Settled (ends CREDITED). Production relies on the inbox (accepted ids) and on
  ConflictingFactException being not-retryable (DLT). P4 in PaymentBookPropertyTests pins this.
  **How to apply:** never claim "duplicates are no-ops" for ledger facts without the inbox in the model.
- Window tests: `validBefore` is a whole epoch second, so the window left is up to 1 s shorter than nominal.
  Pick verify delays / timeouts so every band (402 / 503+credit / 200) has ≥1 s cushion; deterministic
  alternative in the starter is a stepping `Clock` bean + a `FacilitatorClient` decorator that advances it on
  verify (UpfrontPreSettleWindowIntegrationTests).
- `X402ServerProperties.Facilitator#settleMargin()` (connect + read + 5 s) is the one source for the margin;
  seller-api `RequestDeadlines` uses it. Link: [[x402-v2-and-web3j-crypto]].
