# ADR-0017: Ledger model: double-entry, immutable, per-payment state machine

Status: Accepted (2026-10-01).

## Decision
- **Double-entry** in `services/ledger` (schema `ledger`): `account`, `journal_entry`, `posting` (positive `amount_atomic bigint` + `side` DEBIT/CREDIT + asset/decimals; rule 4). Every entry has at least two postings and debits equal credits **per asset**, enforced by a `DEFERRABLE INITIALLY DEFERRED` constraint trigger at commit and again by the domain code.
- **Immutable:** `BEFORE UPDATE OR DELETE` triggers reject changes to entries and postings; corrections are `REVERSAL` (and `ADJUSTMENT`) entries.
- **Books:** each payment is recorded in the buyer's book (orchestrator events) and the seller's book (seller-api events). Chart of accounts (USDC, 6 decimals, created on first use): `buyer:<B>:wallet:available`, `buyer:<B>:wallet:encumbered`, `buyer:<B>:expense:data`; `seller:<S>:wallet`, `seller:<S>:revenue:data`, `seller:<S>:revenue:credit-notes` and `seller:<S>:liability:customer-credits` (reserved for settle-first, M4b); `platform:suspense:usdc`. No facilitator account: `exact` transfers buyer → payTo directly. Wallet accounts record flows, not faucet balances.
- **Entry kinds:** ENCUMBER (authorized), SETTLE (buyer), SALE (seller), RELEASE (expired unused), ADJUSTMENT (reconciliation to suspense), REVERSAL, CREDIT_NOTE (reserved), LLM_USAGE (optional, USD book).
- **Per-payment state machine:** a mutable projection `payment(payment_key, buyer_state, seller_state, chain_state, …)` drives which entries an event implies; `UNIQUE (payment_key, book, kind)` on one-per-payment kinds makes postings idempotent and order-independent (a `settled` before its `authorized` posts ENCUMBER + SETTLE; the late `authorized` posts nothing).
- **Suspense rule:** the chain is right. Reconciliation compares each known wallet's net movement for a payment with the chain; a difference is posted between that wallet and `platform:suspense:usdc` and recorded as a mismatch; a human clears it with a REVERSAL.

## Consequences
+ The balanced-postings invariant is enforced by the database, not only by code.
+ Replays, duplicates and reordering produce the same books.
− A superuser can still disable triggers (shared DB role; M6 adds per-service roles). The tamper demo uses exactly this gap.
