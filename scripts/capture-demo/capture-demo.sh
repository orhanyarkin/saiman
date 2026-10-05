#!/usr/bin/env bash
# `make capture-demo` (ADR-0026): reads a running stack with the READER token and writes one
# capture file in capture format v1 for the static replay demo:
#
#   { schemaVersion: 1, capturedAt, environment, network, sourceCommit,
#     responses: { "<GET path+query exactly as the SPA requests it>": <body> },
#     runEvents:  { "<runId>": [ <event envelope>, ... ] } }
#
# What is read (GET only): the runs list, each run's summary, payments and ordered event list
# (Accept: application/json, original timestamps), approvals, spend, the ledger payments with
# per-payment details and per-run lists, revenue, trial balance and the reconciliation history.
#
# Safety: the token goes through a 0600 temporary header file (`curl -H @file`), never argv; the
# output is sorted-key JSON (deterministic apart from capturedAt); the file is scrub-checked
# (scrub.sh) BEFORE it is moved to its final path, and a failed check leaves nothing behind.
#
# Environment:
#   ORCH_URL (http://localhost:8080)   LEDGER_URL (http://localhost:8082)
#   RUN_LIMIT (20)                     RECON_LIMIT (20)         PAYMENT_LIMIT (20)
#   CAPTURE_OUT   output path          (default build/capture/capture-<UTC timestamp>.json)
#   CAPTURE_ENVIRONMENT  label stored in the capture (default "local docker compose")
#   SECRETS_DIR (secrets)              reader token: <SECRETS_DIR>/api_reader_token
# Publish for the replay build with: CAPTURE_OUT=web/public/demo/capture.json make capture-demo
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."
# shellcheck source=scripts/capture-demo/scrub.sh
source scripts/capture-demo/scrub.sh

ORCH_URL="${ORCH_URL:-http://localhost:8080}"
LEDGER_URL="${LEDGER_URL:-http://localhost:8082}"
RUN_LIMIT="${RUN_LIMIT:-20}"
RECON_LIMIT="${RECON_LIMIT:-20}"
PAYMENT_LIMIT="${PAYMENT_LIMIT:-20}"
secrets_dir="${SECRETS_DIR:-secrets}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
out="${CAPTURE_OUT:-build/capture/capture-${timestamp}.json}"
environment="${CAPTURE_ENVIRONMENT:-local docker compose}"

for tool in curl jq git; do
  command -v "${tool}" >/dev/null 2>&1 || {
    echo "capture-demo: '${tool}' is required" >&2
    exit 1
  }
done

token_file="${secrets_dir}/api_reader_token"
[[ -s "${token_file}" ]] || {
  echo "capture-demo: ${token_file} is missing or empty (run 'make up' once)" >&2
  exit 1
}

umask 077
work="$(mktemp -d "${TMPDIR:-/tmp}/saiman-capture.XXXXXX")"
trap 'rm -rf "${work}"' EXIT
header_file="${work}/header"
{
  printf 'Authorization: Bearer '
  tr -d '\r\n' <"${token_file}"
  printf '\n'
} >"${header_file}"

responses="${work}/responses.json" # {"<path>": body}
events="${work}/events.json"       # {"<runId>": [...]}
echo '{}' >"${responses}"
echo '{}' >"${events}"

# fetch_to <base-url> <path+query> <file>: body into <file>; non-zero on any non-2xx or transport error.
fetch_to() {
  curl -fsS --max-time 30 -H "@${header_file}" -H 'Accept: application/json' -o "$3" "$1$2"
}

# add_entry <store-file> <key> <body-file>: adds {key: body} (bodies go through files, not argv:
# an event list can exceed the 128 KB single-argument limit).
add_entry() {
  jq --arg k "$2" --slurpfile v "$3" '. + {($k): $v[0]}' "$1" >"$1.new"
  mv "$1.new" "$1"
}

# record <base-url> <path> [required]: stores the body under responses[path] and leaves it in
# last-body (emptied first, so a skipped path leaves nothing stale). A failure is fatal for
# required paths and a warned skip otherwise.
record() {
  local base="$1" path="$2" required="${3:-optional}"
  : >"${work}/last-body"
  if ! fetch_to "${base}" "${path}" "${work}/body"; then
    if [[ "${required}" == "required" ]]; then
      echo "capture-demo: GET ${path} failed (is the stack up and is the token valid?)" >&2
      exit 1
    fi
    echo "capture-demo: skipped ${path} (not available)" >&2
    return 0
  fi
  jq -e . "${work}/body" >/dev/null || {
    echo "capture-demo: GET ${path} did not return JSON" >&2
    exit 1
  }
  add_entry "${responses}" "${path}" "${work}/body"
  cp "${work}/body" "${work}/last-body"
}

echo "capture-demo: reading ${ORCH_URL} and ${LEDGER_URL}"

# --- Orchestrator --------------------------------------------------------------------------
record "${ORCH_URL}" "/api/v1/ping" optional
record "${ORCH_URL}" "/api/v1/runs?limit=${RUN_LIMIT}" required
run_ids="$(jq -r '.items[].runId' "${work}/last-body")"
record "${ORCH_URL}" "/api/v1/runs?limit=5" optional
record "${ORCH_URL}" "/api/v1/approvals" optional
record "${ORCH_URL}" "/api/v1/approvals?status=PENDING" optional
record "${ORCH_URL}" "/api/v1/spend" required

run_count=0
for run_id in ${run_ids}; do
  [[ "${run_id}" =~ ^[0-9a-fA-F-]{36}$ ]] || {
    echo "capture-demo: unexpected run id format, aborting" >&2
    exit 1
  }
  record "${ORCH_URL}" "/api/v1/runs/${run_id}" required
  record "${ORCH_URL}" "/api/v1/runs/${run_id}/payments" optional
  fetch_to "${ORCH_URL}" "/api/v1/runs/${run_id}/events" "${work}/events-body" || {
    echo "capture-demo: events of run ${run_id} not available" >&2
    exit 1
  }
  jq -e 'type == "array"' "${work}/events-body" >/dev/null || {
    echo "capture-demo: events of run ${run_id} are not a JSON array" >&2
    exit 1
  }
  add_entry "${events}" "${run_id}" "${work}/events-body"
  record "${LEDGER_URL}" "/api/v1/ledger/payments?runId=${run_id}&limit=50" optional
  run_count=$((run_count + 1))
done

# --- Ledger and reconciliation -------------------------------------------------------------
record "${LEDGER_URL}" "/api/v1/ledger/payments?limit=${PAYMENT_LIMIT}" required
payment_ids="$(jq -r '.items[].paymentId' "${work}/last-body")"
record "${LEDGER_URL}" "/api/v1/ledger/payments?book=BUYER&limit=${PAYMENT_LIMIT}" optional
record "${LEDGER_URL}" "/api/v1/ledger/payments?book=SELLER&limit=${PAYMENT_LIMIT}" optional
for payment_id in ${payment_ids}; do
  [[ "${payment_id}" =~ ^[0-9a-fA-F-]{36}$ ]] || {
    echo "capture-demo: unexpected payment id format, aborting" >&2
    exit 1
  }
  record "${LEDGER_URL}" "/api/v1/ledger/payments/${payment_id}" optional
done
record "${LEDGER_URL}" "/api/v1/ledger/revenue" optional
record "${LEDGER_URL}" "/api/v1/ledger/trial-balance" optional
record "${LEDGER_URL}" "/api/v1/reconciliation/runs?limit=${RECON_LIMIT}" optional
if [[ -s "${work}/last-body" ]] && recon_ids="$(jq -r '.items[]?.runId' "${work}/last-body" 2>/dev/null)"; then
  for recon_id in ${recon_ids}; do
    [[ "${recon_id}" =~ ^[0-9a-fA-F-]{36}$ ]] || continue
    record "${LEDGER_URL}" "/api/v1/reconciliation/runs/${recon_id}" optional
  done
fi
record "${LEDGER_URL}" "/api/v1/reconciliation/runs/latest" optional

# --- Assemble (sorted keys = deterministic), scrub, then publish ---------------------------
candidate="${work}/capture.json"
jq -S -n \
  --arg capturedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg environment "${environment}" \
  --arg sourceCommit "$(git rev-parse HEAD 2>/dev/null || echo unknown)" \
  --slurpfile responses "${responses}" \
  --slurpfile runEvents "${events}" \
  '{schemaVersion: 1, capturedAt: $capturedAt, environment: $environment, network: "eip155:84532",
    sourceCommit: $sourceCommit, responses: $responses[0], runEvents: $runEvents[0]}' >"${candidate}"

if ! SECRETS_DIR="${secrets_dir}" scrub_check "${candidate}"; then
  echo "capture-demo: scrub check failed; nothing was written" >&2
  exit 1
fi

mkdir -p "$(dirname "${out}")"
umask 022
cp "${candidate}" "${out}"
echo "capture-demo: wrote ${out} (${run_count} runs, $(jq '.responses | length' "${out}") responses)"
