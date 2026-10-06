#!/usr/bin/env bash
# Opens an SSM port-forward from localhost to the demo's `web` container (nginx, port 80) (ADR-0028).
# The demo has no load balancer and no public endpoint: this is the only way in, and it needs YOUR AWS
# identity (an SSO profile with ssm:StartSession), not a CI role. Requires the AWS CLI v2 and the
# session-manager-plugin. Prints no secret; the dashboard needs an API token (make demo-tokens).
#
# Usage: demo-tunnel.sh [--region eu-central-1] [--local-port 8088] [--dry-run]
#   Uses AWS_PROFILE from the environment. --dry-run prints the start-session command and exits.
# Result: http://localhost:<local-port>/ serves the dashboard and /api/v1/** (same-origin proxy).
set -euo pipefail

region="eu-central-1"
local_port=8088
dry_run=0
cluster="saiman-demo"
service="saiman-demo-lite"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --region)
      [[ $# -ge 2 ]] || exit 2
      region="$2"
      shift 2
      ;;
    --local-port)
      [[ $# -ge 2 ]] || exit 2
      local_port="$2"
      shift 2
      ;;
    --dry-run)
      dry_run=1
      shift
      ;;
    *)
      echo "usage: demo-tunnel.sh [--region <region>] [--local-port <port>] [--dry-run]" >&2
      exit 2
      ;;
  esac
done
[[ "${local_port}" =~ ^[0-9]{2,5}$ ]] || {
  echo "demo-tunnel: --local-port must be a port number" >&2
  exit 2
}
export AWS_PAGER=""
work="$(mktemp -d "${TMPDIR:-/tmp}/saiman-tunnel.XXXXXX")"
trap 'rm -rf "${work}"' EXIT

aws --region "${region}" --output json ecs list-tasks --cluster "${cluster}" --service-name "${service}" >"${work}/tasks.json"
task_arn="$(jq -r '.taskArns[0] // empty' "${work}/tasks.json")"
[[ -n "${task_arn}" ]] || {
  echo "demo-tunnel: no running task in ${cluster}/${service} (is the demo up, or already expired?)" >&2
  exit 1
}
task_id="${task_arn##*/}"
aws --region "${region}" --output json ecs describe-tasks --cluster "${cluster}" --tasks "${task_arn}" >"${work}/task.json"
runtime_id="$(jq -r '[.tasks[0].containers[]? | select(.name == "web")][0].runtimeId // empty' "${work}/task.json")"
web_status="$(jq -r '[.tasks[0].containers[]? | select(.name == "web")][0].lastStatus // "unknown"' "${work}/task.json")"
[[ -n "${runtime_id}" && "${web_status}" == "RUNNING" ]] || {
  echo "demo-tunnel: the web container is not running yet (status: ${web_status}); wait for the readiness gate" >&2
  exit 1
}

target="ecs:${cluster}_${task_id}_${runtime_id}"
parameters="{\"portNumber\":[\"80\"],\"localPortNumber\":[\"${local_port}\"]}"
if [[ "${dry_run}" -eq 1 ]]; then
  printf 'aws --region %s ssm start-session --target %s --document-name AWS-StartPortForwardingSession --parameters %s\n' \
    "${region}" "${target}" "'${parameters}'"
  exit 0
fi
command -v session-manager-plugin >/dev/null 2>&1 || {
  echo "demo-tunnel: session-manager-plugin not found (see docs/SETUP.md, AWS demo prerequisites)" >&2
  exit 1
}
echo "demo-tunnel: forwarding http://localhost:${local_port} -> web:80 (Ctrl-C to close)"
exec aws --region "${region}" ssm start-session --target "${target}" \
  --document-name AWS-StartPortForwardingSession --parameters "${parameters}"
