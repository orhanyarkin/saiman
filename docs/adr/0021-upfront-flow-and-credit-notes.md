# ADR-0021: x402 upfront flow per handler, with credit notes for paid-but-failed requests

Status: Accepted (2026-10-01). Implements ADR-0015 (M4b).

## Context
ADR-0015 kept verify → serve → settle as the default and promised an opt-in settle-first mode, with the ledger to account for requests that are paid and then fail. The seller holds no private key, so it cannot refund on chain.

The x402 v2 specification (v2.0, §6.1) defines three payment flows: `authorization` (verify → resource → settle, the default), `upfront` (settle → resource) and `escrow`. A server offering a non-default flow MUST say so in `accepts[].extra.paymentFlow`; a client MUST NOT pay for a flow it does not know and SHOULD prefer `authorization`. The spec says nothing about refunds; the `exact` EVM scheme does not mention flows. Checked on 2026-10-01: x402.org's `/verify` ignores `extra.paymentFlow` (absent, `"upfront"` and an unknown value all reached the on-chain balance check).

## Decision
- **Opt in per handler** with `@RequiresPayment(paymentFlow = PaymentFlow.UPFRONT)` (default `AUTHORIZATION`). The flow changes money semantics and the offer on the wire, so it lives in reviewed code next to the endpoint, not in deployment config. An `AUTHORIZATION` offer is byte-identical to today's.
- **Upfront request flow:** decode, check the offer, claim the nonce and `/verify` exactly as today, then `/settle` **before** the handler runs. A failed or malformed settle answers 402 as today, keeps the nonce claim and never runs the handler. A successful settle publishes the settled event at once, so the sale is recorded even if the process dies mid-handler. A replay is refused (402) without a second settle.
- **Paid but not served:** if the handler answers 3xx/4xx/5xx or throws, the buyer gets that status (500 Problem Details for an exception) **with** `PAYMENT-RESPONSE` (`success: true`, the transaction hash), and the seller issues a **credit note for the full amount**. This covers the buyer's own errors too (bad ticker, validation, per-payer 429): money moved and nothing was served, so the seller owes it. A 2xx whose body cannot be delivered (client disconnect) counts as served, as in the default flow.
- **Events:** the starter publishes `X402PaidRequestFailedEvent`; seller-api records a `credit_note` row and publishes `payments.credit-note-issued.v1` (`CreditNoteIssued`, always book SELLER, transaction hash required) through its outbox, idempotently per payment key.
- **Ledger:** seller state `NONE < SETTLE_FAILED < SETTLED < CREDITED`; `CREDITED` implies SALE + CREDIT_NOTE. CREDIT_NOTE = Dr `seller:<S>:revenue:credit-notes` (contra-revenue) / Cr `seller:<S>:liability:customer-credits`, full amount; net revenue 0, liability = amount. A credit note after SETTLE_FAILED, or with a transaction hash different from the settled one, is a CONFLICTING_FACT (reported, no postings). Reconciliation is unchanged: the credit note touches no wallet account.
- **Buyer side:** the starter client accepts `paymentFlow` ∈ {authorization, upfront}, rejects anything else, and prefers authorization when both are offered. The commit rule is unchanged: a non-2xx after paying is ambiguous → HELD, and the HELD resolver settles it from the chain. No orchestrator change.
- **Endpoints:** both paid RAG endpoints of seller-api (`questions`, `summary`) use the upfront flow. `UnsettledRunGuard` does not count settled requests against the unsettled day budget.
- **Out of scope:** redeeming credits and a buyer-side receivable (needs a seller fact in the buyer book).

## Consequences
+ No unpaid LLM run: the ADR-0015 availability gap (an attacker exhausting the unsettled day budget) is closed for both endpoints.
+ Paid-but-failed requests are visible and balanced in the ledger instead of silently kept.
− The buyer pays for its own mistakes up front and gets a credit, not cash; the credit cannot be spent yet.
− Validation and other checks after the x402 interceptor now run after settlement; a failure there costs a settle and creates a credit note.
− A paid failure stays HELD on the buyer side for about `validBefore` plus the safe-block lag.
− A credited payment that later turns out unused on chain leaves an orphan liability until a human posts a REVERSAL.
− A forged `CreditNoteIssued` on the unauthenticated Kafka is the same risk class as a forged `settled` (producer binding, bounds, conflicting-fact checks, reconciliation) until M6.

## Alternatives
- **A field on `payments.settled.v1`:** the settled event is published at settle time, before the handler's outcome exists; delaying it would lose the sale on a crash.
- **A configuration property for the flow:** flips money semantics outside code review.
- **Credit only 5xx/exceptions:** fewer liabilities, but "paid and got nothing" without any record, and a more complex rule.
