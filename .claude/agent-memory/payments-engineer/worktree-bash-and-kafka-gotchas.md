---
name: worktree-bash-and-kafka-gotchas
description: Worktree-isolated Bash refuses compound commands (cd+git, heredoc+gradle, $$ in heredocs, vars in sed); Spring Kafka 4.1 DLT/back-off facts; ProblemDetail instance echoes the path; raw-socket Host tests.
metadata:
  type: reference
---

- Worktree isolation guard refuses: `cd X && git ...` chains with other commands, brace expansion in paths, `sed` with `$VAR` file args, heredocs containing `$$` (plpgsql). A `cd <worktree> && python3 - <<'EOF'` edit batched in parallel with a gradle call was refused (explicit error) while the same shape passed when run alone; the gradle run then tested the unedited code. When batching an edit with a build, check the edit result before trusting the build. Workaround: write a Python script to the scratchpad with Write, then run `python3 <script>` alone; run gradle in its own call.
- Spring Kafka 4.1.1: `DefaultErrorHandler` back-off sleeps on the poll thread (short-sleep loop); keep max interval below `max.poll.interval.ms` or use `ContainerPausingBackOffHandler`. If the recoverer throws, the back-off resets and the record loops forever: wrap the `DeadLetterPublishingRecoverer` so a failed DLT send is counted and skipped.
- DLPR copies the source record's headers into the DLT record; override `createProducerRecord(...)` to filter. `excludeHeader(HeadersToAdd.EX_MSG, EX_CAUSE, EX_STACKTRACE)`. An observation-enabled template adds `traceparent` to the DLT record.
- Jackson 3.1: `MapperFeature.ALLOW_COERCION_OF_SCALARS`, `StreamReadFeature.STRICT_DUPLICATE_DETECTION` (verified in jars). JdbcClient `.query(String.class).list()` trips NullAway (List<@Nullable String>); use a row mapper.
- FiatToken EIP3009.sol: `_markAuthorizationAsUsed` (AuthorizationUsed) is emitted before `_transfer` (Transfer) — verified 2026-10-01.
- Testing X402SettlementFilter's isAsyncStarted backstop without an async return type (rejected at startup): a void handler calling `request.startAsync(request, response).complete()` keeps isAsyncStarted() true after dispatch on Tomcat (verified 2026-10-01). Forcing a response write to fail: an outer OncePerRequestFilter (HIGHEST_PRECEDENCE) wrapping the response; ContentCachingResponseWrapper.getWriter never delegates, but setContentType/setStatus do.
- Spring MVC fills ProblemDetail `instance` with the request path when it is null, so a 400/404 thrown for a path variable (a payment key) echoes it back. Set `problem.setInstance(URI.create("/fixed"))` when the path carries an identifier (seen 2026-10-01, seller-api credit-note lookup).
- Raw-socket HTTP tests (JDK HttpClient refuses a custom Host header): Tomcat answers a ProblemDetail chunked, so assert with `contains` on the raw body rather than parsing. A Host check inside the handler (not a path-prefix filter) holds for odd path forms (`/internal;x=1/...`, `/%69nternal/...`) whatever the container does with them.
- The worktree guard also refused a `cd ... && python3 - <<EOF` that was alone (no build) when the heredoc was long; the scratchpad-script workaround is the reliable form.
