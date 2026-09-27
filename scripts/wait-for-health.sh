#!/usr/bin/env bash
# Poll http://localhost:<port>/actuator/health on every given port until it reports
# UP, or fail with a readable message. Paketo "tiny" buildpack images have no shell,
# so Docker Compose cannot healthcheck the app containers itself (ADR-0007 R8); this
# script does it from the host instead. Used by `make up`.
#
# Usage: scripts/wait-for-health.sh PORT [PORT...]
set -euo pipefail

TIMEOUT_SECONDS="${WAIT_FOR_HEALTH_TIMEOUT:-120}"
INTERVAL_SECONDS=2
CURL_MAX_TIME=2

if [[ $# -eq 0 ]]; then
  echo "usage: $0 PORT [PORT...]" >&2
  exit 2
fi

for port in "$@"; do
  if [[ ! "${port}" =~ ^[0-9]{1,5}$ ]]; then
    echo "wait-for-health: invalid port '${port}': must be 1-5 digits" >&2
    exit 2
  fi
done

is_up() {
  local port="$1"
  local status
  status=$(curl -fsS --max-time "${CURL_MAX_TIME}" -o /dev/null -w '%{http_code}' "http://localhost:${port}/actuator/health" 2>/dev/null) || return 1
  [[ "${status}" == "200" ]]
}

pending=("$@")
start_ts=$(date +%s)

while [[ ${#pending[@]} -gt 0 ]]; do
  still_pending=()
  for port in "${pending[@]}"; do
    if is_up "${port}"; then
      echo "port ${port}: UP"
    else
      still_pending+=("${port}")
    fi
  done
  pending=("${still_pending[@]}")

  if [[ ${#pending[@]} -eq 0 ]]; then
    break
  fi

  elapsed=$(( $(date +%s) - start_ts ))
  if [[ ${elapsed} -ge ${TIMEOUT_SECONDS} ]]; then
    echo "wait-for-health: timed out after ${elapsed}s waiting for /actuator/health on port(s): ${pending[*]}" >&2
    echo "Check container logs, e.g.: docker compose -f deploy/compose/docker-compose.yml logs" >&2
    exit 1
  fi

  sleep "${INTERVAL_SECONDS}"
done

echo "wait-for-health: all services UP (${*}) after $(( $(date +%s) - start_ts ))s"
