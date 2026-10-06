# ADR-0024: Per-service Postgres roles

Status: Accepted (2026-10-05). Amended by ADR-0027 (2026-10-06): migrations run in a per-service one-shot. Amends ADR-0001 (one instance, one schema per service) and ADR-0020 (test containers).

## Context
Every service connects as the `saiman` superuser. A bug or injection in any service can read or rewrite any schema, disable triggers (`session_replication_role`), and falsify the ledger. THREAT_MODEL lists the shared superuser role as a known gap.

## Decision
- **Two roles per schema** (`orchestrator`, `ledger`, `seller_api`, `ingest`): `<svc>_owner` (LOGIN, owns the schema, used **only by Flyway**) and `<svc>_app` (LOGIN, DML only, used by the running service). All `NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS`. The runtime role can't run DDL or disable triggers. Search path: `<svc>`; `ingest_app` also `public` (pgvector).
- **Boot wiring.** `spring.datasource.username=<svc>_app`; `spring.flyway.user=<svc>_owner` (Flyway builds its own DataSource on the same URL). Passwords come from per-service secret files via configtree (`pg_<svc>_owner_password`, `pg_<svc>_app_password`, mounted only into their service; ADR-0009). `SPRING_DATASOURCE_USERNAME=saiman` and password env vars are forbidden on apps (compose policy).
- **Grants are versioned with the schema**: one migration per owner using the `app_role` placeholder (`GRANT ... ON ALL TABLES`, `ALTER DEFAULT PRIVILEGES`, `REVOKE ALL` on `flyway_schema_history`). Ledger: `journal_entry` and `posting` are INSERT/SELECT only for the app role. Orchestrator: a trigger makes `committed_atomic` monotonic non-decreasing on `run` and `spend_day`.
- **Cluster bootstrap** is an idempotent one-shot compose service `db-init` (same pgvector image, runs as the superuser, default profile so `infra-up` and `ingest-backfill` get roles): create roles, set passwords from secrets, create schemas owned by `<svc>_owner`, **adopt pre-M6 objects** (`ALTER ... OWNER TO` for tables, sequences, views, functions, types, incl. Modulith tables and `flyway_schema_history`) so existing volumes migrate with no data loss (the ingest corpus is expensive to rebuild), `REVOKE ALL ON DATABASE FROM PUBLIC` + `GRANT CONNECT`, `CREATE EXTENSION vector WITH SCHEMA public` (stays superuser-owned).
- The superuser stays for `db-init`, `psql` and `ledger-tamper-demo` only (the demo now shows only the superuser can bypass the triggers).
- **Tests keep one database per context** (ADR-0020). `SharedContainers` creates the eight roles once per JVM; per database it creates the schema owned by the owner role. `PostgresContainerConfiguration` supplies `JdbcConnectionDetails` (app role) and Flyway connection details (owner). Transition flag `saiman.test.db.runtime-role=superuser|owner|app` (default `superuser` for modules without a service schema and `app` for the four services). The service is taken from `spring.flyway.default-schema`; tests that tamper on purpose inject `PostgresContainerConfiguration.SuperuserDatabase`.

## Consequences
+ SQL injection through a service reaches only that schema with DML; insert-only tables (ledger postings, journal entries, credit notes, findings) can't be rewritten, and the orchestrator's counters are monotonic and can't be deleted.
− **Residual closed by ADR-0027:** the `<svc>_owner` password used to be mounted in each running service; migrations now run in a per-service one-shot and the service holds only `<svc>_app`.
− The superuser password is a generated secret (`secrets/pg_superuser_password`) held by postgres and `db-init`; `db-init` rotates a pre-M6 `saiman/saiman` password once.
− More secrets and a bootstrap step; mistakes show up as permission errors (tests run as the app role to catch them).
− Same DB instance: a superuser/host compromise is out of scope.

## Alternatives
Separate migration job (accepted later, see ADR-0027); one role per service owning its tables (the app could still alter schema and disable triggers it owns); per-service databases (breaks ADR-0001, costs on RDS).
