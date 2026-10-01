---
name: worktree-bash-and-kafka-gotchas
description: Worktree-isolated Bash refuses compound commands (cd+git, heredoc+gradle, $$ in heredocs, vars in sed); Spring Kafka 4.1 DLT/back-off facts verified in the M4 audit fix.
metadata:
  type: reference
---

- Worktree isolation guard refuses: `cd X && git ...` chains with other commands, brace expansion in paths, `sed` with `$VAR` file args, heredocs containing `$$` (plpgsql). A `cd <worktree> && python3 - <<'EOF'` edit batched in parallel with a gradle call was refused (explicit error) while the same shape passed when run alone; the gradle run then tested the unedited code. When batching an edit with a build, check the edit result before trusting the build. Workaround: write a Python script to the scratchpad with Write, then run `python3 <script>` alone; run gradle in its own call.
- Spring Kafka 4.1.1: `DefaultErrorHandler` back-off sleeps on the poll thread (short-sleep loop); keep max interval below `max.poll.interval.ms` or use `ContainerPausingBackOffHandler`. If the recoverer throws, the back-off resets and the record loops forever: wrap the `DeadLetterPublishingRecoverer` so a failed DLT send is counted and skipped.
- DLPR copies the source record's headers into the DLT record; override `createProducerRecord(...)` to filter. `excludeHeader(HeadersToAdd.EX_MSG, EX_CAUSE, EX_STACKTRACE)`. An observation-enabled template adds `traceparent` to the DLT record.
- Jackson 3.1: `MapperFeature.ALLOW_COERCION_OF_SCALARS`, `StreamReadFeature.STRICT_DUPLICATE_DETECTION` (verified in jars). JdbcClient `.query(String.class).list()` trips NullAway (List<@Nullable String>); use a row mapper.
- FiatToken EIP3009.sol: `_markAuthorizationAsUsed` (AuthorizationUsed) is emitted before `_transfer` (Transfer) — verified 2026-10-01.
- Testing X402SettlementFilter's isAsyncStarted backstop without an async return type (rejected at startup): a void handler calling `request.startAsync(request, response).complete()` keeps isAsyncStarted() true after dispatch on Tomcat (verified 2026-10-01). Forcing a response write to fail: an outer OncePerRequestFilter (HIGHEST_PRECEDENCE) wrapping the response; ContentCachingResponseWrapper.getWriter never delegates, but setContentType/setStatus do.
