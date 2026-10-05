---
name: m6-t7-t5a-audit
description: M6 T7 facilitator telemetry + T5a web token/SSE/replay audit (2026-10-05) - settle null-body regression escapes PaymentSettler, verify null now releases claim, SSE buffer unbounded
metadata:
  type: project
---

Audit of m6-hardening commits 8d171fe, 6ecb11c (T7, libs/x402-spring-boot-starter) and 572782e, b3c9abe, 27aa180, b4f1f37 (T5a, web/). Date 2026-10-05.

Findings (open at audit time):
- MEDIUM (regression): facilitator /settle answering 200 with body `null` -> X402Codec.readJson returns null (verified empirically, Jackson 3.1.5) -> PaymentSettler line ~103 `FacilitatorTelemetry.ofSettle(settlement)` NPE sits BETWEEN the two try blocks -> escapes settle(). Pre-T7 the first deref was inside the inner try -> 402 + X402PaymentFailedEvent. Now: 500 dispatch_error, claim kept (money-safe), but no WARN, no failed event -> seller-api SettlementRecorder writes no SETTLE_FAILED row (seller book blind; buyer book/chain still net), settle observation never stopped. Fix: decode() null -> Failure.MALFORMED; move classification inside the try.
- INFO behaviour change: /verify null body now released claim + 402 facilitator_unavailable tagged transport_error (pre-T7: 500, claim held).
- LOW: oversized facilitator body -> transport_error/status 0 though a response arrived; runbook says 0 = no response and never calls settle transport_error ambiguous.
- LOW: FacilitatorTelemetry.start() unfenced (finish is fenced); throwing handler after nonce claim -> 500, claim held to TTL.
- LOW (web): SseParser buffer/data unbounded, O(n^2) rescan; retry:0 -> hot reconnect loop.
- INFO (web): sample capture ships internal hostnames http://seller-api:8081 in public/demo/capture.json (bundled in every dist); normalizeCapture runEvents plain {} __proto__.

Sound: nonceRef (32-bit sha256 prefix, nonce public anyway), log fields bounded (from == recovered signer, parseLong'd validity, regex reason/txHash), closed tag enums, settle never retried, token memory/sessionStorage only + ESLint localStorage ban, OTel fetch instr. no headers, replay is build-time URL + CSP connect-src 'self', apiPost blocked in replay, REPLAY_ME reader, no dangerouslySetInnerHTML, fixture server loopback/test-only.

Related: [[threat-model-baseline]], [[m5-dashboard-fix-reaudit]], [[m4b-fix-reaudit]].
