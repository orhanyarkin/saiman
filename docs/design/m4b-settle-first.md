# M4b — Settle-before-serve (x402 upfront flow) with credit notes

Contract for M4b. Decisions: ADR-0021 (implements ADR-0015). Accept (docs/PLAN.md): a settle-first handler that fails after settlement produces a credit note that balances in the ledger; the default verify → serve → settle path is unchanged.

## Shared contracts (T0, done)
- `libs/shared` `payments.CreditNoteIssued(meta, authorization, amount, payTo, resource, book, txHash, httpStatus, reasonCode)`: book must be SELLER, txHash required (0x + 64 hex), httpStatus 300-599, reasonCode `[a-z0-9_]{1,64}`.
- `PaymentTopics.CREDIT_NOTE_ISSUED = "payments.credit-note-issued.v1"`, key = payment key, one partition, ledger DLT `payments.credit-note-issued.v1.ledger-dlt` (declared by the ledger like the other DLTs; Kafka auto-create is off, ADR-0020).
- Schema `docs/events/payments.credit-note-issued.v1.schema.json`, golden fixture `libs/shared/src/test/resources/fixtures/events/payments.credit-note-issued.v1.json`.
- Reason codes used by seller-api: `handler_redirect` (3xx), `handler_client_error` (4xx), `handler_server_error` (5xx), `handler_exception` (thrown, answered 500), `async_not_supported`.

## Starter (`libs/x402-spring-boot-starter`)
- `core.PaymentFlow { AUTHORIZATION("authorization"), UPFRONT("upfront"); String wireValue(); }`.
- `@RequiresPayment(..., PaymentFlow paymentFlow() default AUTHORIZATION)`; `RequiresPaymentRegistry.Entry` carries it. An UPFRONT offer adds `extra.paymentFlow: "upfront"`; an AUTHORIZATION offer is byte-identical to today (snapshot test of `PAYMENT-REQUIRED`).
- `TestnetAssets.requireSupported` accepts `paymentFlow` absent, `authorization` or `upfront`; rejects `escrow` and unknown values. Client `selectOffer` prefers an authorization offer when both are acceptable.
- `RequiresPaymentInterceptor.preHandle`: decode, check offer, short-window refusal, claim, `/verify` exactly as today; for UPFRONT entries then `/settle` before returning true. Move settle + tx-hash check + client-facing `PAYMENT-RESPONSE` + event publication from `X402SettlementFilter` into a package-private `PaymentSettler` used by both; the default path must behave identically.
  - After `/verify`, an upfront request re-checks the window against `X402ServerProperties.Facilitator#settleMargin()`; too little left → 402 `window_too_short`, claim released, no settle (M4b audit fix). seller-api `RequestDeadlines` bounds every request (settled included) by `validBefore − now − settleMargin`.
  - The short-window (`minWindowSeconds`) refusal must stay **before** any facilitator call (`AuthorizationWindowDeadlineEndpointTests` in seller-api shows today's behaviour).
  - Settle fails (exception, `success:false`, missing or malformed tx hash): as today's `failSettlement` — 402 with `PAYMENT-REQUIRED`, **keep** the nonce claim (ambiguous), publish `X402PaymentFailedEvent`, return false; the handler never runs. `afterDispatch` must branch on "settle already attempted" before its "handler non-2xx → release claim" logic (explicit test: settle-failed 402 keeps the claim).
  - Settle succeeds: store tx hash + client-facing settlement on the attempt, publish `X402PaymentSettledEvent` (unchanged record) immediately, return true.
- `X402SettlementFilter.afterDispatch`, upfront branch: 2xx → `PAYMENT-RESPONSE`, outcome `settled`, no further event. 3xx/4xx/5xx → keep status and Problem Details body, add `PAYMENT-RESPONSE` (success, tx hash), outcome `paid_not_served`, publish `X402PaidRequestFailedEvent`. Most seller failures arrive here as 5xx/4xx through `@ControllerAdvice` (e.g. `ModelUnavailableException` → 503), not as exceptions — test that case first. Handler throws (check what the filter does today with an escaped exception first): write `500 application/problem+json` with `PAYMENT-RESPONSE`, publish the event (`handler_exception`), log the exception class only, do not rethrow, no handler headers leak. Async dispatch: same with `async_not_supported`.
- New public API: `X402PaymentContext.settled(HttpServletRequest)`, `transactionHash(HttpServletRequest)`; `record X402PaidRequestFailedEvent(UUID eventId, String resourceUrl, PaymentRequirements requirements, String from, String nonce, String value, String validBefore, String payer, String transactionHash, int httpStatus, String reasonCode, Instant failedAt)`.
- Observability: low-cardinality key `x402.payment_flow`, observation outcome `paid_not_served`; separate counters `x402.payments.paid_not_served` and `x402.payment.paid_not_served.amount` (the settled amount is already in `x402.payment.amount`, so it is not counted twice).
- `samples/console-buyer`: `testnet-check --flow=upfront` (read-only `/verify` with `extra.paymentFlow`), starter README section.
- Proof of "default unchanged": existing server and client tests pass **unmodified** (diff on existing test files additive only).

## seller-api
- Both RAG endpoints (`DisclosureQuestionController.ask`, `DisclosureSummaryController.summary`) use `paymentFlow = UPFRONT`.
- Migration `V2__credit_notes.sql`: `credit_note(payment_key PK, tx_hash CHECK 0x+64 hex, amount_atomic > 0, pay_to, payer, http_status 300-599, reason_code CHECK, created_at)`.
- `SettlementRecorder`: `@EventListener` for `X402PaidRequestFailedEvent`, one bounded transaction: `INSERT ... ON CONFLICT DO NOTHING`, and only if inserted publish `CreditNoteIssued` via the Modulith outbox (event id `eventId(key, "CREDIT_NOTE")`), externalized to `payments.credit-note-issued.v1`. A recorder failure is counted and never changes the response.
- `UnsettledRunGuard.tryStart` skips the unsettled day counter when `X402PaymentContext.settled(request)`; per-payer in-flight and hourly limits stay (a 429 after settlement becomes a credit note).
- Metrics: `saiman.seller.credit_notes{reason}`, `saiman.seller.credit_note.amount` (atomic units).

## ledger
- `PaymentFact.CreditNoted(CreditNoteIssued)` (sealed: the compiler forces `PaymentBook` to handle it); `PaymentEventParser.creditNoteIssued(json)` binds producer `seller-api`, same bounds as `settled`.
- `SellerState`: `NONE < SETTLE_FAILED < SETTLED < CREDITED`; `CREDITED.impliedEntries() = {SALE, CREDIT_NOTE}`. A credit note before its settled posts SALE + CREDIT_NOTE; the late settled posts nothing (order independence, P2).
- **Conflicting facts (no postings, reported), both delivery orders:** credit note after SETTLE_FAILED / seller failure after CREDITED; credit-note txHash ≠ seller settled txHash (either order). A seller failure after SETTLED stays accepted (M4 rule). Property generators: consistent stories for P2, any story (conflicts skipped as quarantined) for P1.
- CREDIT_NOTE postings (book SELLER, full amount): Dr `seller:<S>:revenue:credit-notes` (REVENUE, contra) / Cr `seller:<S>:liability:customer-credits` (LIABILITY); entry id `entryId(key, SELLER, "CREDIT_NOTE")`.
- Migration `V4__credit_notes.sql`: widen the seller-state check to include `CREDITED` (look up the generated constraint name first).
- New `@KafkaListener` on `payments.credit-note-issued.v1` with inbox dedupe and DLT, declared topic + DLT `NewTopic`s.
- Reconciliation: credited + matching receipt = MATCHED **only if corroborated** by seller-api (`GET /internal/credit-notes/{paymentKey}`, Host allowlist `seller-api,seller-api:8081`; ledger client `saiman.ledger.seller.*` with Resilience4j; positive answers cached in `credit_note_corroboration`, V5); otherwise `CREDIT_NOTE_UNCORROBORATED` (no posting) or PENDING when the seller is unreachable (M4b audit fix). Edge: chain UNUSED but CREDITED → SETTLED_BUT_UNUSED, wallet difference to suspense, liability stays until a human REVERSAL (document).
- Tests: `PaymentBook` orders (credited before/after settled, duplicates, credited without settled, conflicts); P1/P2 generators extended with credit-note facts (one `@Test` per property, ADR-0019); P1 also asserts credit-notes debit = customer-credits credit = amount, net revenue 0; Kafka end-to-end with trial balance 0; DLT for a malformed credit note; reconciliation MATCHED.

## Live verification (T2, orchestrator)
1. `testnet-check --flow=upfront` accepted by x402.org (verified once by a probe on 2026-10-01).
2. `make up`, console-buyer `buy` against `/v1/disclosures/ZZZZ/summary`: 404 + settled tx + `credit_note` row + ledger CREDIT_NOTE; `make recon-run` MATCHED; `make ledger-balance` sums to 0.
3. A normal summary buy returns 200; `make research-run` still completes with ≥ 2 paid calls.
