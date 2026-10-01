---
name: m4b-fix-reaudit
description: M4b audit-fix re-review (2026-10-01): credit-note corroboration sound for Kafka-only attackers; residuals
metadata:
  type: project
---
- Corroboration (seller `GET /internal/credit-notes/{key}` + ledger `RestSellerCreditNoteClient`) closes the forged-credit-note Medium for an attacker who can only write Kafka: exact Host allowlist (no forwarded headers), lowercase keys both sides, body key must match, cache hit only on equal tx+amount, every non-definite answer fails closed to "unavailable" (PENDING).
- Remaining: the shared Postgres superuser role `saiman` can insert a matching `seller_api.credit_note` row (out of scope; it can write `ledger.*` too) until M6 per-service roles. UNCORROBORATED findings are never auto-resolved; negative answers not cached (one lookup per 6 h per forged note).
- Upfront pre-settle claim release is safe: before markVerified/markSettleAttempted, Redis release is compare-and-delete on the claim token, `/settle` never retried (≤ 15 s < 20 s margin); a resubmitted released payload fails the pre-claim window check.
