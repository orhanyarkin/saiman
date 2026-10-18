#!/usr/bin/env bash
# Every resource type declared in ../../demo-lite must have an entry in required-actions.json (and no entry may be stale),
# so a new resource type cannot reach apply/destroy without somebody deciding which IAM actions its CI roles need.
# bootstrap.tftest.hcl then checks the actions themselves against the role policies.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
demo="${here}/../../demo-lite"
table="${here}/required-actions.json"

declared="$(grep -hE '^resource "aws_[a-z0-9_]+"' "${demo}"/*.tf | sed -E 's/^resource "([^"]+)".*/\1/' | sort -u)"
listed="$(jq -r '.resources | keys[]' "${table}" | sort -u)"
[[ -n "${declared}" ]] || {
  echo "check-iam-coverage: no resource type found in ${demo}" >&2
  exit 2
}

rc=0
missing="$(comm -23 <(printf '%s\n' "${declared}") <(printf '%s\n' "${listed}"))"
stale="$(comm -13 <(printf '%s\n' "${declared}") <(printf '%s\n' "${listed}"))"
if [[ -n "${missing}" ]]; then
  echo "check-iam-coverage: FAIL: demo-lite declares resource types without an entry in tests/required-actions.json:" >&2
  printf '  %s\n' ${missing} >&2
  rc=1
fi
if [[ -n "${stale}" ]]; then
  echo "check-iam-coverage: FAIL: tests/required-actions.json lists resource types demo-lite no longer declares:" >&2
  printf '  %s\n' ${stale} >&2
  rc=1
fi
[[ "${rc}" -ne 0 ]] || echo "check-iam-coverage: OK ($(printf '%s\n' "${declared}" | wc -l | tr -d ' ') resource types covered)"
exit "${rc}"
