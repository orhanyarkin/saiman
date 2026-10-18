#!/bin/sh
# Task readiness gate for the demo-lite ECS task (ADR-0028). POSIX sh + wget + nc + awk only, so it runs in a
# busybox image. Polls <host>:<port>/actuator/health of the four services until every one answers HTTP 200, then exits 0.
#
# UP means HTTP 200 and nothing else. Spring Boot answers 200 for UP and 503 for DOWN, busybox wget exits 0 only for a 2xx, and
# the body is not looked at: its keys are written alphabetically, so `status` is the LAST key ({"groups":[...],"status":"UP"},
# or {"components":{...},"groups":[...],"status":"UP"} with components shown). The first version matched a body that starts with
# {"status":"UP" and so could never see a real service UP (found when demo-up #9 had healthy services and a failing gate).
#
# A service that is not UP is explained, so a failed demo-up shows the cause at a glance: every READINESS_REPORT_INTERVAL seconds
# and when the timeout is reached it prints the HTTP status, the overall status and name=STATUS of every health component that
# is not UP. busybox wget throws the body of a 503 away, so that part reads the reply with nc and walks the JSON by depth (awk),
# which does not depend on the order of the keys. Only the HTTP status code and tokens matching [A-Za-z0-9_-]+ / [A-Z_]+ are
# printed, never the body itself: nothing else (details, paths, messages, hostnames) can reach the log, even if a service had
# health details switched on.
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

# Prints "status=<X>" for the top-level status and "component=<name>=<X>" for each entry of the top-level "components"
# object whose status is not UP. A string is a key when a colon follows it; `key[depth]` remembers the key of each level.
HEALTH_AWK='
function readstr(   out, c) {
  out = ""
  i++
  while (i <= n) {
    c = substr(s, i, 1)
    if (c == "\\") { out = out c substr(s, i + 1, 1); i += 2; continue }
    if (c == "\"") break
    out = out c
    i++
  }
  return out
}
NR == 1 {
  s = $0; n = length(s); depth = 0
  for (i = 1; i <= n; i++) {
    c = substr(s, i, 1)
    if (c == "{" || c == "[") { depth++; continue }
    if (c == "}" || c == "]") { depth--; continue }
    if (c != "\"") continue
    str = readstr()
    j = i + 1
    while (substr(s, j, 1) == " ") j++
    if (substr(s, j, 1) != ":") continue
    key[depth] = str
    k = j + 1
    while (substr(s, k, 1) == " ") k++
    if (str == "status" && substr(s, k, 1) == "\"") {
      i = k
      val = readstr()
      if (depth == 1) overall = val
      else if (depth == 3 && key[1] == "components") { cn++; cname[cn] = key[2]; cstat[cn] = val }
    }
  }
}
END {
  if (overall ~ /^[A-Z_]+$/) print "status=" overall
  for (m = 1; m <= cn; m++)
    if (cname[m] ~ /^[A-Za-z0-9_-]+$/ && cstat[m] ~ /^[A-Z_]+$/ && cstat[m] != "UP") print "component=" cname[m] "=" cstat[m]
}'

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
  parsed="$(printf '%s\n' "${reply}" | sed '1,/^$/d' | awk "${HEALTH_AWK}" 2>/dev/null || true)"
  overall="$(printf '%s\n' "${parsed}" | sed -n 's|^status=||p' | head -n 1)"
  down="$(printf '%s\n' "${parsed}" | sed -n 's|^component=||p' | tr '\n' ' ')"
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
    # HTTP 200 is UP; the body is deliberately not read (see the header).
    if wget -q -T 3 -O /dev/null "http://${host}:${port}/actuator/health" 2>/dev/null; then
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
