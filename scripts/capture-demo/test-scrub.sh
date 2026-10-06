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

export ENV_FILE="${work}/absent.env"
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
expect_scrub token-content 1 '{"x":"fixture-token-ABCDEFGHIJKLMNOPQRSTUV"}' 'value from a secrets file'
printf 'first-line-short\nsecond-secret-line-ABCDEFGHIJ\n' >"${work}/secrets/multi_line"
expect_scrub multiline-secret 1 '{"x":"second-secret-line-ABCDEFGHIJ"}' 'value from a secrets file'
printf 'export PLANTED_ENV="env-planted-value-0123456789"  # c\nOTHER=short\n' >"${work}/fixture.env"
got=0
printf '%s' '{"x":"env-planted-value-0123456789"}' >"${fx}/env.json"
ENV_FILE="${work}/fixture.env" SECRETS_DIR="${work}/secrets" scrub_check "${fx}/env.json" 2>"${work}/err" || got=$?
if [[ "${got}" -eq 1 ]] && ! grep -qF "env-planted" "${work}/err"; then pass "scrub env-file value"; else fail "scrub env-file value (rc=${got})"; fi
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

# Run selection, annotations and the corpus snapshot (ADR-0026).
run_id="6ad4354c-8e79-4b49-b5d9-d45eb9689b41"
printf '{"%s":{"label":"Failed run","detail":"The facilitator refused the settlement.","tone":"warning"}}' "${run_id}" >"${work}/annotations.json"
rc=0
CAPTURE_RUN_IDS="${run_id}" CAPTURE_ANNOTATIONS_FILE="${work}/annotations.json" CAPTURE_CORPUS_WATERMARK="2023-12-29T20:46:52Z" \
  run_capture "${work}/sel.json" || rc=$?
if [[ "${rc}" -eq 0 ]] && jq -e --arg id "${run_id}" '.annotations[$id].tone == "warning"
    and .corpus.newestDisclosureAt == "2023-12-29T20:46:52Z"
    and (.corpus.snapshotLabel | contains("2023-12-29"))
    and (.responses["/api/v1/runs?limit=20"].items | map(.runId) == [$id])' "${work}/sel.json" >/dev/null; then
  pass "capture-demo: selected runs, annotations and the corpus snapshot are stored"
else
  fail "capture-demo: run selection / annotations / corpus (rc=${rc}): $(cat "${work}/err")"
fi
rc=0
CAPTURE_RUN_IDS="00000000-0000-0000-0000-000000000000" run_capture "${work}/sel2.json" || rc=$?
if [[ "${rc}" -ne 0 && ! -e "${work}/sel2.json" ]]; then
  pass "capture-demo: an unknown run id in CAPTURE_RUN_IDS fails without output"
else
  fail "capture-demo: an unknown run id was accepted (rc=${rc})"
fi
printf '{"%s":{"label":"x","detail":"y","tone":"loud"}}' "${run_id}" >"${work}/bad-annotations.json"
rc=0
CAPTURE_ANNOTATIONS_FILE="${work}/bad-annotations.json" run_capture "${work}/sel3.json" || rc=$?
if [[ "${rc}" -ne 0 && ! -e "${work}/sel3.json" ]]; then
  pass "capture-demo: a malformed annotations file fails without output"
else
  fail "capture-demo: malformed annotations were accepted (rc=${rc})"
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

# --- scripts/run-evals.sh publishing rules (no stack: EVAL_RUN_CMD stands in for the container) ---
ev_out="${work}/ev-out"
ev_pub="${work}/ev-pub"
run_evals() { # <shell command that plays the container>
  EVAL_RUN_CMD="$1" EVAL_OUT_DIR="${ev_out}" EVAL_PUBLISH_DIR="${ev_pub}" SECRETS_DIR="${work}/secrets" \
    scripts/run-evals.sh >"${work}/out" 2>"${work}/err"
}
rc=0
run_evals "printf '# report\n' >'${ev_out}/latest.md'; printf '{\"recall\":0.9}' >'${ev_out}/latest.json'" || rc=$?
if [[ "${rc}" -eq 0 && -f "${ev_pub}/latest.md" && -f "${ev_pub}/latest.json" && "$(ls "${ev_pub}/runs" | wc -l)" -eq 1 ]]; then
  pass "run-evals: clean reports are published"
else
  fail "run-evals: clean reports not published (rc=${rc}): $(cat "${work}/err")"
fi
rm -rf "${ev_pub}"
printf 'host-file-content-that-must-not-be-copied\n' >"${work}/outside.txt"
rc=0
run_evals "ln -s '${work}/outside.txt' '${ev_out}/latest.md'; printf '{}' >'${ev_out}/latest.json'" || rc=$?
if [[ "${rc}" -ne 0 && ! -e "${ev_pub}/latest.md" && ! -e "${ev_pub}/latest.json" ]] && grep -qF 'symlink' "${work}/err"; then
  pass "run-evals: a planted symlink latest.md is rejected and nothing is copied"
else
  fail "run-evals: planted symlink not rejected (rc=${rc})"
fi
rc=0
run_evals "printf 'x' >'${ev_out}/latest.md'; printf '{\"k\":\"second-secret-line-ABCDEFGHIJ\"}' >'${ev_out}/latest.json'" || rc=$?
if [[ "${rc}" -ne 0 && ! -e "${ev_pub}/latest.json" ]] && grep -qF 'scrub check failed' "${work}/err"; then
  pass "run-evals: a report containing a secret is rejected and nothing is copied"
else
  fail "run-evals: secret in report not rejected (rc=${rc})"
fi
rc=0
run_evals "printf 'stale' >'${ev_out}/stale.md'; true" || rc=$?
if [[ "${rc}" -eq 0 ]]; then pass "run-evals: (stale file from the container is a report like any other)"; fi
rm -rf "${ev_pub}"
printf 'old' >"${ev_out}/old.md"; ln -s "${work}/outside.txt" "${ev_out}/old-link"
rc=0
run_evals "true" || rc=$?
if [[ "${rc}" -ne 0 && ! -e "${ev_out}/old.md" && ! -L "${ev_out}/old-link" ]]; then
  pass "run-evals: stale files and symlinks are cleared before the run"
else
  fail "run-evals: stale output survived (rc=${rc})"
fi

if [[ "${failures}" -ne 0 ]]; then
  echo "capture-demo self-test: ${failures} check(s) FAILED" >&2
  exit 1
fi
echo "capture-demo self-test: all checks passed"
