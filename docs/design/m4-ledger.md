# M4 design contract — ledger and reconciliation

Binding for every M4 task. Source: architect pass (2026-10-01) and the human's decisions: outbox with **Spring Modulith** (not hand-rolled), property tests with seeded JUnit 5 generators (jqwik ruled out, ADR-0019), settle-first split into **M4b**, seller-api gets schema `seller_api`, ledger HTTP uses the orchestrator guard posture (authn in M6), chain reads only via the public `https://sepolia.base.org`. ADRs: 0016, 0017, 0018 (+ ADR-0013 amendment), 0019, ADR-0015 amendment.

**Acceptance (PLAN.md):** balanced-postings property test passes; duplicate and out-of-order events handled; a deliberately corrupted record shows up as a mismatch in the report.

## Standing rules
1. Money is integer atomic units; testnet only (`eip155:84532`, chain id 84532 checked at startup); no secret, signature or idempotency key in events, logs or spans; nonces only in `payments.*`.
2. Every state change that emits an event publishes it through Spring Modulith's registry in the same transaction (ADR-0016); every consumer dedupes through `InboxGuard` in the same transaction as its postings; consumers are order-independent.
3. Decisions that release budget or declare a mismatch read the chain at the `safe` block; an RPC failure is "skip", never a mismatch; HELD stays counted until resolved (fail closed).
4. The spend plane consumes nothing from Kafka (Redpanda has no auth).
5. Hermetic CI: chain calls are stubbed; live chain tests are `@Tag("testnet")` and excluded from `check`.
6. Every task report ends with "Spring notes".

## Contracts (T0, done)
- `libs/shared`: `events.EventMetadata`; `payments.{AuthorizationRef, Book, SettlementEvidence, Finality, PaymentAuthorized, PaymentSettled, PaymentFailed, PaymentTopics}`; `ledger.{Side, PostingLine, EntryPosted, MismatchKind, ReconciliationMismatch, LedgerTopics}`; `Money` serialises as `{atomicUnits, asset, decimals}` everywhere. Golden fixtures + schemas: `docs/events`.
- `libs/eventing`: `InboxGuard` (API); T1 implements it plus a `@AutoConfiguration` that sets the Modulith defaults (`registry-trigger-annotation` = `org.springframework.modulith.events.ApplicationModuleListener`, republish on restart, completion purge) and documents the `inbox` table DDL each consumer copies into its own Flyway migration:
  `inbox(event_id text, consumer text, topic text not null, received_at timestamptz not null default now(), primary key (event_id, consumer))`.
- `libs/evm-rpc`: `BaseSepoliaUsdc` (`block(tag)`, `authorizationState(from, nonce, block)`, `receipt(tx)`, `findAuthorizationTx(from, nonce, fromBlock, toBlock)`), `BlockTag`, `ChainBlock`, `UsdcReceipt`, `UsdcTransfer`, `ChainUnavailableException`. Config `saiman.chain: {rpc-url: https://sepolia.base.org, allowed-hosts: [sepolia.base.org], connect-timeout: 3s, read-timeout: 10s, max-requests-per-second: 5, log-chunk-blocks: 1000}`.
- Catalog: `spring-modulith` 2.1.1 (BOM, `starter-jdbc`, `events-kafka`, `events-api`), `spring-boot-starter-kafka(-test)`, `testcontainers-redpanda`, `jackson-annotations`.

## Producers
- **Orchestrator** (`V6__payment_events.sql`): Modulith `event_publication` table in schema `orchestrator`; `payment_intent` gets `resolved_by text check in ('FACILITATOR','CHAIN')`, `resolved_at timestamptz`, and a partial unique index on `(lower(payer), lower(auth_nonce)) where auth_nonce is not null`. Publish `PaymentAuthorized` in the transaction that marks an intent SIGNED (`markSigned` becomes transactional together with the publication), `PaymentSettled` (BUYER, FACILITATOR) in `BudgetSpendGuard.commit`, and from `HeldPaymentResolver` either `PaymentSettled` (CHAIN) or `PaymentFailed` (FINAL, `expired_unused`). `agent.run-step.v1`: externalize each appended `run_event` (key run id). `V7__backfill_payment_events.sql` (or an idempotent startup backfill) publishes events for existing SIGNED/SETTLED/HELD intents so M3 history reconciles.
- **HeldPaymentResolver** (orchestrator, `saiman.orchestrator.held-resolution: {interval: 2m, batch-size: 20}`): for HELD intents whose `valid_before` is before the `safe` block's timestamp, read `authorizationState` at that block; true → SETTLED (reserved → committed under the existing lock order; tx from `findAuthorizationTx`, else null) ; false → RELEASED (reserved released). RPC failure → untouched.
- **seller-api** (`V1__seller_schema.sql`, schema `seller_api`, first Flyway use in seller-api): `settlement(payment_key pk, tx_hash, amount_atomic, pay_to, payer, outcome, created_at)` + Modulith registry; an `@EventListener` on the starter's settled/failed events writes the row and publishes `PaymentSettled` (SELLER, FACILITATOR) or `PaymentFailed` (SELLER, AMBIGUOUS, bounded reason) in one transaction. A database failure must not fail the paid response already settled: log + metric.

## Ledger (`services/ledger`, schema `ledger`)
- Tables (V1): `account(code, book, type, asset, decimals, wallet, unique(code, asset))`; `journal_entry(id, payment_id, payment_key, book, kind, source_event_id, reverses_entry_id, description, effective_at, recorded_at)` with a partial unique index `(payment_key, book, kind)` for ENCUMBER/SETTLE/RELEASE/SALE/CREDIT_NOTE; `posting(id identity, entry_id, account_code, side, amount_atomic > 0, asset, decimals)`; deferred constraint trigger (≥2 postings, Σdebit = Σcredit per asset at commit); immutability triggers; `payment` projection (buyer_state NONE/AUTHORIZED/SETTLED/RELEASED, seller_state NONE/SETTLED/SETTLE_FAILED, chain_state UNKNOWN/USED/UNUSED, tx hashes, valid_before, last_checked_at); `reconciliation_run`, `reconciliation_mismatch(unique(payment_id, kind))`; `inbox`; Modulith registry for the ledger's own events.
- Consumers: `@KafkaListener` per `payments.*` topic, String payload parsed with Boot's `JsonMapper` into the shared records (no type headers), `@Transactional`, record ack mode; `InboxGuard` first; then the pure state machine produces entries; publish `EntryPosted` per entry. Poison messages go to `<topic>.ledger-dlt`.
- Accounting (ADR-0017) — worked examples: settled 0.02 call → ENCUMBER (Dr buyer encumbered / Cr buyer available 20000), SALE (Dr seller wallet / Cr seller revenue 20000), SETTLE (Dr buyer expense / Cr buyer encumbered 20000); HELD then released → ENCUMBER then RELEASE, net 0; approval rejected → no entries; corrupted SALE (25000 instead of 20000) → reconciliation posts ADJUSTMENT Dr suspense 5000 / Cr seller wallet 5000 and an AMOUNT_MISMATCH.
- Reconciliation (`saiman.ledger.reconciliation: {interval: 5m, grace-after-valid-before: 30m, receipt-grace: 10m, batch-size: 50}`): single runner (`pg_try_advisory_lock`); read `safe`; internal checks; select due payments; per payment in its own transaction fetch receipts / `authorizationState` / log lookup, feed the same state machine, write mismatches, adjustments and events; run status COMPLETED or PARTIAL (RPC skips).
- HTTP (guarded like orchestrator `/api`): `GET /api/v1/ledger/trial-balance`, `POST /api/v1/reconciliation/runs` → 202 `{runId}`, `GET /api/v1/reconciliation/runs/{id|latest}` → report `{runId, startedAt, finishedAt, status, network, safeBlock, summary{checked, matched, pending, resolvedUsed, resolvedUnused, mismatches}, suspense, items[{paymentId, paymentIntentId, runId, payer, payTo, amount, buyerState, sellerState, chainState, txHash, status MATCHED|PENDING|MISMATCH|TX_UNKNOWN, mismatch{kind, ledgerValue, chainValue, adjustmentEntryId}}]}`. Never a nonce.

## Tasks
| # | owner | owned dirs | deps |
|---|---|---|---|
| T0 | orchestrator | libs/shared, docs, catalog, settings, `libs/eventing` + `libs/evm-rpc` APIs | — (done) |
| T1 | payments-engineer | libs/eventing, libs/evm-rpc | T0 |
| T2 | infra (parallel to T1) | deploy, Makefile, scripts, .env.example, .github | T0 |
| T3 | payments-engineer | services/ledger (schema, state machine, consumers, trial balance, guard, property tests) | T1 |
| T4 | agent-engineer (parallel to T3) | services/orchestrator (publications, resolver, backfill) | T1 |
| T5 | payments-engineer | services/ledger (reconciliation, report) | T3 |
| T6 | payments-engineer (parallel to T5) | services/seller-api | T1 |
| T7 | orchestrator | live verification | T2-T6 |
| T8 | security-auditor (opus) | milestone audit | T7 |
Per-task audits: T1 and T3 and T6 (sonnet), T4 and T5 (opus). Cut order if the week slips: LLM USD postings → `ledger.entry-posted.v1` → T6.

## Live plan (T7)
`make up` (backfill publishes M3 history) → resolver settles or releases real M3 HELD intents from chain reads → `make recon-run && make recon-report` (real tx hashes MATCHED) → one `make research-run` with approval → reconcile again → `make ledger-tamper-demo` → MISMATCH + suspense in the report.
