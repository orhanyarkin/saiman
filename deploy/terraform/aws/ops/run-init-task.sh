#!/usr/bin/env bash
# Runs the demo-lite init task (role bootstrap, Flyway migrations, corpus restore) once and requires EVERY container
# to have exited 0 (ADR-0027, ADR-0028). Why a script: ECS allows 10 containers per task definition, so the one-shots
# live in a second, run-to-completion task definition and `dependsOn` can no longer gate the long-running service.
# demo-up starts the service (desired count 1) only after this script succeeded.
#
# The task ends by itself: its essential `done` container starts when every one-shot exited 0. If a one-shot fails,
# `done` never starts and the task may keep running, so a failed container stops the task here (fail fast), and so
# does the timeout. Output lists container names, exit codes and ECS reasons only; no environment, no secrets.
#
# Usage: run-init-task.sh --region R --cluster C --task-definition ARN --subnets subnet-a,subnet-b --security-group sg-x
#                         [--timeout-seconds 900] [--interval-seconds 10]
# Exit 0 every container exited 0, 1 a container failed / the task did not finish / run-task failed, 2 usage error.
set -euo pipefail

usage() {
  echo "usage: run-init-task.sh --region R --cluster C --task-definition ARN --subnets a,b --security-group sg [--timeout-seconds N] [--interval-seconds N]" >&2
  exit 2
}
region="" cluster="" taskdef="" subnets="" sg="" timeout=900 interval=10
while [[ $# -gt 0 ]]; do
  [[ $# -ge 2 ]] || usage
  case "$1" in
    --region) region="$2" ;;
    --cluster) cluster="$2" ;;
    --task-definition) taskdef="$2" ;;
    --subnets) subnets="$2" ;;
    --security-group) sg="$2" ;;
    --timeout-seconds) timeout="$2" ;;
    --interval-seconds) interval="$2" ;;
    *) usage ;;
  esac
  shift 2
done
[[ "${region}" =~ ^[a-z]{2}-[a-z]+-[0-9]$ ]] || usage
[[ "${cluster}" =~ ^saiman-demo[a-z0-9-]*$ ]] || usage
[[ "${taskdef}" =~ ^arn:aws:ecs:[a-z0-9-]+:[0-9]{12}:task-definition/saiman-demo[a-z0-9-]*:[0-9]+$ ]] || usage
[[ "${subnets}" =~ ^subnet-[0-9a-f]+(,subnet-[0-9a-f]+)*$ ]] || usage
[[ "${sg}" =~ ^sg-[0-9a-f]+$ ]] || usage
[[ "${timeout}" =~ ^[0-9]+$ && "${interval}" =~ ^[1-9][0-9]*$ ]] || usage

export AWS_PAGER=""
work="$(mktemp -d "${TMPDIR:-/tmp}/saiman-init-task.XXXXXX")"
trap 'rm -rf "${work}"' EXIT

# Public IP: there is no NAT, so the task needs it to pull the images and reach S3 (the SG has no ingress).
aws --region "${region}" --output json ecs run-task \
  --cluster "${cluster}" --task-definition "${taskdef}" --launch-type FARGATE --count 1 --started-by demo-up \
  --network-configuration "awsvpcConfiguration={subnets=[${subnets}],securityGroups=[${sg}],assignPublicIp=ENABLED}" >"${work}/run.json"

if [[ "$(jq -r '(.failures // []) | length' "${work}/run.json")" != "0" ]]; then
  echo "::error::ecs run-task failed: $(jq -r '[.failures[] | "\(.reason // "unknown reason") (\(.detail // "no detail"))"] | join("; ")' "${work}/run.json")" >&2
  exit 1
fi
task="$(jq -r '.tasks[0].taskArn // empty' "${work}/run.json")"
[[ -n "${task}" ]] || {
  echo "::error::ecs run-task returned no task" >&2
  exit 1
}
echo "init: started ${task##*/} (${taskdef##*/})"

stop_task() {
  aws --region "${region}" --output json ecs stop-task --cluster "${cluster}" --task "${task}" --reason "$1" >/dev/null 2>&1 || true
}
# One line per container: "name exitCode|- lastStatus reason".
containers() {
  jq -r '.tasks[0].containers[]? | "\(.name) \(.exitCode // "-") \(.lastStatus // "-") \(.reason // "")"' "${work}/task.json"
}

deadline=$((SECONDS + timeout))
last=""
while true; do
  aws --region "${region}" --output json ecs describe-tasks --cluster "${cluster}" --tasks "${task}" >"${work}/task.json"
  status="$(jq -r '.tasks[0].lastStatus // "UNKNOWN"' "${work}/task.json")"
  failed="$(jq -r '[.tasks[0].containers[]? | select(.lastStatus == "STOPPED" and (.exitCode // 0) != 0) | "\(.name) (exit \(.exitCode))"] | join(", ")' "${work}/task.json")"

  if [[ -n "${failed}" ]]; then
    echo "::error::init task: container(s) failed: ${failed}. Logs: CloudWatch group /saiman-demo-lite, stream prefix = container name." >&2
    containers | sed 's/^/init:   /' >&2
    stop_task "demo-up: an init container failed"
    exit 1
  fi

  if [[ "${status}" == "STOPPED" ]]; then
    containers | sed 's/^/init:   /'
    # Every container must have run to completion with exit code 0 (a missing code means it never ran).
    incomplete="$(jq -r '[.tasks[0].containers[]? | select(.exitCode == null) | .name] | join(", ")' "${work}/task.json")"
    count="$(jq -r '[.tasks[0].containers[]?] | length' "${work}/task.json")"
    if [[ -n "${incomplete}" || "${count}" == "0" ]]; then
      echo "::error::init task stopped but did not run to completion (no exit code for: ${incomplete:-any container}). Stopped reason: $(jq -r '.tasks[0].stoppedReason // "none"' "${work}/task.json")" >&2
      exit 1
    fi
    echo "init: all ${count} init containers exited 0"
    exit 0
  fi

  if [[ "${status}" != "${last}" ]]; then
    echo "init: task ${status}"
    last="${status}"
  fi
  if [[ "${SECONDS}" -ge "${deadline}" ]]; then
    echo "::error::init task did not finish within ${timeout}s (status ${status}); stopping it" >&2
    containers | sed 's/^/init:   /' >&2
    stop_task "demo-up: init task timeout"
    exit 1
  fi
  sleep "${interval}"
done
