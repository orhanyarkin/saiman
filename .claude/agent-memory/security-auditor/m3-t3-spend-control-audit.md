---
name: m3-t3-spend-control-audit
description: M3 T3 spend-control audit (2026-09-30, c99a725..45d7bca): /api guard bypass via ;/%61 CONFIRMED on real Tomcat, SellerEndpoint ".." escape, trigger/role gaps, what was verified OK
metadata:
  type: project
---
M3 T3 audit (2026-09-30, orchestrator budget/payment/approval + V2__runs.sql). Probes in scratchpad probe/ (boot-jar libs + build/classes, real Tomcat on 18977).

- CONFIRMED High: ApiRequestGuardFilter.shouldNotFilter uses raw getRequestURI prefix "/api/"; `/api;x=1/v1/runs/{r}/approvals/{a}` and `/%61pi/...` skip the filter (no Host check, no X-Saiman-Csrf) yet PathPattern matches (decoded, ;-params stripped) -> 200. GET `/api;/v1/runs` too. DNS-rebinding page can read ids and approve / start runs. Same bug in ingest InternalRequestGuardFilter (/internal). Fix: guard all paths except exact actuator health, or match on normalised servlet path; reject ';' and '%' in raw path.
- CONFIRMED Low: SellerEndpoint/PaymentIntentService.resolve: value ".." -> /v1/disclosures/../summary (normalises to /v1/summary); "" -> "//". Same host only. Javadoc claim wrong; validate vars (^[A-Z0-9]{3,6}$) in create().
- Low: V2 deny_reason CHECK lacks APPROVAL_MISMATCH/OFFER_NOT_PAYABLE (added to shared enum in 45d7bca, documented in events doc, code still writes UNKNOWN_INTENT/INVALID_ARGS). Writing them later -> constraint violation (fail closed, misreported).
- Low: trigger covers UPDATE of budget only; compose uses one superuser `saiman` for all services -> DISABLE TRIGGER/session_replication_role/DELETE+INSERT possible; committed_atomic can be lowered. No dynamic SQL found. M6: per-service non-owner roles, monotonic committed trigger, DB CHECK on budget upper bound.
- Low: amount "0" passes interceptor + guard until markReserved CHECK -> DataIntegrityViolation (fail closed, UNAVAILABLE). Guard does not re-check network/asset (interceptor does). Starter records SpendReservation/PaymentIntent default toString include idempotency key.
- Verified OK: reserve before sign; signed() before send with amount+payTo match; second 402/3xx/5xx after send -> HELD; DONT_FOLLOW + real-socket tests; key 128-bit random, reserve only PENDING/APPROVED; lock order intent->run->day, approval->intent (guard inserts approval only); conditional updates + run CHECK; reserved_day for commit/release; addExact; approval decide FOR UPDATE + expires_at under lock, waiter expire under lock; approval never touches counters; decide requires run_id match (IDOR ok, but no authn until M6).
- Default threshold 20000 == max-amount-per-request 20000 -> approvals dead in default config: assurance/usability, not safety.
**How to apply:** at M3 close re-check the guard filter fix (and ingest), POST /runs budget path, startup recovery of RESERVED/SIGNED, and that T5 validates ticker before create().
