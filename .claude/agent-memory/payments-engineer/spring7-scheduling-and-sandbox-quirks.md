---
name: spring7-scheduling-and-sandbox-quirks
description: Spring 7 scheduling API shape, NullAway with JdbcClient single-column lists, advisory-lock runner pattern, and the worktree Bash sandbox refusing heredocs/loops/unquoted vars
metadata:
  type: reference
---

- `ScheduledTaskRegistrar.addFixedDelayTask(Runnable, Duration, Duration)` does not exist in Spring 7; use
  `registrar.addFixedDelayTask(new FixedDelayTask(runnable, interval, initialDelay))`.
- Gate a schedule on a bean created by an auto-configuration with `SchedulingConfigurer` + `ObjectProvider`, not
  `@ConditionalOnBean` on application config (evaluated before auto-configs run).
- NullAway: `JdbcClient...query(String.class).list()` is `List<@Nullable String>`; use `.query((rs, row) -> rs.getString(1))`.
- Single-runner job over per-item transactions: `pg_try_advisory_lock` is session-scoped, so hold it on a dedicated
  `DataSource.getConnection()` for the run and do item work via `TransactionTemplate` on pooled connections; pair
  with an in-process `AtomicBoolean`. Catch `RuntimeException` per item (mark checked, PENDING) or one poison row
  fails every future run (it sorts first under `last_checked_at NULLS FIRST`).
- Worktree Bash sandbox: refuses commands with heredocs (`<<'EOF'`), `for` loops, brace expansion, or unquoted
  `$VAR` paths (the `github` path segment trips its git detector). Use Write/Edit tools for files, quote variables,
  and `python3 -c` for small edits.
- Separate Spring test context with its own containers: same meta-annotation + `@TestPropertySource(properties =
  "saiman.test.context=<name>")` changes only the cache key. See [[boot4-test-standard]].
