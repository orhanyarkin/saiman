#!/usr/bin/env bash
# Self-test for scripts/usage-profile.sh on a scratch copy of .claude/: switching pro <-> max restores every file
# byte for byte, applying the active profile again changes nothing, and an unknown profile fails.
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT
mkdir -p "${work}/.claude"
cp -r "${repo}/.claude/agents" "${repo}/.claude/profiles" "${work}/.claude/"
cp "${repo}/.claude/settings.json" "${work}/.claude/"
[[ -f "${repo}/.claude/profile" ]] && cp "${repo}/.claude/profile" "${work}/.claude/"

run() { SAIMAN_PROFILE_ROOT="${work}" "${repo}/scripts/usage-profile.sh" "$@" >/dev/null; }
snapshot() { (cd "${work}/.claude" && find agents settings.json profile -type f | sort | xargs sha256sum); }
fail() { echo "FAIL: $*" >&2; exit 1; }

run max; max1="$(snapshot)"
run pro; pro1="$(snapshot)"
[[ "${max1}" != "${pro1}" ]] || fail "pro and max produced identical files"
run max; [[ "$(snapshot)" == "${max1}" ]] || fail "max -> pro -> max did not restore the max files byte for byte"
run pro; [[ "$(snapshot)" == "${pro1}" ]] || fail "pro -> max -> pro did not restore the pro files byte for byte"
run pro; [[ "$(snapshot)" == "${pro1}" ]] || fail "applying the active profile again changed files"
grep -qx pro "${work}/.claude/profile" || fail ".claude/profile does not record the active profile"
grep -q '"CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS": "2"' "${work}/.claude/settings.json" || fail "pro must set 2 concurrent subagents"
run max
grep -q '"CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS": "3"' "${work}/.claude/settings.json" || fail "max must set 3 concurrent subagents"
grep -qx 'maxTurns: 60' "${work}/.claude/agents/security-auditor-milestone.md" || fail "max milestone audit must have 60 turns"
grep -qx 'maxTurns: 40' "${work}/.claude/agents/security-auditor.md" || fail "per-task audits keep 40 turns"
if SAIMAN_PROFILE_ROOT="${work}" "${repo}/scripts/usage-profile.sh" ultra >/dev/null 2>&1; then fail "unknown profile accepted"; fi
if SAIMAN_PROFILE_ROOT="${work}" "${repo}/scripts/usage-profile.sh" "../max" >/dev/null 2>&1; then fail "path-like profile accepted"; fi
echo "test-usage-profile: all checks passed"
