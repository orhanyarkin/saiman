-- V7: the runtime role's privileges (ADR-0024). Flyway runs as ledger_owner, which owns the schema and every object in
-- it; the service runs as ${app_role} (default ledger_app, spring.flyway.placeholders.app_role): DML only, no DDL, no
-- ownership, so it can neither ALTER/DISABLE a trigger nor SET session_replication_role.

GRANT USAGE ON SCHEMA ${flyway:defaultSchema} TO ${app_role};

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA ${flyway:defaultSchema} TO ${app_role};
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA ${flyway:defaultSchema} TO ${app_role};

-- Objects later migrations create (as ledger_owner, the role running this statement) get the same grants.
ALTER DEFAULT PRIVILEGES IN SCHEMA ${flyway:defaultSchema}
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${app_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA ${flyway:defaultSchema}
    GRANT USAGE, SELECT ON SEQUENCES TO ${app_role};

-- Migration history is Flyway's alone.
REVOKE ALL ON TABLE ${flyway:defaultSchema}.flyway_schema_history FROM ${app_role};

-- Append-only books (ADR-0017): the immutability triggers stay as the second line; the runtime role is refused by
-- privilege before they even fire. Only a superuser (scripts/ledger-tamper-demo.sh) can still bypass them.
REVOKE UPDATE, DELETE, TRUNCATE ON TABLE ${flyway:defaultSchema}.journal_entry FROM ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON TABLE ${flyway:defaultSchema}.posting FROM ${app_role};
