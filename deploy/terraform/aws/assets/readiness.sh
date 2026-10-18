#!/bin/sh
# Task readiness gate for the demo-lite ECS task (ADR-0028). POSIX sh + wget + nc only, so it runs in a
# busybox image. Polls <host>:<port>/actuator/health of the four services until every one reports
# status UP, then exits 0.
#
# A service that is not UP is explained, so a failed demo-up shows the cause at a glance (demo-up #8 only said "not UP after
# 1200s" and the Redis error had to be dug out of four log streams): every READINESS_REPORT_INTERVAL seconds and when the
# timeout is reached it prints the HTTP status, the overall status and name=STATUS of every health component that is not
# UP. busybox wget throws the body of a 503 away (which is what a DOWN service answers), so that part uses nc. Only
# the HTTP status code and tokens matching [A-Za-z0-9_-]+ / [A-Z_]+ are printed, never the body itself: nothing else
# (details, messages, hostnames) can reach the log, even if a service had health details switched on.
#
# Environment (all optional):
#   READINESS_TIMEOUT_SECONDS  overall timeout, default 1200 (20 min); exit 1 when it is reached
#   READINESS_INTERVAL         seconds between rounds, default 5
#   READINESS_REPORT_INTERVAL  seconds between progress reports for services that are not UP, default 120
#   READINESS_HOST             default 127.0.0.1
#   READINESS_TARGETS          space separated name:port list,
#                              default "orchestrator:8080 seller-api:8081 ledger:8082 ingest:8083"
set -eu

timeout="${READINESS_TIMEOUT_SECONDS:-1200}"
interval="${READINESS_INTERVAL:-5}"
host="${READINESS_HOST:-127.0.0.1}"
targets="${READINESS_TARGETS:-orchestrator:8080 seller-api:8081 ledger:8082 ingest:8083}"

report="${READINESS_REPORT_INTERVAL:-120}"

# explain <name> <port>: one line on why the service is not UP (see the header; never prints the body).
explain() {
  if ! command -v nc >/dev/null 2>&1; then
    echo "readiness: $1 (port $2): nc is not available, cannot read the health body"
    return 0
  fi
  reply="$(printf 'GET /actuator/health HTTP/1.0\r\nHost: %s:%s\r\n\r\n' "${host}" "$2" | nc -w 3 "${host}" "$2" 2>/dev/null | tr -d '\r' || true)"
  if [ -z "${reply}" ]; then
    echo "readiness: $1 (port $2): no answer (not listening yet?)"
    return 0
  fi
  code="$(printf '%s\n' "${reply}" | sed -n '1s|^HTTP/[0-9.]* \([0-9][0-9][0-9]\).*|\1|p' || true)"
  overall="$(printf '%s\n' "${reply}" | grep -o '^{"status":"[A-Z_]*"' | head -n 1 | sed 's|.*:"\(.*\)"|\1|' || true)"
  down="$(printf '%s\n' "${reply}" | grep -oE '"[A-Za-z0-9_-]+":\{"status":"[A-Z_]+"' | grep -v '"status":"UP"' | sed -E 's|^"([^"]+)":\{"status":"([A-Z_]+)"$|\1=\2|' | tr '\n' ' ' || true)"
  if [ -n "${down}" ]; then
    echo "readiness: $1 (port $2): HTTP ${code:-?}, status ${overall:-?}, components not UP: ${down% }"
  else
    echo "readiness: $1 (port $2): HTTP ${code:-?}, status ${overall:-?}, no component listed as not UP (is MANAGEMENT_ENDPOINT_HEALTH_SHOW_COMPONENTS=always set?)"
  fi
}

start="$(date +%s)"
last_report="${start}"
pending="${targets}"

while :; do
  still=""
  for t in ${pending}; do
    name="${t%%:*}"
    port="${t##*:}"
    # The body is only matched here. Spring serialises the top-level status first.
    if wget -q -T 3 -O - "http://${host}:${port}/actuator/health" 2>/dev/null | grep -q '^{"status":"UP"'; then
      echo "readiness: ${name} (port ${port}) is UP"
    else
      still="${still} ${t}"
    fi
  done
  pending="${still# }"
  if [ -z "${pending}" ]; then
    echo "readiness: all services are UP"
    exit 0
  fi
  now="$(date +%s)"
  if [ $((now - start)) -ge "${timeout}" ]; then
    for t in ${pending}; do
      echo "readiness: ${t%%:*} (port ${t##*:}) not UP after ${timeout}s" >&2
      explain "${t%%:*}" "${t##*:}" >&2
    done
    exit 1
  fi
  if [ $((now - last_report)) -ge "${report}" ]; then
    last_report="${now}"
    for t in ${pending}; do
      explain "${t%%:*}" "${t##*:}"
    done
  fi
  sleep "${interval}"
done
