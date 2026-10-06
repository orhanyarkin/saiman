---
name: m6-t2-orchestrator-auth-audit
description: M6 T2 orchestrator auth/DB-roles/daily-cap audit (2026-10-05) - no Crit/High/Med; app DELETE bypasses monotonic trigger, decided_by digest prefix to READER, auth-off = Boot default chain
metadata:
  type: project
---
Per-task audit of 4988b02/dc97f53/524729d on m6-hardening (orchestrator ApiSecurityConfiguration, MeController, decided_by, V9/V10, daily-cap 503). No Crit/High/Medium.

- Low: V10 grants DELETE on all tables (+ default privileges) to orchestrator_app; trigger is BEFORE UPDATE only. `DELETE FROM spend_day WHERE day=today` then guard's INSERT..DO NOTHING recreates the row at 0 -> USDC day cap reset. No code path deletes run/spend_day (only Modulith deletes event_publication). Fix: revoke DELETE except event_publication, or BEFORE DELETE trigger.
- Low: decided_by = `operator:<8 hex of unsalted SHA-256(token)>` visible to every READER via GET /approvals -> 32-bit offline filter on a hand-picked token (regex only checks charset/length). make auth-tokens generates random ones.
- Info (verified in spring-boot-security-4.1.1 bytecode): auth disabled -> no app chain -> ManagementWebSecurityAutoConfiguration default chain (health permitAll, rest authenticated, formLogin+httpBasic) and no in-memory user (OpaqueTokenIntrospector class present) => API locked closed, not "NO authentication" as the lib warns. decide() would NPE on null Authentication if a permit chain were ever added.
- Info: shared `router:cost:<day>` key -> seller/ingest spend now flips orchestrator to 503 replay for the day; Retry-After = midnight even on Redis-unreadable branch; replayAvailable hardcoded; llm-budget > cap not startup-checked.
- Sound: route table default-deny (HEAD/OPTIONS/PUT/DELETE/trailing-slash -> denyAll; ; % // \ dot segments die in guard at order -110 before security); ASYNC/ERROR permitAll ok; only moveRun/moveDay touch committed_atomic, always +>=0 (release/HELD/recovery safe); trigger fn SECURITY INVOKER, no schema refs, owner-owned; app lacks TRIGGER/TRUNCATE; Modulith needs only DML (V6 matches 2.x columns); whole suite runs as app role.
**How to apply:** at M6 close check DELETE revoke, THREAT_MODEL lines 212/337 updated, and whether per-service router day keys landed.
