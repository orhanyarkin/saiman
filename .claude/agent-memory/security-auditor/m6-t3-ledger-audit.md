---
name: m6-t3-ledger-audit
description: M6 T3 ledger half audit (2026-10-05) - no Crit/High; owner cred residual undocumented + overstated in V7/ADR-0024, app role over-granted on audit tables, 401 suppression signal
metadata:
  type: project
---
Per-task audit of 4b2b715/e58f7c9/35a3eb8 (ledger authn, seller service token, CREDIT_NOTE_UNCORROBORATED event, V7 grants). No Crit/High.

- Medium (carry-forward of [[m6-t1-t4-auth-infra-audit]]): ledger_owner pw mounted in running ledger (/run/secrets/spring.flyway.password); THREAT_MODEL has no residual; V7 comment, LedgerSchemaTests javadoc and ADR-0024 Consequences claim "only superuser can bypass". Fix: migrate one-shot or document.
- Low: V7 grants UPDATE/DELETE on insert-only audit tables (reconciliation_mismatch, reconciliation_item, credit_note_corroboration, account, inbox); default privileges repeat it for future tables.
- Low: seller 401/403 counted as outcome=unavailable -> misconfigured/rotated token silently keeps every CREDITED payment PENDING (forged notes never flagged). Want distinct outcome tag + alert.
- Low: token over plain http on compose bridge, no cap_drop NET_RAW. Info: http.url span attr carries payment key (nonce), pre-existing M4b.
- Sound (verified): Boot BindFailureAnalyzer does NOT echo the token on malformed value (probed with Boot 4.1.1: property=null); guard order proven by 400-without-WWW-Authenticate test; HEAD/OPTIONS/trailing slash -> denyAll; mismatch publish gated on ON CONFLICT insert + MANDATORY tx + advisory lock; test Flyway runs as ledger_owner so default-privileges gaps are test-visible; REVOKE ALL ON DATABASE FROM PUBLIC also removes TEMP.
**How to apply:** at M6 close check the owner-cred residual is documented or the migrate split exists; re-check least-privilege REVOKEs if V8+ adds tables.
