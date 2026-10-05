---
name: db-roles-and-security-chain-gotchas
description: ADR-0024 app-role pitfalls (Modulith schema initializer runs CREATE SCHEMA, StartupDatabase must use newPostgresDatabase) and seller-api's /internal-only security chain facts.
metadata:
  type: reference
---

- Spring Modulith JDBC's `databaseSchemaInitializer` runs `CREATE SCHEMA IF NOT EXISTS <schema>` on the app DataSource; as a DML-only role that fails startup ("permission denied for database"). Set `spring.modulith.events.jdbc.schema-initialization.enabled=false` whenever Flyway owns the table (seen 2026-10-05 on seller-api; ledger already had it).
- Tests that boot via SpringApplicationBuilder must use `SharedContainers.newPostgresDatabase()` (only path that creates the roles) and pass `spring.flyway.user`/`password` for the owner role; the container's default DB has no roles.
- A `securityMatcher("/internal/**")` chain leaves /v1 outside Spring Security (no X-Content-Type-Options header on /v1 responses is a cheap proof). Encoded forms like `/%69nternal/...` are matched by the chain (401), verified 2026-10-05.
- With `saiman.auth.enabled=false` a service has no chain, so Boot's default chain locks every path (including x402 paid paths). Open issue, not fixed.
- Worktree Bash guard also refuses brace expansion in `cat` paths and any python heredoc; write the script to the scratchpad and run it alone.
