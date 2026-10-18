#!/usr/bin/env bash
# Every `aws <service> <command>` the demo containers run must be listed (as "cli") in required-actions.json "workload",
# so the IAM actions of the task role cannot be forgotten: demo-up #6 failed because `aws s3 sync` lists the bucket and the
# task role could only read objects. Sources scanned: the command strings of demo-lite/containers.tf and every script the
# assets bundle ships (build-assets.sh `install` lines). bootstrap.tftest.hcl then requires each listed action from the role
# and from the permissions boundary.
#
# Also requires the entries the container definitions imply without an AWS CLI call: ECS Exec (enable_execute_command),
# secrets[].valueFrom (the execution role reads SSM) and the awslogs driver.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
aws_dir="${here}/../.."
demo="${aws_dir}/demo-lite"
table="${here}/required-actions.json"
repo="$(cd "${aws_dir}/../../.." && pwd)"

sources=("${demo}/containers.tf")
while IFS= read -r rel; do
  case "${rel}" in
    repo/*) sources+=("${repo}/${rel#repo/}") ;;
    here/*) sources+=("${aws_dir}/assets/${rel#here/}") ;;
  esac
done < <(sed -nE 's|^install -m [0-9]+ "\$\{(repo\|here)\}/([^"]+)".*|\1/\2|p' "${aws_dir}/assets/build-assets.sh")
for f in "${sources[@]}"; do
  [[ -f "${f}" ]] || {
    echo "check-workload-actions: source not found: ${f}" >&2
    exit 2
  }
done
[[ ${#sources[@]} -ge 4 ]] || {
  echo "check-workload-actions: build-assets.sh lists too few scripts (${#sources[@]}); did its install lines change?" >&2
  exit 2
}

# `aws s3 sync`, `aws s3 cp ...`; comment lines are prose, not commands.
found="$(grep -hvE '^[[:space:]]*(#|--)' "${sources[@]}" |
  grep -oE '(^|[^[:alnum:]_./-])aws[[:space:]]+[a-z0-9-]+[[:space:]]+[a-z0-9-]+' |
  sed -E 's/^[^a]*aws[[:space:]]+//; s/[[:space:]]+/ /g' | sort -u || true)"
listed="$(jq -r '.workload[] | .cli // empty' "${table}" | sort -u)"

rc=0
missing="$(comm -23 <(printf '%s\n' "${found}") <(printf '%s\n' "${listed}") | sed '/^$/d')"
stale="$(comm -13 <(printf '%s\n' "${found}") <(printf '%s\n' "${listed}") | sed '/^$/d')"
if [[ -n "${missing}" ]]; then
  echo "check-workload-actions: FAIL: containers run AWS CLI commands without an entry (\"cli\") in tests/required-actions.json workload:" >&2
  sed 's/^/  aws /' <<<"${missing}" >&2
  rc=1
fi
if [[ -n "${stale}" ]]; then
  echo "check-workload-actions: FAIL: tests/required-actions.json workload lists CLI commands no container runs any more:" >&2
  sed 's/^/  aws /' <<<"${stale}" >&2
  rc=1
fi

# Implied by the container definitions, no CLI call to find: each needs an entry that names the action.
need() { # <description> <role> <action>
  jq -e --arg r "$2" --arg a "$3" '[.workload[] | select(.role == $r and (.actions | index($a)))] | length > 0' "${table}" >/dev/null || {
    echo "check-workload-actions: FAIL: $1, but required-actions.json workload has no $2-role entry with $3" >&2
    rc=1
  }
}
grep -qE '^[[:space:]]*enable_execute_command[[:space:]]*=[[:space:]]*true' "${demo}/ecs.tf" && need "ecs.tf enables ECS Exec" task ssmmessages:CreateDataChannel
grep -q 'valueFrom' "${demo}/containers.tf" && need "containers.tf injects secrets[].valueFrom" execution ssm:GetParameters
grep -q 'logDriver *= *"awslogs"' "${demo}/containers.tf" && need "containers.tf uses the awslogs driver" execution logs:PutLogEvents

[[ "${rc}" -ne 0 ]] || echo "check-workload-actions: OK (${#sources[@]} sources, AWS CLI commands: $(printf '%s' "${found}" | tr '\n' ',' | sed 's/,$//; s/,/, /g'))"
exit "${rc}"
