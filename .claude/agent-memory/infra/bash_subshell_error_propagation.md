---
name: bash-subshell-error-propagation
description: Don't use a shared shell variable to report an error reason from a function whose stdout is captured via command substitution — the assignment is lost
metadata:
  type: feedback
---

A function invoked via command substitution (`x=$(some_func ...)`) runs in a **forked
subshell**. Any plain variable assignment inside that function (e.g. a "last error
message" global like `LAST_ERROR="..."`) is invisible to the caller once the
subshell exits — only the function's *stdout* (captured into `x`) and its *exit
status* cross the subshell boundary.

This bit `scripts/verify-trace.sh` (M0, T1 fix round, 2026-09-28): `run_curl()` set a
`LAST_ERROR` variable on failure, and was itself called from `fetch_trace_once()`,
which was itself called via `trace_json=$(fetch_trace_once "$id")` — two levels of
subshell. The caller's `reason="${LAST_ERROR}"` read the *parent* shell's copy, which
was never updated, so failure messages silently came out empty
(`last problem: ` with nothing after it). Caught by actually invoking the script
end-to-end against a real (missing) trace, not just `bash -n` or a code read — the
bug was invisible from static inspection.

**Why:** wasted a retry cycle chasing why "last problem: <reason>" was blank before
noticing the subshell boundary. The fix generalizes: this pattern (side-channel global
+ command substitution) is an easy trap any time a function needs to return "data" via
stdout AND "diagnostics" via a variable.

**How to apply:** when a bash function's result must be captured with `$(...)` and the
function also needs to report an out-of-band error/reason string, write the reason to
a **file** (`mktemp`, cleaned up via `trap ... EXIT`), not a shell variable — file I/O
survives subshells; variable assignment does not. Read the file back explicitly at the
point you need the reason (a small `read_reason() { cat "$REASON_FILE"; }` helper is
enough). Test this kind of script by actually running the failure path, not just
`bash -n`/shellcheck — subshell scoping bugs don't show up as syntax or lint issues.
