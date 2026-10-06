---
name: m6b-db-migrate-audit
description: libs/db-migrate (ADR-0027) audit 2026-10-06 — exit 0 without migrating (lazy-init/autoconfig exclude, verified), pgjdbc URL user= beats spring.flyway.user, ADR-0024 residual still open until compose flips
metadata:
  type: project
---

Per-task audit of libs/db-migrate (commits c75c7c9, c6a2d49, branch m6b-deploy-migrate), 2026-10-06.

Findings:
- MEDIUM: DbMigrate.run returns SpringApplication.exit(context)=0 even if the strategy never ran. Verified by probe against an unreachable DB: `spring.main.lazy-initialization=true` -> exit 0; `spring.autoconfigure.exclude=...FlywayAutoConfiguration` -> exit 0 (ImportAutoConfigurationImportSelector honours the property). No LazyInitializationExcludeFilter for FlywayMigrationInitializer in Boot 4.1.1. Fix: sentinel set by loggingStrategy, return 1 otherwise.
- LOW: pgjdbc 42.7.13 URL params override Properties (verified with Driver.parseURL): `?user=saiman&password=` in spring.flyway.url bypasses the *_owner preflight; log line prints the property not current_user. Compose pins SPRING_DATASOURCE_URL but not SPRING_FLYWAY_URL / SPRING_APPLICATION_JSON / JAVA_TOOL_OPTIONS; ECS has no policy. Fix: beforeMigrate check current_user/session_user/rolsuper/current_schema.
- LOW: run-mode is migrate-only-if-flagged; a migrate container that loses SAIMAN_RUN_MODE (or " migrate" with whitespace, verified false) boots the full server with the owner secret. Unknown values silently mean server.
- INFO: default-schema check skipped when blank; schemas list unchecked. Full service classpath spring.factories hooks + configtree still run in migrate mode.
- INFO: ADR-0024 says residual "closed by ADR-0027" but compose still mounts owner pw into servers and check-compose-policy.sh:126-129 allows it.

Secret leakage: clean. Flyway 12.4 redacts JDBC URLs in exceptions; Boot "Application run failed" stack trace carries only usernames; preflight messages name properties.

**Why:** ADR-0027 closes the ADR-0024 owner-credential residual only once service mains + compose/ECS wiring land.
**How to apply:** on the wiring task re-check: owner pw only on <svc>-migrate, SPRING_FLYWAY_ENABLED=false on servers, SAIMAN_RUN_MODE only on migrate services, System.exit(DbMigrate.run(args)) in each main, and whether the sentinel/current_user fixes landed. Related: [[m6-t3-ledger-audit]], [[m6-t1-t4-auth-infra-audit]].
