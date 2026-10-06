#!/usr/bin/env bash
# Waits until the `readiness` container of the demo task has exited 0 (all four services UP), ADR-0028.
# The task ARN is re-resolved on every poll: when an essential container dies ECS replaces the task and
# the new task runs readiness again. Exit 0 ready, 1 readiness failed or timed out, 2 usage error.
#
# Usage: wait-demo-ready.sh --region eu-central-1 [--timeout-seconds 1200] [--interval-seconds 15]
set -euo pipefail

region=""
timeout=1200
interval=15
cluster="saiman-demo"
service="saiman-demo-lite"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --region)
      [[ $# -ge 2 ]] || exit 2
      region="$2"
      shift 2
      ;;
    --timeout-seconds)
      [[ $# -ge 2 ]] || exit 2
      timeout="$2"
      shift 2
      ;;
    --interval-seconds)
      [[ $# -ge 2 ]] || exit 2
      interval="$2"
      shift 2
      ;;
    *)
      echo "usage: wait-demo-ready.sh --region <region> [--timeout-seconds N] [--interval-seconds N]" >&2
      exit 2
      ;;
  esac
done
[[ -n "${region}" && "${timeout}" =~ ^[0-9]+$ && "${interval}" =~ ^[0-9]+$ ]] || {
  echo "usage: wait-demo-ready.sh --region <region> [--timeout-seconds N] [--interval-seconds N]" >&2
  exit 2
}
export AWS_PAGER=""
work="$(mktemp -d "${TMPDIR:-/tmp}/saiman-ready.XXXXXX")"
trap 'rm -rf "${work}"' EXIT

deadline=$((SECONDS + timeout))
last=""
while true; do
  aws --region "${region}" --output json ecs list-tasks --cluster "${cluster}" --service-name "${service}" >"${work}/tasks.json"
  task="$(jq -r '.taskArns[0] // empty' "${work}/tasks.json")"
  status="no task yet"
  if [[ -n "${task}" ]]; then
    aws --region "${region}" --output json ecs describe-tasks --cluster "${cluster}" --tasks "${task}" >"${work}/task.json"
    # Fail fast on a one-shot that failed: the dependent containers will never start.
    failed="$(jq -r '[.tasks[0].containers[]? | select((.name | test("^(assets|db-init|corpus-restore|.+-migrate)$")) and .lastStatus == "STOPPED" and (.exitCode // 0) != 0) | "\(.name) (exit \(.exitCode))"] | join(", ")' "${work}/task.json")"
    if [[ -n "${failed}" ]]; then
      echo "::error::demo task: one-shot container(s) failed: ${failed}. See CloudWatch log group /saiman-demo-lite (stream prefix = container name)."
      exit 1
    fi
    code="$(jq -r '[.tasks[0].containers[]? | select(.name == "readiness")][0].exitCode // empty' "${work}/task.json")"
    status="$(jq -r '[.tasks[0].containers[]? | select(.name == "readiness")][0].lastStatus // "readiness container not reported yet"' "${work}/task.json")"
    if [[ "${code}" == "0" ]]; then
      echo "wait-demo-ready: readiness exited 0, all services are UP"
      exit 0
    elif [[ -n "${code}" ]]; then
      echo "::error::readiness exited ${code}: not all services came up. See log group /saiman-demo-lite, stream prefix readiness."
      exit 1
    fi
  fi
  if [[ "${status}" != "${last}" ]]; then
    echo "wait-demo-ready: waiting (${status})"
    last="${status}"
  fi
  if [[ "${SECONDS}" -ge "${deadline}" ]]; then
    echo "::error::demo task not ready after ${timeout}s (${status})"
    exit 1
  fi
  sleep "${interval}"
done
