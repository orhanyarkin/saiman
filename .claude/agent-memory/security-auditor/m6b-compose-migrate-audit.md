---
name: m6b-compose-migrate-audit
description: compose <svc>-migrate wiring audit (cecf203, 2026-10-06) — shipped compose sound; policy rules profile-gated, volumes_from leaks owner secret (live-verified), relaxed-binding env spellings
metadata:
  type: project
---
Per-task audit of cecf203 (ADR-0027 compose wiring + check-compose-policy.sh), 2026-10-06. No Crit/High; shipped compose correct.

Closed from [[m6b-db-migrate-audit]]: exit-0-without-migrating (MigrationOutcome sentinel), URL user= bypass (checkUrl + verifySession current_user/session_user/rolsuper), run-mode typo now throws.

Findings (all CI-guardrail bypasses, verified with scratch fixtures that PASS the policy):
- MEDIUM: migrate/app rules sit inside `if inscope(profiles)` and app rules need profiles == ["apps"] exactly; a migrator with no/other profile skips SAIMAN_RUN_MODE, env allowlist, restart, command, image, SPRING_FLYWAY_URL rules. Only name-keyed secrets allowlist survives.
- MEDIUM: `volumes_from: [<svc>-migrate]` copies the compose secret bind mount; live busybox test read /run/secrets/spring.flyway.password in the dependent. No policy rule.
- LOW: named volume with driver_opts bind device (only .type=="bind" checked); privileged/cap_add/devices/pid/ipc/network_mode unchecked; BPL_DEBUG_ENABLED/BPL_JMX_ENABLED (Paketo JDWP/JMX) unchecked on apps.
- LOW: Boot 4.1.1 relaxed binding verified: SPRING__CONFIG_IMPORT, SPRING_CONFIG__IMPORT, SPRING_FLYWAY__URL, SPRING_AUTOCONFIGURE_EXCLUDE_0(_) all bind; apps pass SPRING__CONFIG_IMPORT / EXCLUDE_0 (migrators blocked by exact env allowlist; leading "_" blocked by UPPER_SNAKE rule). SAIMAN_SECRETS_DIR redirects configtree, unchecked.
- Verified live: `up -d` exits 1 and leaves app "created" when migrator exits 1; changed image behind same tag recreates and re-runs migrator; unchanged re-up re-runs it (idempotent).
**Why:** ADR-0027/THREAT_MODEL:320 claim the policy "pins" the shape; it does so only for services already in profile apps.
**How to apply:** on fix review check rules keyed on service name not profile, volumes_from forbidden, norm collapses `_+` and strips `_N`; at ECS wiring (A3a) re-check the same invariants in Terraform with a test.
