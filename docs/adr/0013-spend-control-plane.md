# ADR-0013: The spend-control plane (Postgres is the authority)

Status: Accepted (2026-09-30). Implements CLAUDE.md rule 3; builds on ADR-0008 (x402 client `SpendGuard`).

## Context
The orchestrator's agents pay for tools per call. A budget that lives in a prompt, or that a model-influenced value can change, is not a budget. The starter's client already calls a `SpendGuard` after decoding a 402 and before signing; M3 supplies the guard.

## Decision
- `BudgetSpendGuard implements SpendGuard` is the only guard bean. The check runs before `signTransferWithAuthorization`, so a refused payment is never signed.
- **Payments bind to a run without a ThreadLocal.** The tool gateway inserts a `payment_intent` row (PENDING) with an opaque 128-bit random idempotency key before sending the request; `reserve` looks the key up and refuses an unknown key or one not in PENDING/APPROVED (`UNKNOWN_INTENT`). Only requests created by code can be paid; the key is never derived from model text.
- **`reserve`** is one Postgres transaction: payee on the allowlist and amount <= the per-request max (the interceptor checks both too); `SELECT ... FOR UPDATE` on the run row, then the UTC `spend_day` row (fixed lock order, no deadlock); `reserved + committed + amount <= run budget` and the day's total <= the daily cap (a DB CHECK backs the run invariant); above the approval threshold without an APPROVED approval the intent goes AWAITING_APPROVAL and `ApprovalRequiredException` (a `SpendDeniedException`) is thrown; otherwise a conditional update to RESERVED adds the amount to both counters.
- `signed` (after signing, before the paid retry is sent) records payer, nonce and `validBefore` for M4 reconciliation; if it throws, nothing was sent and the reservation is released. `commit` moves reserved to committed on both rows and stores the tx hash. `release` is possible only before anything was sent. An ambiguous outcome (signed, result unknown) sets HELD and **a held reservation keeps counting** until M4 reconciles it through `authorizationState(from, nonce)` after `validBefore`.
- **Approvals.** The gateway emits `PAYMENT_APPROVAL_REQUIRED` and the run's virtual thread waits on an in-process future keyed by approval id (the DB row is the source of truth; a restart marks unfinished runs FAILED `INTERRUPTED`, held reservations stay). On APPROVE the same request is re-sent with the same key and the fresh 402 must match the approved `(amount, payTo, resource)`. **An approval only opens the threshold gate; it never raises the run budget or the daily cap.**
- **The model cannot change a limit:** the budget is set once at `POST /runs`, bounded by a server maximum, and immutable through a DB trigger; no endpoint updates it; the model sees exactly two tools without URL, amount, payee, budget or key parameters; the seller base URL and payee allowlist come from config; the gateway counts calls per run in code, dedupes identical `(tool, argsHash)` calls against an earlier SETTLED result, and never lets an exception reach Spring AI's exception processor (fixed strings only).
- **LLM cost** is a separate scope: the router's `ScopedCostGuard` (ADR-0011 amendment) caps each run's model spend; the total cost of a run is `RunCost` (USDC payments + USD LLM, a 1 USDC = 1 USD testnet assumption).

## Why Postgres, not Valkey
A money decision must be durable and in the same transaction as the reservation record; a held reservation must survive restarts and Valkey eviction; Valkey has no authentication yet (known gap). Volume is a few payments per run. Valkey keeps only the ephemeral per-run LLM scope (still "Valkey + Postgres" as rule 3 says).

## Alternatives
- Valkey counters with Lua (as the router does): fast, but not durable or transactional with the intent record.
- A limit enforced by the tool or the prompt: explicitly rejected by rule 3.

## Consequences
+ The acceptance tests (budget blocks before signing, injection cannot raise a budget) are deterministic against a real database.
+ M4's reconciliation has the data it needs (`payer`, `nonce`, `validBefore`, tx hash).
− One row lock per paid call serialises a run's payments; acceptable at this volume and already required by the seller's per-payer limit.
− A held reservation can block a run until M4 reconciles it.

## Amendment (2026-10-01, M4): HELD resolution
HELD intents are resolved by the orchestrator from chain facts (ADR-0018): HELD → SETTLED when `authorizationState` is true at the `safe` block past `validBefore` (reserved → committed), HELD → RELEASED when it is false (reserved released). `payment_intent.resolved_by` (FACILITATOR | CHAIN) and `resolved_at` record how. A HELD reservation keeps counting until then; an RPC failure leaves it counted (fail closed).

- A HELD intent without a nonce (marked HELD before anything was signed) is released immediately with `resolved_by = LOCAL`, without a chain read or a payment event: its signature provably never left the process.

## Amendment (2026-10-01): Redis and Apache Kafka (ADR-0020)
Read "Valkey" above as Redis: the router's counters moved to Redis. The reasons for keeping payment budgets in Postgres (durability, same transaction as the intent, no auth on the cache yet) apply to Redis unchanged.
