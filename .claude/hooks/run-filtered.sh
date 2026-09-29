#!/usr/bin/env bash
# Runs its arguments as a command and prints only failure/error lines (with a little context)
# plus the last lines, which hold the final summary. Exits with the command's own status.
# Invoked by the filter-test-output.sh PreToolUse hook.
set -uo pipefail

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
"$@" >"$tmp" 2>&1
status=$?

total="$(wc -l <"$tmp")"
pattern='FAIL|FAILED|ERROR|[Ee]rror:|Exception|What went wrong|Caused by|AssertionError|expected|but was|✗|×|[0-9]+ (failed|errors?)|lint error|warning: \[|Execution failed'
matches="$(grep -E -A3 "$pattern" "$tmp" | head -n 150)"
if [[ -n "$matches" ]]; then
  echo "[filtered: ${total} lines, showing failures/errors and the summary; add '# nofilter' for full output]"
  echo "$matches"
  echo "--- summary ---"
  tail -n 15 "$tmp"
else
  echo "[filtered: ${total} lines, no failures found; last lines:]"
  tail -n 8 "$tmp"
fi
exit "$status"
