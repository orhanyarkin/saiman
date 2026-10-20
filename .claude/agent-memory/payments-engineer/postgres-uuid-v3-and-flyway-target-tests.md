---
name: postgres-uuid-v3-and-flyway-target-tests
description: Deriving Java's UUID.nameUUIDFromBytes in Postgres SQL, and testing a Flyway migration against seeded pre-migration data with spring.flyway.target
metadata:
  type: reference
---

# Name-based UUID (version 3) in SQL

`UUID.nameUUIDFromBytes(name)` = MD5 of the bytes with `b[6] = (b[6] & 0x0f) | 0x30` and
`b[8] = (b[8] & 0x3f) | 0x80`. In the 32-character hex digest that is only characters **13** and **17**
(1-indexed: byte *i* occupies chars 2i+1, 2i+2):

```sql
WITH digest(h) AS (SELECT md5(p_name))
SELECT (substr(h, 1, 12) || '3' || substr(h, 14, 3)
        || to_hex((('x' || substr(h, 17, 1))::bit(4)::integer & 3) | 8) || substr(h, 18, 15))::uuid
  FROM digest;
```

`('x' || <hex char>)::bit(4)::integer` is the idiomatic hex-nibble-to-int cast. `uuid::text` in Postgres is
lower-case hyphenated, exactly like `UUID.toString()`, so concatenating a uuid into the name matches Java.
Verify such a formula with a test that compares against the Java call — never by eye.

# Testing a migration against pre-migration data

A `@SpringBootTest` with `spring.flyway.target=8` (plus a unique `saiman.test.context` property for its own
context) starts the app on the old schema. Seed through the app and raw SQL, then migrate the rest inside the
test:

```java
Flyway.configure().configuration(flyway.getConfiguration()).target(MigrationVersion.LATEST).load().migrate();
```

The injected `Flyway` bean's configuration already carries the owner credentials, so the test needs none.
`FlywayMigrationStrategy` lives in `org.springframework.boot.flyway.autoconfigure` in Boot 4.
`flyway_schema_history` is revoked from the runtime role, so read it with the owner JdbcClient.

Deferred constraint triggers (`DEFERRABLE INITIALLY DEFERRED`) fire at COMMIT and `CREATE OR REPLACE FUNCTION`
keeps the OID, so a migration cannot both write rows the old rule allows and tighten that rule: split it into
two migrations (Flyway gives each its own transaction unless `group` is set).

# Writing the plpgsql of such a migration

A non-STRICT `SELECT ... INTO v` nulls the target on zero rows, but reset the variable explicitly at the top of
each loop iteration: a stale value from iteration N silently changes derived ids in N+1. In a `WHERE NOT (...)`
prefer `NOT EXISTS (...)` over `x IN (subquery)` — `IN` yields NULL for a NULL left side and `NOT NULL` drops
the row instead of keeping it. A data migration should post a best-effort correction rather than `RAISE` on an
ordinary-but-unexpected state: an aborted migration leaves Flyway's history failed and the service unable to
start.

# Forcing saturated figures in a test

CHECK constraints are *not* disabled by `session_replication_role = replica` (only triggers are), so the ledger's
2^53-1 per-posting cap still applies: passing `Long.MAX_VALUE` in one account needs ≥1025 postings, best written
as one `INSERT ... SELECT FROM generate_series` inside a replica-mode superuser transaction (the deferred balance
trigger would otherwise walk every posting once per row). Tests that move global figures (suspense totals,
tampered postings) need their own `saiman.test.context`; siblings assert absolute values.

Error Prone's `ReturnValueIgnored` rejects a bare `optional.orElseThrow();` in a test; use
`assertThat(x.method()).isPresent()`.

See also [[spring7-scheduling-and-sandbox-quirks]], [[worktree-bash-and-kafka-gotchas]].
