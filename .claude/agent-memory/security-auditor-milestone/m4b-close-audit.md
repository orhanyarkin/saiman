---
name: m4b-close-audit
description: M4b (x402 upfront flow + credit notes) milestone-end audit 2026-10-01 on m4b-settle-first @ f1f5b4a - no Crit/High; forged credit note invisible to recon, deadline cut dropped after settle, no pre-settle per-payer limit
metadata:
  type: project
---
Read-only audit 2026-10-01, diff 4fdcb23..f1f5b4a. By reading + live stack inspection (V4 constraint live, credit-note topic + DLT declared, live 402 offer carries extra.paymentFlow=upfront). No probes.

No Critical/High. Findings:
- Medium: forged CreditNoteIssued (producer "seller-api", key/validBefore/tx from a real tx's public calldata) flips a real SETTLED sale to CREDITED; CREDIT_NOTE touches no wallet so reconciliation says MATCHED. ADR-0021/THREAT_MODEL claim "same class as forged settled ... reconciliation" is wrong. Fix: corroborate ledger CREDIT_NOTE against seller-api credit_note via an internal API; correct docs.
- Low: RequestDeadlines drops the validBefore cut once settled; verify retries (3x15 s) + settle + sync recorders + 25 s handler can exceed the orchestrator's 65 s read timeout -> buyer HELD/spent, seller logs 2xx served, no credit note. No remaining-window re-check before the upfront /settle.
- Low: per-payer limits (UnsettledRunGuard in GroundedGenerator) run after settle; nothing per-payer before /verify. 400/404/429 are paid + credited. Delta vs buying cached summaries is small, so Low not Medium.
- Low: crash between settle and answer -> sale, no credit note, recon MATCHED. Shared verify/settle breaker now in front of every upfront settle. 3-fact order dependence (Failed, Credit, Settled) only with forged input.
Verified sound: claim never released once settleAttempted (afterDispatch branches first), failed settle -> 402 + handler never runs, exception path not rethrown + headers discarded + class-name-only log, sendError/sendRedirect captured, cross-flow accepted mismatch fail-closed by record equality (no test), client rejects escrow/unknown before signing + prefers authorization, recorder idempotent ON CONFLICT + deterministic event id, 2 s tx timeout.
**How to apply:** at M5/M6 check the credit-note corroboration landed, the deadline cut for settled requests, and a pre-verify per-payer limit; broker auth in M6 closes the forged-credit-note class.
