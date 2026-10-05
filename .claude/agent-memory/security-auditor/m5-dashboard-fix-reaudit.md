---
name: m5-dashboard-fix-reaudit
description: M5 dashboard audit-fix re-review (2026-10-05, 2171a01 + 1dfb547) - echo closed; chainVerified forgeable via TX_UNKNOWN (USED, no tx, no finding); ledger reads lack timeout
metadata:
  type: project
---
Re-review of the M5 read-surface fixes. Echo (Medium 1) closed in both services; cursors bounded; BoundedReads sound.

- OPEN Medium: `LedgerQueries.revenue` sale_verified = chain_state USED + no blocking mismatch. ChainReconciler TX_UNKNOWN path (authorizationState USED, no canonical receipt found) sets chain_state USED with NO finding and chain_tx_hash NULL, never re-searched. PaymentSettled allows txHash null with evidence CHAIN for ANY book (parser has no per-book rule). Forged seller settled (no tx) + payer/nonce of any real used authorization + validBefore far from the real use (searchRange 3600 blocks anchored on forged validBefore) -> forged amount lands in chainVerified. Fix: require pm.chain_tx_hash IS NOT NULL; parser rejects SELLER + null txHash. LedgerReadApiTests ~831 sets chain_state USED without chain_tx_hash (encodes the gap).
- Low: ledger has no statement timeout on reads (revenue aggregates all SELLER postings per poll); orchestrator advice is per-exception (415 Content-Type / ConversionNotSupported texts unsanitised, unreachable today); Boot /error `path` on uncaught 500s; no bulkhead on shared Hikari pool.
- Info: controller-local ProblemDetail handlers (recon 429/503, RunAdmission) get instance = raw URI (canonical by guard); V8 CREATE INDEX non-concurrent ok at this size; "openFindings" counts all-time rows.
**How to apply:** at M5 close / M6 check sale_verified requires a chain tx hash and a TX_UNKNOWN test exists; at M6 Kafka authn removes the root cause.
