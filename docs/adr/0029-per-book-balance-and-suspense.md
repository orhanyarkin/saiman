# ADR-0029: Every entry belongs to one book; suspense lives in the book that needs it

Status: Proposed (2026-10-20). Amends ADR-0017 (ledger model) and ADR-0018 (reconciliation).

## Context

ADR-0017 enforces balance **per entry, per asset**: the deferred constraint trigger
`check_entry_balanced` groups by `(asset, decimals)` and `JournalEntry.requireBalanced` does the
same in the domain. Nothing relates a posting's account book to the entry's book.

Reconciliation's suspense rule was built on top of that. `ChainReconciler.addLegs` posts the
difference between a wallet and `platform:suspense:usdc` in a single entry labelled `PLATFORM`,
so an adjustment for a seller wallet is Dr `platform:suspense:usdc` (book PLATFORM) against
Cr `seller:<payTo>:wallet` (book SELLER). The entry balances; neither book does.

The dashboard groups the trial balance by the **account's** book and tells the reader "each book
always balances". After one `make ledger-tamper-demo` plus a reconciliation run, the local stack
shows PLATFORM 0.005 / 0.00 and SELLER 0.645 / 0.65, with a red warning on a correct ledger. The
whole ledger does sum to zero, which is what M4 actually accepted ("trial balance sums to 0").

Two ways out. Weaken the claim to the whole-ledger sum, keeping cross-book entries; or make the
stronger claim true. A per-book trial balance is the one a reader can check against a single
counterparty, and "this book balances" is the sentence a reviewer expects from double-entry
bookkeeping. The claim is worth keeping.

## Decision

1. **An entry belongs to exactly one book.** Every posting's account book equals the entry's book.
   Enforced by the database (a condition in `check_entry_balanced`, which already runs deferred at
   commit for every new entry and posting) and by `JournalEntry`, which gains the book alongside the
   existing balance check. Cross-book entries become impossible.

2. **Balance stays per entry and per asset.** Because an entry is now single-book, per-book balance
   per asset follows: every posting belongs to exactly one book, so summing a balanced entry into
   its book keeps that book balanced. The UI check (`Σ debits = Σ credits` per book and asset) is
   then a consequence of the invariant rather than a hope.

3. **Suspense moves into the book whose wallet moved.** New accounts
   `buyer:<payer>:suspense:usdc` (book BUYER) and `seller:<payTo>:suspense:usdc` (book SELLER), both
   type SUSPENSE. A seller difference is Dr `seller:<payTo>:suspense:usdc` / Cr
   `seller:<payTo>:wallet`, entirely in the seller's book. `platform:suspense:usdc` is retired: it
   keeps its history and takes no new postings. The PLATFORM book ends up empty.

4. **One ADJUSTMENT per payment, run and book.** Where a payment needed one entry with up to four
   legs, it now needs one entry per affected book. The entry id derivation gains the book, which it
   already takes as a parameter (`PaymentBook.entryId`), so reruns stay idempotent.

5. **The reconciliation report sums every suspense account**, not `platform:suspense:usdc` alone,
   and reports the figure per book. `ReconciliationRepository.suspenseBalance` changes accordingly.

6. **The history is corrected by appending, never by editing, in two migrations.**

   `V9__per_book_suspense_data.sql` corrects the data, under the old rule:

   a. For every existing ADJUSTMENT whose postings span more than one account book, insert a
      `REVERSAL` entry naming it (`reverses_entry_id`) with the opposite postings. This reversal is
      itself cross-book, which only the old rule permits.

   b. Insert the replacement ADJUSTMENT in the book whose wallet was adjusted, against that book's
      new suspense account.

   `V10__per_book_balance.sql` then tightens the rule: it creates the new suspense account rows if
   they are not there yet and replaces the trigger function with the single-book condition.

   **Why two migrations and not one.** `journal_entry_balanced` and `posting_balanced` are
   `DEFERRABLE INITIALLY DEFERRED` constraint triggers, so their events queue up and fire at
   `COMMIT`. `CREATE OR REPLACE FUNCTION` keeps the function's OID, so a replacement inside the same
   transaction would be the body those queued events run against: the cross-book reversal from
   step (a) would be checked by the new single-book rule at commit and the migration would fail.
   Splitting the work puts each half in its own transaction, so step (a) is checked and committed
   under the old body before the new body exists.

   `SET CONSTRAINTS ... IMMEDIATE` at the top of a single migration would also work, by forcing the
   checks to run at statement time against the old body. It is rejected because it makes correctness
   depend on constraint-timing semantics that a later editor would have to know about, and because
   two migrations keep "correct the data" and "tighten the rule" as separate reviewable units with
   separate failure modes. Flyway runs each migration in its own transaction unless `group` is set,
   and `group` is not set anywhere in this repository (checked 2026-10-20), so the split is enough.

   Constraint triggers fire per row on INSERT, so rows written by V9 are never re-checked after V10.
   Nothing is updated, deleted or truncated, and the immutability triggers stay on throughout: the
   correction is two new entries, exactly the mechanism ADR-0017 prescribes. The net effect on
   `seller:<payTo>:wallet` is zero, `platform:suspense:usdc` returns to zero, and the difference
   reappears in `seller:<payTo>:suspense:usdc` where it belongs.

   V9 finds its targets by query (an ADJUSTMENT whose postings' accounts have more than one distinct
   `book`), so it is a no-op on a database that has none. Both migrations run as the ledger owner
   role, which V7 and V8 kept able to write; the runtime role is still refused.

## Consequences

+ "Each book balances" becomes an invariant the database enforces, not a claim the UI makes.
+ A reader can check one counterparty's book on its own.
+ An unresolved difference is visible as a non-zero suspense balance **inside the book that owns
  it**, which is more useful than a platform-wide pot.
− One more account per counterparty, and `platform:suspense:usdc` lingers as a zero-balance relic.
− The reconciliation report's suspense figure is now a sum over accounts, so a per-book breakdown
  has to be carried through the report and the API.
− One migration posts entries, which no previous ledger migration does. It is the only way to
  correct history without editing it.

`make ledger-tamper-demo` keeps working unchanged: it still raises a SALE and reconciliation still
catches it. What changes is where the difference lands. The seller book keeps balancing, and the
0.005 shows up as `seller:<payTo>:suspense:usdc`, an open finding in the seller's own book, instead
of as an imbalance across two books.

## Verification

Testcontainers tests against a real Postgres, in `services/ledger/src/test/.../journal/`, driving
Flyway the way the service does:

1. **Migration on a tampered database.** Seed a database at V8 with a settled payment, its SALE in
   the seller's book, and a cross-book ADJUSTMENT of 5000 atomic units (Dr `platform:suspense:usdc`
   / Cr `seller:<payTo>:wallet`), the exact shape `make ledger-tamper-demo` plus a reconciliation
   run produces. Migrate to head, then assert: every book balances per asset; the net of
   `seller:<payTo>:wallet` is unchanged from before the migration; `platform:suspense:usdc` nets to
   zero; `seller:<payTo>:suspense:usdc` holds the 5000 debit; the original ADJUSTMENT row is
   untouched and now has exactly one REVERSAL naming it.
2. **The new rule bites.** On that migrated database, inserting a cross-book entry fails at commit
   with the trigger's error, and a single-book entry still succeeds.
3. **Migration on a clean database.** Migrate an empty database to head and assert it succeeds, that
   no REVERSAL or ADJUSTMENT was created, and that a normal ENCUMBER/SETTLE pair still posts.
4. **The whole-ledger sum stays zero** in both cases, so the weaker invariant is never traded away
   for the stronger one.

The existing `LedgerSchemaTests` balance and immutability cases must keep passing unchanged, and
`make ledger-tamper-demo` followed by a reconciliation run must still produce a finding, now with
the difference in `seller:<payTo>:suspense:usdc` and with the seller book still balanced.

## Follow-up (not in this change)

Nothing in the code posts a REVERSAL of an ADJUSTMENT. ADR-0017 says a human clears suspense with
one, but the only REVERSAL producer is the automatic release reversal in `PaymentBook`, and there is
no endpoint, make target or script for the manual case. So a difference parked in suspense stays
there forever. Deciding whether a sanctioned clearing path should exist, and who may use it, is a
separate decision; this ADR only moves the parking space into the right book.
