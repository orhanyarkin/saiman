---
name: m5-t1-t2-dashboard-reads-audit
description: M5 T1/T2 dashboard read surface audit (2026-10-05, m5-dashboard 786a503..28aca31) - type-mismatch echo in ledger 400s, revenue not chain-qualified, no orchestrator indexes, cursor 500s
metadata:
  type: project
---
Per-task audit of the M5 GET surface (orchestrator runs/payments/approvals/spend, ledger payments/detail/revenue/recon history, typed POST). No Critical/High.

- CONFIRMED live (old images): Spring type-mismatch ProblemDetail echoes input: `{"detail":"Failed to convert 'id' with value: '<input>'","instance":"<path>"}` (spring.mvc.problemdetails.enabled=true in all services). Ledger's new endpoints bind runId/book/limit/paymentId via conversion -> query string carries arbitrary decoded text into `detail`; SPA renders detail as-is. Orchestrator new params read as String (safe) but @PathVariable UUID endpoints echo too (path charset limited by the guard).
- Revenue: only SALE/CREDIT_NOTE post to revenue:data / revenue:credit-notes / liability:customer-credits; ADJUSTMENT moves seller wallet <-> platform suspense only. A forged Kafka `settled` inflates gross/net forever; reconciliation flags it but the report has no chain qualifier. One-sided FILTER sums ignore a future REVERSAL.
- Payer address leaks in ledger drill-down via accountCode `buyer:<payer>:...`; ledger marker test checks nonce/key only.
- Orchestrator has no index for runs keyset (run.created_at,id), approvals by status, or spend byTool (coalesce predicate); ledger V6 indexes are right. Revenue unbounded rows (one per payTo, forgeable).
- Forged cursors with out-of-range instants -> Postgres "timestamp out of range" -> 500 (no injection; all SQL parameterised; ledger WHERE built from constant fragments).
- Verified OK: guards unchanged and cover every path (nginx `..` confusion still 400 at the backend), springdoc off at runtime in both + tests, no nonce/key/signature columns selected, descriptions are code constants, 409/429/503 recon behaviour unchanged, Money non-negative/no upper bound -> revenue saturation at Long.MAX shows as unsafe integer (SPA refuses).
**How to apply:** at M5 close re-check the TypeMismatch advice + detail assertions, the revenue verified/unverified split, the orchestrator index migration; at M6 authn closes enumeration.
