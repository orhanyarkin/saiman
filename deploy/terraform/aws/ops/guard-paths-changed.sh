#!/usr/bin/env bash
# Does this event touch anything the Terraform guards protect?
#
# `terraform (fmt, validate, test)` (the `static` job of .github/workflows/terraform.yml) is a required status
# check of the protect-main ruleset. A workflow that path filtering skips reports NOTHING, and the ruleset then
# waits for that check forever ("Expected - Waiting for status to be reported"), which blocks every pull request
# that touches no Terraform file. The pull_request trigger therefore carries no `paths` filter and the job asks
# this script whether there is work to do; ops/check-workflow-run-blocks.sh enforces both halves of that deal.
#
# GUARD_PATHS (the job's env, kept equal to the push path filter by the same guard) lists every path the guards
# and the demo workflows depend on, in GitHub's filter spelling. `**` and `*` are matched as bash patterns here,
# which can only ever match more, never less.
#
# Fail closed: anything but a pull request whose base commit is at hand (a push, a manual run, an unknown base
# ref, a shallow clone, a failing diff) counts as changed, so the guards run.
#
# This script lives under deploy/terraform/ on purpose: that prefix is in GUARD_PATHS itself, so a change to the
# detector runs the guards it gates.
#
# Usage: guard-paths-changed.sh            (run from the repository root, with the base branch fetched)
# Env:   GUARD_PATHS (required), GITHUB_EVENT_NAME, GITHUB_BASE_REF, GITHUB_OUTPUT
# Writes `terraform=true|false` to $GITHUB_OUTPUT. Exit 0 decided, 1 misconfigured.
set -euo pipefail

work="$(mktemp -d "${TMPDIR:-/tmp}/saiman-guard-paths.XXXXXX")"
trap 'rm -rf "${work}"' EXIT

decide() { # decide <true|false> <reason>
  echo "$2"
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    echo "terraform=$1" >>"${GITHUB_OUTPUT}"
  fi
  exit 0
}

patterns=()
while IFS= read -r line; do
  line="${line#"${line%%[![:space:]]*}"}"
  line="${line%"${line##*[![:space:]]}"}"
  if [[ -n "${line}" ]]; then
    patterns+=("${line}")
  fi
done <<<"${GUARD_PATHS:-}"
if [[ ${#patterns[@]} -eq 0 ]]; then
  echo "::error::GUARD_PATHS is empty: the job cannot tell whether the guards must run" >&2
  exit 1
fi

event="${GITHUB_EVENT_NAME:-}"
base="${GITHUB_BASE_REF:-}"
if [[ "${event}" != pull_request || -z "${base}" ]]; then
  decide true "event '${event:-unknown}' is not a pull request: running the guards"
fi

merge_base=""
merge_base="$(git merge-base "origin/${base}" HEAD 2>/dev/null)" || true
if [[ -z "${merge_base}" ]]; then
  decide true "no merge base with origin/${base} (shallow clone or unknown base ref): running the guards"
fi

if ! git diff -z --name-only --no-renames "${merge_base}" HEAD >"${work}/changed"; then
  decide true "git diff against ${merge_base} failed: running the guards"
fi

while IFS= read -r -d '' file; do
  for pattern in "${patterns[@]}"; do
    # shellcheck disable=SC2053  # the right-hand side is a glob on purpose
    if [[ "${file}" == ${pattern} ]]; then
      decide true "guard path changed: ${file} (matches ${pattern}): running the guards"
    fi
  done
done <"${work}/changed"

decide false "no terraform changes: this pull request touches none of the guard paths"
