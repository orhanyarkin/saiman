# ADR-0015: Settle-before-serve is opt-in and lands in M4

Status: Accepted (2026-09-30). Follows the T4 audit finding and the M2 threat-model gap "unpaid LLM runs can exhaust the per-day unsettled budget".

## Context
The starter verifies, runs the handler, then settles (ADR-0008). For handlers that spend money before settlement (the RAG endpoints call an LLM) an attacker with funded wallets can consume the day's unsettled budget: the seller then answers **429 to every payer until UTC midnight**. Spend is bounded by the run guard, the router's cap and the provider limit; availability is not.

## Decision
- Keep verify -> serve -> settle as the default and document the limit (THREAT_MODEL, README).
- Add an **opt-in settle-first mode** to the starter (per `@RequiresPayment` handler) in **M4, together with the ledger**: settle first means a paid request can fail afterwards, and with no seller key there are no on-chain refunds, so the ledger needs credit notes.
- M3 does only the cheap part: the seller's deadline derives from the authorization's `validBefore` (and a startup assertion keeps the timeouts inside `minWindowSeconds`).

## Alternatives
- Settle-first now: fixes availability but creates paid-but-failed requests without a refund path.
- Stake or allowlist payers: out of scope for a public demo.

## Consequences
+ The known limit is stated in the README and threat model, not hidden.
− Until M4 a burst of funded-wallet requests can make the demo return 429 for a day.
