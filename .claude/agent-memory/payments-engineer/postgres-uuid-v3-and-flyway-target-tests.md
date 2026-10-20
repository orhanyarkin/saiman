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

See also [[spring7-scheduling-and-sandbox-quirks]], [[worktree-bash-and-kafka-gotchas]].
