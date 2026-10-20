---
name: adr0029-ledger-per-book-audit
description: ADR-0029 per-book audit + re-review of the fix commits (2026-10-20) — Finding 1 closed at 0f74a87; crossBookEntries exemption is linkage-not-mirror; mismatch-vs-item path untested
metadata:
  type: project
---

Audit of ADR-0029 (one book per entry, suspense per book) on branch `ledger-per-book-impl`
(commits 35591e9, e2b8483). Read-only; `:services:ledger:check` green (261 tests, 28 classes).

**Medium — V9 `RAISE EXCEPTION` when no `reconciliation_mismatch` points at a cross-book ADJUSTMENT.**
`ReconciliationRepository.insertMismatch` is `ON CONFLICT (payment_id, kind) DO NOTHING`, so the *second*
adjustment for the same (payment, kind) is never linked: the row keeps the first adjustment's id. Two ways in:
(a) `make ledger-tamper-demo && make recon-run` twice — `duePaymentKeys` has no cooldown ("Matched payments
stay due"); (b) no tampering at all — a receipt whose direct payer→payTo transfer differs from the
authorization while the buyer's diff is 0, then the seller book reports in a later run (kind stays
AMOUNT_MISMATCH). V9 then aborts → failed Flyway migration → ledger will not start until a human repairs it.
Diagnostic before any deploy:
`SELECT e.id FROM journal_entry e WHERE e.kind='ADJUSTMENT' AND NOT EXISTS (SELECT 1 FROM reconciliation_mismatch m WHERE m.adjustment_entry_id = e.id)`.
Fallback available in the data: `reconciliation_item (run_id, payment_id, adjustment_entry_id)` carries it.

**Why the abort protects nothing.** `runId = UUID.randomUUID()` server-side in
`ReconciliationService.execute`, so no later run ever re-derives V9's entry id. Idempotency across the
migration rests entirely on `payment_id` being set on the REVERSAL and the replacement, so
`ReconciliationRepository.nets` counts them and the diff is zero. The ADR's "a later run recognises it as
already posted" (step 7b) and verification 5's "a drift shows up as a duplicate adjustment" are both wrong.
`PerBookSuspenseMigrationTests` test 1 is where both properties are actually asserted (the id formula at
lines 164-165, the net-convergence precondition at lines 168-173).

**SQL UUIDv3 derivation is correct.** V9's `name_uuid_v3` (`substr(h,1,12) || '3' || substr(h,14,3) ||
to_hex((('x'||substr(h,17,1))::bit(4)::int & 3) | 8) || substr(h,18,15)`) matches
`UUID.nameUUIDFromBytes` for all 16 variant nibbles (checked offline against Java's byte math). The in-repo
assertion is probabilistic for the `& 3` half: the test's run id is random, so a missing `& 3` is caught
~50% of runs. A fixed-vector test would make it deterministic.

**`JournalRepository.ensureAccount` is `ON CONFLICT DO NOTHING` with no type/book check.** Newly relevant:
suspense accounts are now per-wallet and created on first use, and `suspenseBalances` keys on
`a.type = 'SUSPENSE'`. A pre-inserted `seller:<x>:suspense:usdc` with `type='ASSET'` (the app role holds
INSERT on `account`) absorbs the mirror leg and drops out of the reconciliation report while every book still
balances. The retired `platform:suspense:usdc` was code-keyed, so it had no such gap.

**Invariant is forward-only and nothing counts violations.** After V9 every corrected database keeps two
cross-book entries forever (the original ADJUSTMENT and its REVERSAL); they cancel per book, so sums hold,
but "cross-book entries are impossible" is only true of rows committed after V10.
`ReconciliationRepository.unbalancedEntries` is still the only internal structural check — no `crossBookEntries()`.
Any future hard assertion must exempt the V9 pair.

**Sound, do not re-derive:** V9 is insert-only, no UPDATE/DELETE/TRUNCATE, no `session_replication_role`, no
trigger ALTER; the correction is value-neutral on the wallet and the whole ledger still sums to zero (test 1);
`LedgerQueries.findings` LATERAL is scoped by `a.payment_id = m.payment_id`; the app role gains nothing (V9's
helper function is dropped in the same transaction); no injection surface (V9 has no `EXECUTE`, and the
concatenated account code comes from CHECK-constrained `wallet`/`asset` columns).

See [[m4-close-audit]], [[m4-close-fix-reaudit]], [[m5-dashboard-fix-reaudit]].

## Re-review 2026-10-20 (`e2b8483...0f74a87`): no Critical/High/Medium, merge-ready

Finding 1 closed. Every V9 abort path was enumerated and is unreachable for app-written data: the
old adjustment ids used `book = PLATFORM`, so a replacement can never collide with an original; the
old formula is run-derived, so two originals per (payment, run) are impossible, which makes the
`IF EXISTS` fallback dead but safe; `journal_entry_reversed_once` is unreachable because nothing in
the code reverses an ADJUSTMENT; `abs(net)` is bounded by V3's 2^53-1 per posting. The surviving
`RAISE` (NULL `payment_key`) is reachable only by an app-role INSERT and is the safer branch there,
since a NULL would NULL the derived uuid and die on the primary key with a worse message.
`v_run := NULL` is correct but not load-bearing: a non-STRICT `SELECT ... INTO` already nulls its
target on zero rows.

**`crossBookEntries()` exempts by linkage, not by mirroring.** Sound against the app role: V7 lines
22-30 withhold UPDATE on `journal_entry`, `posting` and `account`, and V10's per-row trigger
re-checks the whole entry on each posting INSERT, so V9's exempt pair is not a parking slot. Leaky
against the bypassing writer it targets: a cross-book entry plus *any* cross-book REVERSAL naming it
is invisible, and so is a posting with no `account` row, because replica mode disables FK triggers
too. If revisited, compare the mirror posting multiset.

**Test gaps that survive mutants:** dropping either `crossed` correlation from the two `NOT EXISTS`
clauses passes the whole suite, and `firstAdjustment` carries a mismatch row and an item row with
the same run, so removing V9's mismatch lookup goes undetected.

Saturation is correct at both ends (`±Long.MAX_VALUE`, which is exactly what keeps `Math.absExact`
from throwing), and 1025 postings is the tight minimum that crosses `Long.MAX_VALUE`. Residual: the
headline `suspense` has no `saturated` flag and clamps past 2^53-1, unlike `RevenueReport`.
