#!/usr/bin/env bash
# PreToolUse hook (Bash): rewrites a plain test/build/lint command into a single literal
# invocation of run-filtered.sh, which runs it and keeps only failures/errors and the final
# summary (lean mode: long Gradle/pnpm logs stay out of the context window).
#
# Only simple commands are rewritten — no ; && || | $( ` < > or newlines — so:
# - the rewritten command's name is a literal path (worktree-isolated agents' command guard
#   must be able to see what runs; a pipe into "${VAR}/script" is refused there), and
# - no other command can hide inside a wrapper and slip past permission rules
#   (e.g. `./gradlew check && git push` is left as is).
# Add the comment `# nofilter` to a command to see its full output.
set -euo pipefail

input="$(cat)"
command="$(jq -r '.tool_input.command // empty' <<<"$input")"
[[ -z "$command" ]] && exit 0
[[ "$command" == *"# nofilter"* ]] && exit 0

# Plain commands only.
if [[ "$command" == *$'\n'* || "$command" =~ [\;\&\|\<\>\`] || "$command" == *'$('* ]]; then
  exit 0
fi

gradle='^\./gradlew( [^ ]+)* (check|test|build|spotlessCheck|spotlessApply|publishToMavenLocal|[A-Za-z:-]*:(check|test|build))( |$)'
make_='^make( -[A-Za-z]+)* (test|lint)( |$)'
pnpm='^pnpm( [^ ]+)* (test|lint|typecheck)( |$)'
if [[ ! "$command" =~ $gradle && ! "$command" =~ $make_ && ! "$command" =~ $pnpm ]]; then
  exit 0
fi

# A literal absolute path, resolved now, so nothing in the rewritten command is computed at runtime.
runner="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/run-filtered.sh"
jq -n --arg cmd "${runner} ${command}" \
  '{hookSpecificOutput: {hookEventName: "PreToolUse", updatedInput: {command: $cmd}}}'
