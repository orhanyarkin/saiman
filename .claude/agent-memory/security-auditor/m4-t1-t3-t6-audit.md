---
name: m4-t1-t3-t6-audit
description: M4 per-task audit (evm-rpc, eventing inbox, ledger consumers/schema/guard, seller-api settlement recorder) 2026-10-01 - no Critical; poison validBefore kills reconciliation, DLT on transient DB errors, amount/validBefore bounds, Kafka trust
metadata:
  type: project
---

M4 T1+T3+T6 audit (branch m4-ledger, 2026-10-01, read-only; one probe in scratchpad m4t1/).

Verified OK (do not re-check): evm-rpc URL checks (userinfo, trailing dot, case, IP, https, exact allowlist, redirects DONT_FOLLOW), selectors computed via web3j Hash + KAT, hex/quantity range checks, 1 MB bounded read, slow-loris body is cut by readTimeout (probed: 2.7 s with 2 s timeout), chain-id deferred check fail-closed per call, rate limiter innermost (retries consume permits). InboxGuard MANDATORY + PK(event_id, consumer). Ledger guard filter (raw path, Host, CSRF, 16 KB/chunked) closes the M3 `;`/`%61` bypass. DB triggers: deferred balance, immutable, BEFORE TRUNCATE (CASCADE tested). seller-api recorder: catch RuntimeException, class-name logs only, deterministic event ids, rollback of row on publish failure, no nonce in correlation id. x402 server bounds the validBefore window (maxTimeout), so a buyer cannot push a huge validBefore through seller-api.

Findings carried (see the report): (1) valid_before unbounded -> `valid_before + :grace` bigint overflow in ReconciliationRepository.duePaymentKeys + Java adds in ChainReconciler/ReconciliationService => one event with validBefore near Long.MAX fails every run; (2) amount only >0, not <=2^53-1 -> trial-balance longValueExact 500; (3) FixedBackOff(500,3) dead-letters VALID events on DB outage (ledger Hikari default 30 s timeout); no DLT metric/replay; (4) unauthenticated Redpanda: first-writer-wins facts, inbox eventId poisoning (key lacks topic), unbounded accounts/payments/inbox; no producer-vs-book binding; (5) seller `ON CONFLICT (payment_key) DO NOTHING` drops SETTLED after SETTLE_FAILED; (6) no JDBC socket/statement timeout on the seller paid path (Hikari 2 s covers only connection acquisition); the DB-outage test mocks TransactionTemplate; (7) oversized poison + DLT headers can make DLT publish fail -> endless recovery loop; (8) jackson coercion of "20000" strings, duplicate keys; (9) rpc-url query/path allowed (secret in URL could surface in logs).

**Why:** the M4 ledger is the independent audit of money; its inputs are an unauthenticated broker until M6.
**How to apply:** at the M4 close audit (T8) confirm these were fixed or consciously accepted; also check T4 (orchestrator) HeldPaymentResolver treats IllegalStateException (wrong chain after the deferred check) as skip.
