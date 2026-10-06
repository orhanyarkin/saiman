---
name: x402-facilitator-telemetry-notes
description: M6-T7 facilitator observation design plus micrometer test/Prometheus quirks and where the authoritative x402 exact-EVM error code list lives
metadata:
  type: project
---

- Authoritative facilitator error codes: `go/mechanisms/evm/exact/facilitator/errors.go` in coinbase/x402 (raw.githubusercontent.com). Receipt-wait failure is `invalid_exact_evm_transaction_failed`; there is a separate `invalid_exact_evm_failed_to_get_receipt`.
- `TestObservationRegistry.getContexts()` and its context type are NOT public; to inspect stopped observations from another package register an `ObservationHandler.onStop` on `observationConfig()` (parent is `context.getParentObservation()`, type `ObservationView`; use `var`).
- Prometheus rejects one meter name with differing tag KEY sets, so adding a `reason` tag to `x402.payments{outcome=failed}` required `reason=none` on the settled series too.
- `FacilitatorException` now carries `Failure` (TRANSPORT/CIRCUIT_OPEN/MALFORMED/REJECTED) + `httpStatus`; `FacilitatorTelemetry`/`PaymentSettler` classify from that. Settle transport errors reach `x402.payments` as `reason=none/other` (event errorReason null or `unrecognised`); only the `x402.facilitator.settle` observation distinguishes them.
- The `authorization` offer's `extra` has no `paymentFlow` key; test payloads for default-flow handlers must omit it or verify fails with 402.
- Bash tool in this worktree refuses `cd ... && cat > f <<EOF` style compounds; use Write/Edit tools. Spotless reformatting can make later scripted string replaces miss: re-read before patching.
