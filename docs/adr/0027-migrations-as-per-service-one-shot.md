# ADR-0027: Migrations run as a per-service one-shot

Status: Accepted (2026-10-06, human decision). Amends ADR-0024, which listed a separate migration job as a rejected alternative.

## Context
ADR-0024 left one residual: Flyway ran inside each service at startup, so the `<svc>_owner` password was mounted in the running service. Code execution in a service could still run DDL or disable the triggers that protect the ledger.

## Decision
- New library `libs/db-migrate` (`DbMigrate`): a minimal `@SpringBootConfiguration` that imports only `FlywayAutoConfiguration`, runs `migrate` as the owner role and exits with 0 on success. No web server, Kafka, Redis, Modulith or API security.
- Each service's `main` calls `DbMigrate.run(args)` and exits when `SAIMAN_RUN_MODE=migrate` (or `--saiman.run-mode=migrate`); otherwise it starts normally. One image, two entry points.
- Migrator: owner user and password only (no app password, no provider or wallet keys). Server: `<svc>_app` only, `SPRING_FLYWAY_ENABLED=false`, no owner secret.
- Compose: `<svc>-migrate` one-shot (`restart: "no"`); the service depends on it with `service_completed_successfully`. ECS: non-essential container, `dependsOn: SUCCESS`. The compose policy script enforces the secret split.
- The one-shot logs one line (schema, user, from/to version, applied count, duration), never a password. It is a deploy step, so it reports an exit code instead of OTel spans.
- Applied migrations stay immutable; no new `V*` is needed. `flyway_schema_history` stays revoked from the app role, so the runtime never runs `validate`.

## Consequences
+ A compromised service can no longer run DDL or disable its triggers.
− Startup ordering depends on the orchestrator (compose, ECS) instead of the service migrating itself.
− `make ingest-backfill` still runs Flyway as owner through `bootRun`; this is a dev-only path.

## Alternatives
Flyway CLI image (a second Flyway version to keep in step with the Boot BOM); a `migrate` profile on the full context (would start Kafka, Redis and schedulers).
