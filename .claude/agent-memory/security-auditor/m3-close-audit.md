---
name: m3-close-audit
description: M3 milestone-end audit 2026-10-01 (m3-orchestrator @ 0287050) - honest-path HELD triggers between orchestrator and seller (35s buyer read timeout, seller 30/h per-payer cap, shared router:cost day key, clock skew), T4 Lows closed
metadata:
  type: project
---
Read-only M3 close audit (2026-10-01, branch m3-orchestrator @ 0287050). No probes; traced by reading.

No Critical/High. Findings are cross-component config/contract mismatches that turn honest traffic into HELD reservations (released only by M4 reconciliation):
- Medium: orchestrator SellerProperties.readTimeout 35s < seller worst case (handler deadline 25s + facilitator connect 3s + read 12s, headers written only after /settle by the buffering filter) -> buyer IOException after a real settle -> HELD, data lost, money moved. Javadoc claim ("above the handler deadline") ignores the settle margin. Fix >= 60s (MAX_VALIDITY_SECONDS bounds the latest settled 200) + startup cross-check.
- Medium: seller max-runs-per-payer-per-hour=30 (UnsettledRunGuard Lua; counts cache hits and 404s inside withRunSlot) vs one-wallet orchestrator (2 concurrent x 4 paid). 31st paid call/hour -> seller 429 on the paid retry (claim released, not charged) -> AmbiguousPaymentException -> HELD + counts on the seller circuit breaker. Design aligned only in-flight=2 (F-D). Fix: pre-sign hourly paid-call limiter in the orchestrator (new DenyReason) + startup consistency check.
- Low/Med: ValkeyCostGuard KEY_PREFIX "router:cost:" + same SPRING_DATA_REDIS_URL -> one $0.70/day across seller-api, ingest, orchestrator; unauth local POST /runs can drain it (~5 worst-case runs) -> seller 503 for all payers (and HELD for the orchestrator's own signed calls), ingest query embedding refused.
- Low: clock skew: buyer signs validBefore=now+60, seller needs window in [45, 65] -> buyer ahead >5s (window_too_large) or behind >~15s (window_too_short) -> 402 after signing -> HELD every call. Same-host compose is fine; multi-host needs NTP note.
- Low: X402PaymentFailedEvent.errorReason still raw facilitator text (6827ab0 bounded only the log); bound before M4 relays events.
- Info: doc drift after 8bd2f53 (PaidResourceClientConfiguration javadoc NOT_CONFIGURED path, secrets-check.sh wording, garbled ensure-secret-files comment); restart: on-failure loop with empty key.
- Verified closed: T4 Lows (link scrub on RunStarted/ToolCallRequested question via UntrustedText.question, citation titles via forModel; invisibles dropped); T3 guard. Testnet-only and integer money clean in the diff (mainnet ids only in negative tests).
**How to apply:** at M4 re-check that authorizationState reconciliation releases HELD from seller 429/503/window refusals, that the read timeout and hourly limiter fixes landed, and that the failed-event reason is bounded before the outbox.
