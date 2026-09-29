#!/usr/bin/env bash
# PreToolUse hook (Bash): rewrites test/build/lint commands so their output goes through
# test-output-filter.sh, which keeps only failures/errors and the final summary. This keeps
# long Gradle/pnpm logs out of the context window (lean mode). The command's exit status is
# preserved (pipefail). Add the comment `# nofilter` to a command to see its full output.
set -euo pipefail

input="$(cat)"
command="$(jq -r '.tool_input.command // empty' <<<"$input")"
[[ -z "$command" ]] && exit 0
[[ "$command" == *"# nofilter"* || "$command" == *"test-output-filter.sh"* ]] && exit 0

gradle='(^|[;&|( ]+)\./gradlew( [^;&|]*)? (check|test|build|spotlessCheck|spotlessApply|publishToMavenLocal|[A-Za-z:-]*:(check|test|build))( |$|[;&|)])'
make_='(^|[;&|( ]+)make( -[A-Za-z]+)* (test|lint)( |$|[;&|)])'
pnpm='(^|[;&|( ]+)pnpm( [^;&|]*)? (test|lint|typecheck)( |$|[;&|)])'
if [[ ! "$command" =~ $gradle && ! "$command" =~ $make_ && ! "$command" =~ $pnpm ]]; then
  exit 0
fi

filter='"${CLAUDE_PROJECT_DIR:-.}"/.claude/hooks/test-output-filter.sh'
wrapped="set -o pipefail; { ${command}
} 2>&1 | ${filter}"

jq -n --arg cmd "$wrapped" '{hookSpecificOutput: {hookEventName: "PreToolUse", updatedInput: {command: $cmd}}}'
