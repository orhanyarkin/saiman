#!/usr/bin/env bash
# Self-test for the capture scrubber (scrub.sh) and for capture-demo.sh itself (ADR-0026), with
# made-up data and a fake `curl`; no running stack needed. Run by `make capture-demo-selftest`
# and in CI (compose-policy job).
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."
# shellcheck source=scripts/capture-demo/scrub.sh
source scripts/capture-demo/scrub.sh

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT
failures=0
fx="${work}/fixtures"
mkdir -p "${fx}" "${work}/secrets"
chmod 700 "${work}/secrets"
printf '%s' "fixture-token-ABCDEFGHIJKLMNOPQRSTUV" >"${work}/secrets/api_reader_token"

hex64="$(printf 'ab%.0s' {1..32})"
tx="0x${hex64}"

pass() { echo "PASS: $1"; }
fail() {
  echo "FAIL: $1" >&2
  failures=$((failures + 1))
}

# expect_scrub <name> <expected-exit> <json-body> [expected message substring]
expect_scrub() {
  local name="$1" want="$2" body="$3" msg="${4:-}" got=0
  printf '%s' "${body}" >"${fx}/${name}.json"
  SECRETS_DIR="${work}/secrets" scrub_check "${fx}/${name}.json" 2>"${work}/err" || got=$?
  if [[ "${got}" -ne "${want}" ]]; then
    fail "scrub ${name}: expected exit ${want}, got ${got}"
  elif [[ -n "${msg}" ]] && ! grep -qF -- "${msg}" "${work}/err"; then
    fail "scrub ${name}: message lacks \"${msg}\""
  elif grep -qF -- "${hex64}" "${work}/err" || grep -qF "fixture-token" "${work}/err"; then
    fail "scrub ${name}: the offending value was printed"
  else
    pass "scrub ${name}"
  fi
}

expect_scrub clean 0 "{\"txHash\":\"${tx}\",\"amount\":{\"atomicUnits\":20000}}"
expect_scrub nonce 1 '{"data":{"nonce":"x"}}' 'contains "nonce"'
expect_scrub nonce-upper 1 '{"data":{"NONCE":"x"}}' 'contains "nonce"'
expect_scrub signature 1 '{"signature":"x"}' 'contains "signature"'
expect_scrub paymentKey 1 '{"paymentKey":"x"}' 'contains "paymentKey"'
expect_scrub privateKey 1 '{"privateKey":"x"}' 'contains "privateKey"'
expect_scrub bearer 1 '{"h":"Bearer abc"}' 'contains "Bearer"'
expect_scrub bare-hex 1 "{\"k\":\"${hex64}\"}" 'bare 64-hex'
expect_scrub bare-hex-after-text 1 "{\"k\":\"key=${hex64}\"}" 'bare 64-hex'
expect_scrub longer-hex 1 "{\"k\":\"${hex64}${hex64}\"}" 'bare 64-hex'
expect_scrub long-0x 1 "{\"k\":\"0x${hex64}${hex64}\"}" 'longer than a tx hash'
expect_scrub token-content 1 '{"x":"fixture-token-ABCDEFGHIJKLMNOPQRSTUV"}' 'content of'
expect_scrub short-hex-ok 0 '{"id":"6ad4354c-8e79-4b49-b5d9-d45eb9689b41","h":"abcdef0123456789"}'

# --- capture-demo.sh against a fake curl ---------------------------------------------------
fake_bin="${work}/bin"
mkdir -p "${fake_bin}"
cp scripts/capture-demo/testdata/fake-curl.sh "${fake_bin}/curl"
chmod +x "${fake_bin}/curl"
export FAKE_CURL_TOKEN_FILE="${work}/secrets/api_reader_token"

run_capture() { # <out>
  PATH="${fake_bin}:${PATH}" SECRETS_DIR="${work}/secrets" CAPTURE_OUT="$1" \
    scripts/capture-demo/capture-demo.sh >"${work}/out" 2>"${work}/err"
}

rc=0
run_capture "${work}/a.json" || rc=$?
if [[ "${rc}" -ne 0 ]]; then
  fail "capture-demo: happy path exited ${rc}: $(cat "${work}/err")"
else
  if jq -e '.schemaVersion == 1 and .network == "eip155:84532" and (.runEvents | keys | length) == 1
            and (.responses | has("/api/v1/runs?limit=20")) and (.responses | has("/api/v1/spend"))
            and (.responses | has("/api/v1/ledger/payments/3f2b8a40-6c1d-4e0a-9d52-7a1b2c3d4e51"))
            and (.responses | has("/api/v1/reconciliation/runs/latest"))
            and (.runEvents["6ad4354c-8e79-4b49-b5d9-d45eb9689b41"][0].seq == 1)' "${work}/a.json" >/dev/null; then
    pass "capture-demo: capture format v1 with runs, events, ledger and reconciliation"
  else
    fail "capture-demo: unexpected capture content"
  fi
  run_capture "${work}/b.json"
  if [[ "$(jq -S 'del(.capturedAt)' "${work}/a.json")" == "$(jq -S 'del(.capturedAt)' "${work}/b.json")" ]] \
    && [[ "$(jq -r 'keys_unsorted | join(",")' "${work}/a.json")" == "$(jq -r 'keys | join(",")' "${work}/a.json")" ]]; then
    pass "capture-demo: deterministic apart from capturedAt (sorted keys)"
  else
    fail "capture-demo: two captures differ"
  fi
  if grep -qF "fixture-token" "${work}/a.json"; then
    fail "capture-demo: token found in the capture"
  fi
fi

# A leak (forbidden key in an event) must fail and leave no output file.
rc=0
FAKE_CURL_LEAK=1 run_capture "${work}/leak.json" || rc=$?
if [[ "${rc}" -ne 0 && ! -e "${work}/leak.json" ]] && grep -qF 'scrub check failed' "${work}/err"; then
  pass "capture-demo: a forbidden key aborts the capture and writes nothing"
else
  fail "capture-demo: a forbidden key did not abort the capture (rc=${rc})"
fi

# A wrong token (401 -> curl exit 22) must fail.
printf '%s' "some-other-token-0123456789abcdef" >"${work}/other"
rc=0
FAKE_CURL_TOKEN_FILE="${work}/other" run_capture "${work}/bad.json" || rc=$?
if [[ "${rc}" -ne 0 && ! -e "${work}/bad.json" ]]; then
  pass "capture-demo: a rejected token fails without output"
else
  fail "capture-demo: a rejected token did not fail"
fi

if [[ "${failures}" -ne 0 ]]; then
  echo "capture-demo self-test: ${failures} check(s) FAILED" >&2
  exit 1
fi
echo "capture-demo self-test: all checks passed"
