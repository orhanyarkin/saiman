-- V3: runtime grants (ADR-0024). Flyway runs as the schema owner (ingest_owner); the running service
-- connects as ${app_role} (DML only: no DDL, no TRUNCATE, no access to Flyway's history table).
-- The role name comes from the Flyway placeholder app_role (spring.flyway.placeholders.app_role).
GRANT USAGE ON SCHEMA ingest TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA ingest TO ${app_role};
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA ingest TO ${app_role};

-- Objects created by later migrations (run by the same owner) get the same grants automatically.
ALTER DEFAULT PRIVILEGES IN SCHEMA ingest GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${app_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA ingest GRANT USAGE, SELECT ON SEQUENCES TO ${app_role};

-- Flyway's own bookkeeping is the owner's business only.
REVOKE ALL ON ingest.flyway_schema_history FROM ${app_role};
