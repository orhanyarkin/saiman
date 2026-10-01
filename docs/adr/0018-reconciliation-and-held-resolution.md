# ADR-0018: Reconciliation against Base Sepolia and HELD resolution

Status: Accepted (2026-10-01). Amends ADR-0013 (HELD transitions).

## Decision
- **Chain access:** `libs/evm-rpc`, a small JSON-RPC client over `RestClient` (`eth_chainId`, `eth_getBlockByNumber`, `eth_call`, `eth_getTransactionReceipt`, `eth_getLogs`); selectors and topics computed with web3j `crypto` Keccak (no web3j core, ADR-0008). Startup fails unless `eth_chainId == 84532`; the URL must be https on an exact-host allowlist (default and only configured endpoint: the public `https://sepolia.base.org`, rate-limited, no account or secret) or loopback for tests; no redirects; rate limit, retry with jitter and a breaker. `eth_getLogs` runs in ≤1000-block chunks and is a fallback only. An RPC failure means "skip", never a mismatch.
- **Finality:** decisions that release budget or declare a mismatch are made at the `safe` block (about a minute behind head); a receipt above `safe` is PENDING.
- **The orchestrator resolves its own HELD intents** (`HeldPaymentResolver`): once the `safe` block's timestamp is past `validBefore`, it reads `authorizationState(from, nonce)` there. `true` → HELD → SETTLED (counters move reserved → committed; tx hash from the receipt or log lookup, else null) and `payments.settled.v1` with evidence CHAIN; `false` → HELD → RELEASED (counters released) and `payments.failed.v1` FINAL `expired_unused`. `payment_intent` records `resolved_by` (FACILITATOR | CHAIN) and `resolved_at`. Note: `authorizationState` is also set by `cancelAuthorization`; we never cancel, and "used without a transaction found" stays counted as spent and is flagged `TX_UNKNOWN` by the ledger.
- **The ledger audits independently:** a scheduled and on-demand reconciliation run (single runner under an advisory lock) checks every reported transaction's receipt (status, USDC `Transfer` from payer to payTo for the amount, matching `AuthorizationUsed`), and authorizations past `validBefore` + grace; differences go to suspense with a `ledger.reconciliation-mismatch.v1` event and appear in the report.
- **No Kafka input to the spend plane:** the orchestrator consumes nothing from Kafka in M4. Redpanda has no authentication yet; a forged "unused" event must not be able to free reserved budget.
- **Ledger HTTP** (trial balance, reconciliation runs and report) uses the orchestrator's guard posture: Host allowlist, JSON + `X-Saiman-Csrf` on state changes, refusal of non-normal raw paths; local/compose only. Authentication and per-service DB roles are M6 (the human's decision).

## Consequences
+ HELD reservations stop blocking budgets once the chain answers; the M3 known gap closes.
+ Two independent paths (buyer resolver, ledger audit) must agree, which is what makes a corrupted record visible.
− Depends on a public, rate-limited testnet RPC without an SLA; CI stays hermetic (stubbed RPC), live checks are tagged `testnet`.

## Amendment (2026-10-01, M4 audits)
- HELD intents without a nonce are released locally (`resolved_by = LOCAL`), no chain read and no event.
- The ledger dead-letters only deterministic failures (malformed or conflicting facts); transient errors are retried indefinitely. A conflicting fact also writes a `CONFLICTING_FACT` mismatch row (database and report only; it is not published, because `ledger.reconciliation-mismatch.v1` requires a reconciliation run id).
- Forged-input hardening (Redpanda is unauthenticated until M6): bounded validBefore/amount/asset, unique (payer, nonce), fair due batching, receipts judged one by one (a bogus hash cannot freeze a payment), BOOKS_OPEN when the chain is final but the books are open, Transfer/AuthorizationUsed paired by log index.

## Amendment (2026-10-01): Redis and Apache Kafka (ADR-0020)
Read "Redpanda" above as Apache Kafka. Kafka is unauthenticated until M6 just as Redpanda was, so the forged-input hardening and the "no Kafka input to the spend plane" rule stay as written.
