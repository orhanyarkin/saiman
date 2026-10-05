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

## Observations log

- **2026-10-05 17:19Z, run `4feeba86` (ASELS demo question):** both upfront settlements (0.01 and 0.02 USDC) of one run were rejected by the facilitator with `invalid_exact_evm_transaction_failed` (WARN lines from `PaymentSettler`; `secondsLeft` about 60 at the time of `/settle`, nonceRefs `47c5ae46` and `8fb31a9e`). The ledger shows `sellerState=SETTLE_FAILED`, no seller tx hash and `chainState=UNKNOWN`; the buyer reservations were released. No tx hash was ever produced, which fits H2 (the facilitator did not broadcast or lost its transaction) better than H1; H3 is unlikely with 60 s left. Rate in the local ledger: 4 `SETTLE_FAILED` of 27 payments; since the M6 restart at 16:20Z 2 failed, 4 settled, 1 credited of the 7 attempts. The failures came back to back, which suggests a facilitator-side state (sender nonce or gas) rather than anything in the individual authorizations. Decision rule not yet met for a mitigation (needs a larger sample); the "never retry `/settle`" rule stands.
