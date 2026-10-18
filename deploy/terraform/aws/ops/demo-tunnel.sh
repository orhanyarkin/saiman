#!/usr/bin/env bash
# Opens an SSM port-forward from localhost to the demo's `web` container (nginx, port 80) (ADR-0028).
# The demo has no load balancer and no public endpoint: this is the only way in, and it needs YOUR AWS
# identity (an SSO profile with ssm:StartSession), not a CI role. Requires the AWS CLI v2 and the
# session-manager-plugin. Prints no secret; the dashboard needs an API token (make demo-tokens).
#
# Usage: demo-tunnel.sh [--region eu-central-1] [--local-port 8088] [--dry-run]
#   Uses AWS_PROFILE from the environment. --dry-run prints the start-session command and exits.
# Result: http://localhost:<local-port>/ serves the dashboard and /api/v1/** (same-origin proxy).
#
# The local port must be free. The local compose stack publishes its dashboard on 127.0.0.1:8088, the same default: with both
# running, localhost:8088 is the LOCAL stack, which answers 401 to the demo's tokens (demo-up #9). The script therefore refuses
# a port that is already listening, says who holds it, and suggests another one (make demo-tunnel TUNNEL_PORT=18088).
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
# port_in_use <port>: 0 when something accepts TCP connections on loopback (IPv4 or IPv6).
port_in_use() {
  local host
  for host in 127.0.0.1 ::1; do
    if (exec 3<>"/dev/tcp/${host}/$1") 2>/dev/null; then
      return 0
    fi
  done
  return 1
}

# port_holder <port>: who holds the port, as far as docker, ss or lsof can tell (a docker-proxy hides its owner from ss).
port_holder() {
  local port="$1" found=""
  if command -v docker >/dev/null 2>&1; then
    found="$(docker ps --format '{{.Names}} ({{.Image}}) {{.Ports}}' 2>/dev/null | grep -E "(^|[ ,])(127\.0\.0\.1|0\.0\.0\.0|\[::\]|\[::1\]):${port}->" | sed -E 's/ [^ ]*:[0-9]+->.*$//' | head -n 3 || true)"
    if [[ -n "${found}" ]]; then
      sed 's/^/docker container /' <<<"${found}"
      return 0
    fi
  fi
  if command -v ss >/dev/null 2>&1; then
    found="$(ss -ltnpH "sport = :${port}" 2>/dev/null | head -n 3 || true)"
    [[ -z "${found}" ]] || {
      echo "listener: ${found}"
      return 0
    }
  fi
  if command -v lsof >/dev/null 2>&1; then
    found="$(lsof -nP -iTCP:"${port}" -sTCP:LISTEN 2>/dev/null | tail -n +2 | head -n 3 || true)"
    [[ -z "${found}" ]] || {
      echo "listener: ${found}"
      return 0
    }
  fi
  echo "unknown (neither docker, ss nor lsof could tell)"
}

if [[ "${dry_run}" -eq 0 ]] && port_in_use "${local_port}"; then
  {
    echo "demo-tunnel: local port ${local_port} is already in use, so http://localhost:${local_port} would not be the demo:"
    port_holder "${local_port}" | sed 's/^/  held by: /'
    echo "  The usual cause is the local compose stack (its dashboard is published on 127.0.0.1:8088). Stop it, or use another port:"
    echo "    make demo-tunnel TUNNEL_PORT=$((local_port + 10000 > 65535 ? 18088 : local_port + 10000))"
    echo "  (make demo-capture takes the same TUNNEL_PORT.)"
  } >&2
  exit 1
fi

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
