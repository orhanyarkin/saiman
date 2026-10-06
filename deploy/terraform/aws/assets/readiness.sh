#!/bin/sh
# Task readiness gate for the demo-lite ECS task (ADR-0028). POSIX sh + wget only, so it runs in a
# busybox image. Polls <host>:<port>/actuator/health of the four services until every one reports
# status UP, then exits 0. Prints only service names and ports, never a response body.
#
# Environment (all optional):
#   READINESS_TIMEOUT_SECONDS  overall timeout, default 1200 (20 min); exit 1 when it is reached
#   READINESS_INTERVAL         seconds between rounds, default 5
#   READINESS_HOST             default 127.0.0.1
#   READINESS_TARGETS          space separated name:port list,
#                              default "orchestrator:8080 seller-api:8081 ledger:8082 ingest:8083"
set -eu

timeout="${READINESS_TIMEOUT_SECONDS:-1200}"
interval="${READINESS_INTERVAL:-5}"
host="${READINESS_HOST:-127.0.0.1}"
targets="${READINESS_TARGETS:-orchestrator:8080 seller-api:8081 ledger:8082 ingest:8083}"

start="$(date +%s)"
pending="${targets}"

while :; do
  still=""
  for t in ${pending}; do
    name="${t%%:*}"
    port="${t##*:}"
    # The body is only matched, never printed. Spring serialises the top-level status first.
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
    done
    exit 1
  fi
  sleep "${interval}"
done
