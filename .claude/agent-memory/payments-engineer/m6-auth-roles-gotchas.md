---
name: m6-auth-roles-gotchas
description: Boot 4.1 security filter order constant location, RestTestClient default-header pitfalls, Spring 7 DAO messages without cause, worktree Bash guard false positives on "eval" paths
metadata:
  type: reference
---

- Boot 4.1: `DEFAULT_FILTER_ORDER` (-100) lives in `org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties`, not `SecurityProperties`.
- `RestTestClientBuilderCustomizer` (spring-boot-resttestclient) adds default headers; `.headers(h -> h.remove(...))` per request does NOT remove a default header (defaults merge later). Use a separate `RestTestClient.bindToServer().baseUrl(...)` for no-token tests. A per-request `.header(AUTHORIZATION, x)` does override.
- Spring 7 `BadSqlGrammarException` message no longer contains the PSQL text: assert with `.rootCause().hasMessageContaining(...)` or `NestedExceptionUtils.getMostSpecificCause`.
- Runtime role `<svc>_app`: superuser-created test objects (sequences used by trigger functions) need an explicit GRANT to the app role; default privileges only cover objects created by the owner.
- Worktree Bash guard refuses commands whose paths contain `eval` (e.g. `shared/eval/`), brace expansion over paths, `for`/`while read` loops, and long python heredocs mixed with other commands: put edit scripts in the scratchpad and run `python3 <file>` alone.

Related: [[boot4-modularisation-gotchas]], [[worktree-bash-and-kafka-gotchas]]
