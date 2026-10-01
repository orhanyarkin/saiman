# Event schemas

One JSON Schema file per topic, named `<topic>.schema.json`; a golden example of each topic lives in `libs/shared/src/test/resources/fixtures/events/<topic>.json` and is round-tripped by `EventFixtureTests`. `agent.run-step.v1` is documented in `agent.run-step.v1.md`.

| topic | producer | message key | consumer |
|---|---|---|---|
| `payments.authorized.v1` | orchestrator | payment key | ledger |
| `payments.settled.v1` | orchestrator (BUYER), seller-api (SELLER) | payment key | ledger |
| `payments.failed.v1` | orchestrator (FINAL), seller-api (AMBIGUOUS) | payment key | ledger |
| `agent.run-step.v1` | orchestrator | run id | none in M4 |
| `ledger.entry-posted.v1` | ledger | payment id | none (M5) |
| `ledger.reconciliation-mismatch.v1` | ledger | payment id | none (M5) |

Common rules (ADR-0016):
- Every event carries `meta: {eventId, occurredAt, producer, correlationId}`; consumers dedupe on `eventId` in an inbox table in the same transaction as their state change, and on business keys (the payment key `network:asset:payer:nonce`, lower-case).
- Money is `{"atomicUnits": <JSON integer ≤ 2^53-1>, "asset", "decimals"}`; no floats, no decimal strings.
- Events never carry a signature, an idempotency key or free text from a facilitator (failure reasons are `[a-z0-9_]{1,64}` codes). `payments.*` carry the payer and nonce (public on chain once used); `ledger.*` do not carry the nonce.
- Delivery order is not guaranteed (Spring Modulith externalization); consumers are order-independent.
- `CONFLICTING_FACT` mismatches are recorded in the ledger database and report only; `ledger.reconciliation-mismatch.v1` carries mismatches found by a reconciliation run.
- Breaking changes create a new `.vN` topic.
