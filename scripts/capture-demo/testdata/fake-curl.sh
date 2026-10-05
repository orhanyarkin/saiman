#!/usr/bin/env bash
# Stand-in for `curl` on PATH, used by scripts/capture-demo/test-capture.sh so capture-demo.sh can
# be exercised without a running stack. Understands only what capture-demo.sh passes:
#   curl -fsS --max-time N -H @<header-file> -H 'Accept: ...' -o <file> <url>
# It answers 401 (exit 22, like curl -f) unless the header file carries `Authorization: Bearer
# <contents of $FAKE_CURL_TOKEN_FILE>`, then serves made-up JSON by path. FAKE_CURL_LEAK=1 puts a
# forbidden key into an event so the scrub check must trip. Fixture only; all data is invented.
set -euo pipefail

header_file="" out="" url=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -H)
      [[ "$2" == @* ]] && header_file="${2#@}"
      shift 2
      ;;
    -o)
      out="$2"
      shift 2
      ;;
    --max-time)
      shift 2
      ;;
    -*) shift ;;
    *)
      url="$1"
      shift
      ;;
  esac
done

expected="Authorization: Bearer $(tr -d '\r\n' <"${FAKE_CURL_TOKEN_FILE}")"
[[ -n "${header_file}" && "$(cat "${header_file}")" == "${expected}" ]] || exit 22
[[ "$(stat -c %a "${header_file}")" == "600" ]] || exit 22

path="${url#*://*/}"
path="/${path}"
run=6ad4354c-8e79-4b49-b5d9-d45eb9689b41
pay=3f2b8a40-6c1d-4e0a-9d52-7a1b2c3d4e51
rec=9b1f0c52-3a7e-4d68-8f21-5c0d1e2f3a61
tx=0x68592d03715426a9a3aea48c4f50df430e6e10ad46962d9ab0c7c532a73bb090
usdc='{"atomicUnits":20000,"asset":"USDC","decimals":6}'
event="{\"eventId\":\"${run}:1\",\"runId\":\"${run}\",\"seq\":1,\"type\":\"RUN_STARTED\",\"occurredAt\":\"2026-10-01T10:00:01Z\",\"data\":{\"question\":\"THYAO?\"}}"
if [[ "${FAKE_CURL_LEAK:-}" == "1" ]]; then
  event="{\"eventId\":\"${run}:1\",\"runId\":\"${run}\",\"seq\":1,\"type\":\"RUN_STARTED\",\"occurredAt\":\"2026-10-01T10:00:01Z\",\"data\":{\"nonce\":\"0x$(printf 'ab%.0s' {1..32})\"}}"
fi

case "${path}" in
  /api/v1/ping) body='{"status":"ok"}' ;;
  "/api/v1/runs?limit=20" | "/api/v1/runs?limit=5") body="{\"items\":[{\"runId\":\"${run}\",\"status\":\"SUCCEEDED\"}],\"nextCursor\":null}" ;;
  /api/v1/approvals | "/api/v1/approvals?status=PENDING") body='[]' ;;
  /api/v1/spend) body="{\"day\":\"2026-10-05\",\"dailyCap\":${usdc}}" ;;
  "/api/v1/runs/${run}") body="{\"runId\":\"${run}\",\"status\":\"SUCCEEDED\"}" ;;
  "/api/v1/runs/${run}/payments") body="[{\"paymentId\":\"${pay}\",\"txHash\":\"${tx}\"}]" ;;
  "/api/v1/runs/${run}/events") body="[${event}]" ;;
  "/api/v1/ledger/payments?runId=${run}&limit=50" | "/api/v1/ledger/payments?book=BUYER&limit=20" | "/api/v1/ledger/payments?book=SELLER&limit=20") body='{"items":[],"nextCursor":null}' ;;
  "/api/v1/ledger/payments?limit=20") body="{\"items\":[{\"paymentId\":\"${pay}\",\"buyerTxHash\":\"${tx}\"}],\"nextCursor\":null}" ;;
  "/api/v1/ledger/payments/${pay}") body="{\"paymentId\":\"${pay}\",\"chainTxHash\":\"${tx}\"}" ;;
  /api/v1/ledger/revenue) body="{\"total\":${usdc}}" ;;
  /api/v1/ledger/trial-balance) body='[]' ;;
  "/api/v1/reconciliation/runs?limit=20") body="{\"items\":[{\"runId\":\"${rec}\",\"status\":\"COMPLETED\"}]}" ;;
  "/api/v1/reconciliation/runs/${rec}" | /api/v1/reconciliation/runs/latest) body="{\"runId\":\"${rec}\",\"status\":\"COMPLETED\"}" ;;
  *) exit 22 ;;
esac
printf '%s' "${body}" >"${out}"
