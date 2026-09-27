#!/usr/bin/env bash
# Verify that a distributed trace reached Jaeger, via its stable /api/v3/traces/{id} API.
#
# Mode (a) — an explicit trace id is given (captured from the "System check" card in the
# web UI, per ADR-0006): asserts the trace contains both "saiman-web" and "orchestrator"
# spans, plus at least one JDBC span.
#   scripts/verify-trace.sh <traceId>
#
# Mode (b) — no argument, or an empty argument: generates a random W3C traceparent,
# calls the orchestrator's GET /api/v1/ping with it, then asserts the resulting trace
# contains "orchestrator" and at least one JDBC span (no "saiman-web" requirement,
# since the browser never ran).
#   scripts/verify-trace.sh
#
# Used by `make verify-trace TRACE_ID=<id>` (mode a) and standalone (mode b).
set -euo pipefail

JAEGER_URL="${JAEGER_URL:-http://localhost:16686}"
ORCHESTRATOR_URL="${ORCHESTRATOR_URL:-http://localhost:8080}"
RETRY_ATTEMPTS="${VERIFY_TRACE_RETRIES:-8}"
RETRY_DELAY_SECONDS="${VERIFY_TRACE_RETRY_DELAY:-2}"
CURL_MAX_TIME="${VERIFY_TRACE_CURL_MAX_TIME:-5}"

# Failure reasons are written to files, not shell variables: run_curl and
# fetch_trace_once are called via command substitution (e.g. `x=$(fetch_trace_once
# ...)`), which forks a subshell — any plain variable assignment made inside them
# (e.g. LAST_ERROR=...) would be lost when that subshell exits. Files survive across
# the subshell boundary, so use REASON_FILE for the human-readable "last problem"
# text and CURL_ERR_FILE for curl's raw stderr.
REASON_FILE="$(mktemp)"
CURL_ERR_FILE="$(mktemp)"
trap 'rm -f "${REASON_FILE}" "${CURL_ERR_FILE}"' EXIT

read_reason() {
  cat "${REASON_FILE}" 2>/dev/null || true
}

fail() {
  echo "verify-trace: FAIL: $*" >&2
  exit 1
}

usage_error() {
  echo "usage: $0 [traceId]" >&2
  exit 2
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command '$1' not found on PATH"
}

require_command curl
require_command jq

random_hex() {
  local bytes="$1"
  od -An -tx1 -N "${bytes}" /dev/urandom | tr -d ' \n'
}

# Run curl with the given arguments; on success prints stdout, on failure writes a
# reason to REASON_FILE (see the note above) and returns curl's exit code.
run_curl() {
  local rc=0 out
  out=$(curl --globoff --max-time "${CURL_MAX_TIME}" "$@" 2>"${CURL_ERR_FILE}") || rc=$?
  if [[ ${rc} -ne 0 ]]; then
    printf '%s' "curl exited ${rc}: $(tr -s '\n' ' ' <"${CURL_ERR_FILE}")" >"${REASON_FILE}"
    return "${rc}"
  fi
  printf '%s' "${out}"
}

# Lowercase and validate a trace id: must be exactly 32 hex characters, not all zeros.
# Exits 2 (usage error) rather than 1 (verification failure) on an invalid id, since
# this is a caller mistake, not a trace that failed to verify.
validate_trace_id() {
  local raw="$1" lower
  lower=$(printf '%s' "${raw}" | tr '[:upper:]' '[:lower:]')
  if [[ ! "${lower}" =~ ^[0-9a-f]{32}$ ]]; then
    echo "verify-trace: invalid trace id '${raw}': must be 32 hex characters" >&2
    exit 2
  fi
  if [[ "${lower}" =~ ^0{32}$ ]]; then
    echo "verify-trace: invalid trace id '${raw}': an all-zero trace id is not valid" >&2
    exit 2
  fi
  printf '%s' "${lower}"
}

# Fetch a trace from Jaeger's v3 API once (no retries). Prints the JSON body on a 200
# with at least one resourceSpans entry; otherwise writes a reason to REASON_FILE
# (see the note above run_curl) and returns 1.
fetch_trace_once() {
  local trace_id="$1" response http_status body
  if ! response=$(run_curl -sS -w '\n%{http_code}' "${JAEGER_URL}/api/v3/traces/${trace_id}"); then
    return 1
  fi
  http_status="${response##*$'\n'}"
  body="${response%$'\n'*}"
  if [[ "${http_status}" != "200" ]]; then
    printf '%s' "Jaeger returned HTTP ${http_status}" >"${REASON_FILE}"
    return 1
  fi
  if ! echo "${body}" | jq -e '.result.resourceSpans | length > 0' >/dev/null 2>&1; then
    printf '%s' "trace not yet visible in Jaeger (empty resourceSpans)" >"${REASON_FILE}"
    return 1
  fi
  printf '%s' "${body}"
}

# Assert the trace JSON (passed as $2, not read from stdin) contains a span whose
# resource carries service.name == $2's service argument.
has_service() {
  local trace_json="$1" service="$2"
  jq -e --arg svc "${service}" '
    any(.result.resourceSpans[]?.resource.attributes[]?;
      .key == "service.name" and .value.stringValue == $svc)
  ' <<<"${trace_json}" >/dev/null 2>&1
}

# Assert the trace JSON (passed as $1) contains at least one JDBC span: a span named
# "connection", "query" or "result-set" (datasource-micrometer's actual span names,
# SpanKind CLIENT) or starting with "jdbc", or any span carrying an attribute key that
# starts with "jdbc." (e.g. jdbc.datasource.name, jdbc.query[0]).
has_jdbc_span() {
  local trace_json="$1"
  jq -e '
    any(.result.resourceSpans[]?.scopeSpans[]?.spans[]?;
      ((.name // "") | ascii_downcase) as $name
      | ($name == "connection" or $name == "query" or $name == "result-set"
          or ($name | startswith("jdbc")))
        or any((.attributes // [])[]?; (.key // "") | startswith("jdbc."))
    )
  ' <<<"${trace_json}" >/dev/null 2>&1
}

# Check every required assertion against one fetched trace. Prints nothing on success
# (return 0). On failure, prints the first unmet assertion's description to stdout and
# returns 1, so the retry loop can report the last-seen problem.
check_assertions() {
  local trace_json="$1" require_web="$2"

  if [[ "${require_web}" == "1" ]] && ! has_service "${trace_json}" "saiman-web"; then
    echo "missing service 'saiman-web'"
    return 1
  fi
  if ! has_service "${trace_json}" "orchestrator"; then
    echo "missing service 'orchestrator'"
    return 1
  fi
  if ! has_jdbc_span "${trace_json}"; then
    echo "missing a JDBC span (name 'connection'/'query'/'result-set', or a jdbc.* attribute)"
    return 1
  fi
  return 0
}

# Retry the whole fetch-then-assert cycle (not just the fetch): a trace can be visible
# in Jaeger with only some of its spans ingested while the collector is still batching
# the rest, so a fetch that "succeeds" but fails an assertion deserves another attempt
# too. Reports the last-seen problem (fetch error or missing assertion) on final failure.
verify_with_retries() {
  local trace_id="$1" require_web="$2" pass_label="$3"
  local attempt trace_json reason

  reason="trace never became available in Jaeger"
  for ((attempt = 1; attempt <= RETRY_ATTEMPTS; attempt++)); do
    if trace_json=$(fetch_trace_once "${trace_id}"); then
      if reason=$(check_assertions "${trace_json}" "${require_web}"); then
        [[ "${require_web}" == "1" ]] && echo "verify-trace: found service 'saiman-web'"
        echo "verify-trace: found service 'orchestrator'"
        echo "verify-trace: found a JDBC span"
        echo "verify-trace: PASS (${pass_label}) for trace ${trace_id}"
        return 0
      fi
    else
      reason="$(read_reason)"
    fi
    if [[ ${attempt} -lt ${RETRY_ATTEMPTS} ]]; then
      sleep "${RETRY_DELAY_SECONDS}"
    fi
  done

  fail "trace '${trace_id}' did not verify after ${RETRY_ATTEMPTS} attempts (${RETRY_DELAY_SECONDS}s apart); last problem: ${reason}"
}

mode_explicit_trace_id() {
  local trace_id="$1"
  echo "verify-trace: querying ${JAEGER_URL}/api/v3/traces/${trace_id}"
  verify_with_retries "${trace_id}" 1 "web -> orchestrator -> Postgres"
}

mode_generate_and_ping() {
  local trace_id parent_id traceparent

  trace_id=$(random_hex 16)
  parent_id=$(random_hex 8)
  traceparent="00-${trace_id}-${parent_id}-01"

  echo "verify-trace: calling ${ORCHESTRATOR_URL}/api/v1/ping with traceparent ${traceparent}"
  if ! run_curl -fsS -H "traceparent: ${traceparent}" "${ORCHESTRATOR_URL}/api/v1/ping" >/dev/null; then
    fail "GET ${ORCHESTRATOR_URL}/api/v1/ping failed (is 'make up' running?): $(read_reason)"
  fi

  echo "verify-trace: querying ${JAEGER_URL}/api/v3/traces/${trace_id}"
  verify_with_retries "${trace_id}" 0 "orchestrator -> Postgres"
}

if [[ $# -gt 1 ]]; then
  usage_error
elif [[ $# -eq 1 && -n "$1" ]]; then
  trace_id=$(validate_trace_id "$1")
  mode_explicit_trace_id "${trace_id}"
else
  mode_generate_and_ping
fi
