# Facilitator settle failures: investigation plan

Status: open investigation (M6-T7). Owner: payments. Rule until data says otherwise: **never retry `/settle`** (ADR-0008, ADR-0021).

## Symptom

The public testnet facilitator sometimes answers `POST /settle` with `success: false, errorReason: "invalid_exact_evm_transaction_failed"`. It was seen in the M4 T7 live run and again in the M5 entry of `docs/PROGRESS.md`. In the M4 T7 case the ledger resolver later found the authorization nonce **unused** on chain and released it. So the facilitator said "failed" and the chain agreed nothing moved, but we never learned *why*.

We have no failure rate yet, and the facilitator's `errorMessage` is deliberately never logged (untrusted text, THREAT_MODEL). This document says what we now collect and how to decide.

## Hypotheses

| id | Hypothesis | Chain verdict for the authorization | Tx hash in the answer |
|----|------------|-------------------------------------|-----------------------|
| H1 | The facilitator broadcast the transfer but waiting for the receipt failed or timed out (its code maps a failed receipt wait to this reason). | May be **USED** (transfer mined after the facilitator gave up). | Usually none in the failure answer. |
| H2 | The facilitator's sender account hit nonce contention or gas pricing trouble: its transaction was replaced or dropped before inclusion. | **UNUSED** (matches M4 T7, where the resolver released it). | None. |
| H3 | The transaction was included, but after `validBefore`, so the token contract reverted it. | **UNUSED** (the revert burns no authorization) with a receipt whose block time is later than `validBefore`. | Possibly. |
| H4 | The payer lacked funds, or the authorization was settled twice. | Excluded: payer funds are checked by `/verify` and a double settle is prevented by our nonce claim plus the token's own nonce. | n/a |

H1 is the dangerous one: money may have moved while we answered 402. Our ledger resolver and reconciliation already cover it (a USED authorization turns the attempt into a settled payment), so the question is frequency, not safety.

## What the starter now emits

Micrometer observations (span plus timer), one per facilitator call, from `RequiresPaymentInterceptor` (verify, and settle for the upfront flow) and `PaymentSettler` (settle, both flows):

- `x402.facilitator.settle` and `x402.facilitator.verify`
- low-cardinality key `outcome`: `success`, `rejected`, `ambiguous`, `transport_error`, `circuit_open`, `malformed`
- low-cardinality key `reason`: closed set `FacilitatorReason` (known x402 exact-EVM codes, `settlement_pending`, `insufficient_funds`, `none`, `other`). A code in the safe shape `[a-z0-9_]{1,64}` that is not in the set is tagged `other`; the WARN keeps the raw code.
- `x402.payments{outcome="failed"}` has the same `reason` tag (every `x402.payments` series has a `reason` tag; `none` when settled).

Meaning of the outcomes: `rejected` is a definite "no" from the facilitator (`success:false`, or an HTTP 4xx); `ambiguous` is `settlement_pending`; `malformed` is a 2xx answer that is undecodable, decodes to JSON `null` or a non-object, exceeds the 64 KiB bound, or says `success:true` without a well-formed transaction hash (the status is the real 2xx); `transport_error` is a timeout, network error or 5xx; `circuit_open` means nothing was sent.

One WARN per settle failure, logger `io.github.orhanyarkin.x402.server.PaymentSettler`:

```
x402 settlement failed: reason=<code> attemptId=<uuid> payer=<0x..> nonceRef=<8 hex> validAfter=<s> validBefore=<s>
  secondsLeft=<n> verifyToSettleGapMs=<n> settleDurationMs=<n> facilitatorStatus=<http|0> txHashPresent=<bool> txHash=<0x..|-> outcome=<tag value>
```

- `attemptId` equals the `eventId` of the published `X402PaymentFailedEvent`.
- `nonceRef` is the first 8 hex of `sha256(lowercase(from) + lowercase(nonce))`. To find the authorization, compute the same prefix over the (public, on-chain) `AuthorizationUsed` events or the ledger's rows; the prefix cannot be turned back into the nonce.
- `secondsLeft` is `validBefore` minus the instant the `/settle` call started, on our clock (not the time of the log line) (negative means already expired). `verifyToSettleGapMs` is the time between a successful `/verify` and the `/settle` call (large in the default flow, since it includes the handler).
- `facilitatorStatus` is `200` for any decoded answer, the real status for a 4xx/5xx, `0` when no response arrived.
- Never logged or tagged: the signature, the payload, the facilitator's `errorMessage`, the raw nonce.

### What each outcome means for the money and the claim

- Settle `transport_error` (for example a read timeout) or `malformed` is **ambiguous**: the facilitator may have broadcast before the answer was lost or garbled, so money may have moved. The nonce claim is kept, the client gets a 402, and the chain resolver decides (USED or UNUSED). `facilitatorStatus=0` only means no response arrived; it does not mean nothing was sent.
- Settle `circuit_open` means nothing was sent (the local breaker refused), but the claim is still kept, because the upfront flow treats every settle attempt the same way.
- Verify `transport_error`, `circuit_open` or `malformed` (including a JSON `null` body): the facilitator never ruled on the authorization and this server will not settle it under that claim, so the **nonce claim is released** and the client may retry the same authorization. A verify `rejected` (a definite "invalid") keeps the claim.

## Data to collect per failure

1. The WARN line above (all fields).
2. The later chain verdict for the authorization: the ledger resolver's decision (USED / UNUSED) and the reconciliation result, matched by payer plus `nonceRef` (or `attemptId` through `payments.*.v1` events).
3. For a USED or included case: the Basescan receipt for the transaction, its block timestamp compared with `validBefore` (H3), and its position relative to the facilitator's other transactions (H2).

Reading the data:

- UNUSED, no tx hash, small `settleDurationMs` leaning on gas/nonce errors: H2.
- UNUSED with `secondsLeft` small or negative: H3 candidate; compare with the receipt time.
- USED later, `settleDurationMs` near the facilitator's wait timeout: H1.

## Metric to watch

`x402.facilitator.settle{outcome="rejected",reason="invalid_exact_evm_transaction_failed"}`, as a share of all `x402.facilitator.settle` calls. (Micrometer renders the observation `x402.facilitator.settle` as the timer `x402_facilitator_settle_seconds_*` in Prometheus. The exact name depends on the registry; check `/actuator/prometheus` once.) Over a window, for example 7 days:

```
sum(increase(x402_facilitator_settle_seconds_count{outcome="rejected",reason="invalid_exact_evm_transaction_failed"}[7d]))
/
sum(increase(x402_facilitator_settle_seconds_count[7d]))
```

The same split by `outcome` shows how much of the failure rate is transport or circuit trouble rather than facilitator rejections.

## Decision rule

Decide on a mitigation after about 20 settles or 3 failures, whichever comes first.

- If failures are mostly H2 (UNUSED, no tx hash): consider a *new-authorization* retry at the client (a fresh nonce, never a re-send of the same `/settle`), or a different facilitator.
- If H1 shows up (USED after a failure): keep the resolver as the source of truth, and consider treating `settlement_pending`-style answers as accepted-pending rather than failed.
- If H3: raise `minWindowSeconds` on upfront handlers (the settle margin itself is derived from the facilitator connect and read timeouts plus 5 s).
- If the rate is negligible, document it and stop.

Our rule stays **never retry `/settle`** until this data says otherwise.

## Upstream references

- The x402 exact-EVM scheme spec lets a facilitator return `settlement_pending` together with a transaction hash when it has broadcast but not yet confirmed. Our `settlement_pending` answers are classified `ambiguous`.
- The upstream Go facilitator (`go/mechanisms/evm/exact/facilitator`) maps a failed receipt wait to `invalid_exact_evm_transaction_failed` (and has the separate `invalid_exact_evm_failed_to_get_receipt`), which is why H1 is plausible: the code does not distinguish "reverted" from "could not confirm".
- x402 issue #2471: facilitator sender-nonce contention on Base Sepolia (supports H2).

## H5: is the signed `validAfter` back-dated, and does it matter? (analysis 2026-10-05, no code changed)

**What the client signs** (`X402PaymentInterceptor.buildAuthorization`): `validAfter = now - 600 s` (`CLOCK_SKEW_SECONDS`), `validBefore = now + min(maxTimeoutSeconds, cap)` = `now + 60 s` for both RAG endpoints. So `validAfter` is back-dated by 10 minutes on **every** payment, and the window is 660 s long; this is also what the x402 reference clients do.

**Data** (ledger `payment` joined with `seller_api.settlement`; "authorized" = the ledger row's `created_at`, which is the authorization event, a proxy for the signing time within about a second; the signing instant itself is not stored, only `valid_before`; `validAfter` is not stored in any table and is known from the code constant and, for the two newest failures, from the WARN lines):

| | failed (4) | succeeded (14) |
|---|---|---|
| `validBefore - authorized_at` | 59, 59, 59, 60 s | 58-60 s |
| `validAfter` (code constant) | `signed - 600` | `signed - 600` |
| authorization to settle row | 0.7, 0.7, 1.0, 11.0 s | -0.3 to 2.7 s (13.9 s on the same 2026-10-01 burst as the 11.0 s failure) |
| facilitator HTTP status / error | 200 with `success=false`, `errorReason=invalid_exact_evm_transaction_failed` (all 4) | 200 with a transaction hash |
| settle call duration (WARN lines, the two newest only) | 465 ms and 404 ms, `verifyToSettleGapMs=0`, no tx hash | not logged on success |
| chain state afterwards | UNUSED, UNUSED, UNKNOWN, UNKNOWN (never USED) | USED |
| amount | 0.02, 0.02, 0.01, 0.02 USDC | 0.01 and 0.02 both succeed (4 of the 0.02 ones too) |
| position in the run | 3 of 4 are the second payment of a run (15-22 s after a successful first one); 1 is the first payment of a run | second payments also succeed (6, 14, 16, 45 s after the first) |

**Reading**
- `validAfter` is the same constant for the failed and the successful payments, so it cannot be what separates them. Hypothesis H5 ("the back-dated `validAfter` makes the facilitator's transaction fail") is **not supported**: if it were the cause, the 14 successes would not exist. It is not disproved as a contributing factor in some facilitator-side check we cannot see, but nothing in the data points at it.
- The two logged failures took 404-465 ms. A transaction that was broadcast and then waited for a receipt on Base Sepolia (about 2 s blocks) would take seconds. A fast rejection fits a failure before or at submission (simulation revert, sender nonce too low or replacement underpriced, gas) and fits H2, not H1; consistent with chain state never being USED. The facilitator's `errorMessage` would settle it, but T7 deliberately never logs it.
- The position pattern (3 of 4 right after a successful first payment from the same payer, 15-22 s later) fits the sender-nonce contention reported upstream (x402 issue #2471) but is not exclusive: 4 second payments succeeded.
- Timing note: failures cluster in bursts (the two newest were 5 s apart; 2026-10-01 and 13:05 are single failures inside an otherwise fine burst), which suggests facilitator-side state rather than anything specific to an authorization.

**Is a back-dated `validAfter` harmless for security?** Yes, and "now" would be slightly worse.
- `validAfter` is a lower bound for *when the authorization may be executed*. The signature does not exist before the signing instant, so back-dating cannot make a signature usable earlier than it was created; the exposure window of a leaked or intercepted authorization is bounded by `validBefore` (60 s), by the single-use random nonce (EIP-3009 `authorizationState`), by the fixed payee `to` and amount `value`, and by our own claim and replay checks (nonce store, `validBefore` margin, ADR-0008/ADR-0015). None of these depend on `validAfter`.
- Back-dating protects against skew: `transferWithAuthorization` requires `block.timestamp > validAfter`; if the signer's clock is ahead of the chain (or the facilitator's RPC node lags), `validAfter = now` would revert with "authorization is not yet valid". 10 minutes is the reference-client allowance.
- The server side accepts any `validAfter` in the past and rejects `now < validAfter` (`RequiresPaymentInterceptor`); it never relied on a tight lower bound.
- One cosmetic cost: the signed values are public on chain once used (`validAfter` 10 minutes before the transfer); no privacy or integrity impact.
Conclusion: do not change it on security grounds; changing it to `now` or `now - 30` is also safe but is not expected to change the failure rate.

**If an experiment is wanted (not done, needs the human's go):** make `CLOCK_SKEW_SECONDS` configurable (`x402.client.clock-skew-seconds`, default 600, bounds 0-600), run about 20 settles with 30 and compare the failure rate with the current 4 of 18; log the facilitator `errorMessage` only as a truncated (<= 120 chars), charset-restricted (`[A-Za-z0-9 _:.,()-]`), clearly marked untrusted field in the WARN line, with a marker test that a hostile message cannot inject newlines or secrets. "Never retry `/settle`" stays regardless: a rejected settlement with an unknown cause may still have been broadcast.

## Observations log

- **2026-10-05 17:19Z, run `4feeba86` (ASELS demo question):** both upfront settlements (0.01 and 0.02 USDC) of one run were rejected by the facilitator with `invalid_exact_evm_transaction_failed` (WARN lines from `PaymentSettler`; `secondsLeft` about 60 at the time of `/settle`, nonceRefs `47c5ae46` and `8fb31a9e`). The ledger shows `sellerState=SETTLE_FAILED`, no seller tx hash and `chainState=UNKNOWN`; the buyer reservations were released. No tx hash was ever produced, which fits H2 (the facilitator did not broadcast or lost its transaction) better than H1; H3 is unlikely with 60 s left. Rate in the local ledger: 4 `SETTLE_FAILED` of the 18 payments that have a seller settlement row (9 older rows predate the seller book); since the M6 restart at 16:20Z 2 failed, 4 settled, 1 credited of the 7 attempts. The failures came back to back, which suggests a facilitator-side state (sender nonce or gas) rather than anything in the individual authorizations. Decision rule not yet met for a mitigation (needs a larger sample); the "never retry `/settle`" rule stands.
