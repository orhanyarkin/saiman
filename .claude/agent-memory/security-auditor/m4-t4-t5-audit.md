---
name: m4-t4-t5-audit
description: M4 T4 (orchestrator outbox, HeldPaymentResolver, backfill) + T5 (ledger reconciliation, report API) audit 2026-10-01 - forged payments.* defeats reconciliation (validBefore overflow, asset double-book, starvation, CONFLICTING_TX freeze, MATCHED on open books)
metadata:
  type: project
---
Read-only audit 2026-10-01, branch m4-ledger @ 1bfd1bd. No probes; traced by reading (+ javap of spring-tx 7.0.9 and Modulith 2.1.1 jars).

No Critical/High. Spend plane is clean: orchestrator has no Kafka consumer; resolver reads authorizationState at the safe block only when validBefore < safe.timestamp (stricter than EIP-3009), 0/1 only, USDC address hardcoded, lock order intent->run->day everywhere, reserved>=amount guards, HELD re-checked under FOR UPDATE, null-nonce HELD skipped+counted.

Mediums (all need write access to unauthenticated Redpanda, 127.0.0.1:9092 or compose net):
- CARRIED from T3 audit, still open at 1bfd1bd: duePaymentKeys `valid_before + :grace` bigint overflow; AuthorizationRef only checks validBefore > 0 -> one forged event makes every run FAILED.
- Ledger never checks payment asset_address == USDC and has no unique (payer, nonce); reconciler reads USDC only -> forged event with another asset + real payer/nonce + real tx = second MATCHED payment (double booking).
- Due ordering last_checked_at NULLS FIRST, created_at DESC, LIMIT 50 -> flood of fresh forged payments starves real ones.
- PaymentBook firstNonNull seller/buyer tx hash + ChainReconciler early return on 2 hashes -> forged bogus hash freezes a real payment in CONFLICTING_TX.
- ChainReconciler returns MATCHED when no book is terminal (buyer AUTHORIZED, seller NONE) even past validBefore; with ConflictingFact->DLT a real payment hides as MATCHED.
Lows: resolver head-of-line (ORDER BY valid_before LIMIT 20, no attempt marker), pass tx idle across RPC holding xact lock, Lease.close unlock failure returns pooled conn with session lock, findAuthorizationTx doesn't re-check returned topics, multi-auth relay tx sums all payer transfers, manual POST runs have no cooldown (shared public RPC quota with orchestrator).
Verified: wrong chain id after deferred check -> IllegalStateException -> resolver pass aborts / item 'error' (no release); ledger run FAILED. Fail-closed.
Info: AfterCommit uses afterCommit (exceptions propagate to committer) whereas @TransactionalEventListener AFTER_COMMIT ran in afterCompletion (swallowed); current listeners don't throw. Modulith externalizer is @ApplicationModuleListener (async), so Kafka outage doesn't block signed()/commit(). agent.run-step.v1 puts full SSE envelope on unauthenticated Redpanda.
**How to apply:** at M4 close (T8) re-check these fixes landed; verify ledger rejects non-USDC asset and bounded validBefore; re-check report MATCHED semantics.
