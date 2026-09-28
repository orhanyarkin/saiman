#!/usr/bin/env bash
# Self-test for scripts/check-compose-policy.sh and scripts/check-x402-env.sh: runs each
# script against known-bad and known-good fixtures and checks the exit code (and, for
# the private-key case, that the offending value never appears in the output). Run in CI
# (compose-policy job) so a change to either script's detection logic is itself tested,
# not just the compose file the scripts check.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

FIXTURES_DIR="scripts/testdata/compose-policy"
failures=0

work_dir="$(mktemp -d)"
trap 'rm -rf "${work_dir}"' EXIT
out_file="${work_dir}/stdout"
err_file="${work_dir}/stderr"

expect_exit() {
  local description="$1"
  local expected_exit="$2"
  shift 2
  local actual_exit=0
  "$@" >"${out_file}" 2>"${err_file}" || actual_exit=$?
  if [[ "${actual_exit}" -ne "${expected_exit}" ]]; then
    echo "FAIL: ${description}: expected exit ${expected_exit}, got ${actual_exit}" >&2
    echo "  --- stdout ---" >&2
    sed 's/^/  /' "${out_file}" >&2
    echo "  --- stderr ---" >&2
    sed 's/^/  /' "${err_file}" >&2
    failures=$((failures + 1))
    return 0
  fi
  echo "PASS: ${description}"
}

# --- scripts/check-compose-policy.sh: fixtures that must FAIL ---
for fixture in "${FIXTURES_DIR}"/fail-*.yml; do
  name="$(basename "${fixture}" .yml)"
  expect_exit "check-compose-policy: ${name}" 1 env COMPOSE_FILE="${fixture}" scripts/check-compose-policy.sh
done

# --- scripts/check-compose-policy.sh: fixtures that must PASS ---
for fixture in "${FIXTURES_DIR}"/pass-*.yml; do
  name="$(basename "${fixture}" .yml)"
  expect_exit "check-compose-policy: ${name}" 0 env COMPOSE_FILE="${fixture}" scripts/check-compose-policy.sh
done

# --- scripts/check-compose-policy.sh: the real compose file must PASS ---
expect_exit "check-compose-policy: deploy/compose/docker-compose.yml" 0 scripts/check-compose-policy.sh

# --- scripts/check-x402-env.sh cases ---
expect_exit "check-x402-env: unset" 1 env -u X402_SELLER_PAYTO_ADDRESS scripts/check-x402-env.sh
expect_exit "check-x402-env: zero address" 1 env X402_SELLER_PAYTO_ADDRESS="0x0000000000000000000000000000000000000000" scripts/check-x402-env.sh
expect_exit "check-x402-env: USDC contract address" 1 env X402_SELLER_PAYTO_ADDRESS="0x036CbD53842c5426634e7929541eC2318f3dCF7e" scripts/check-x402-env.sh
expect_exit "check-x402-env: valid address" 0 env X402_SELLER_PAYTO_ADDRESS="0x1234567890123456789012345678901234567890" scripts/check-x402-env.sh

# The 64-hex (private-key-shaped) case gets its own block: besides the exit code, it
# must never print the value it rejected.
fake_key="0xabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd"
key_exit=0
X402_SELLER_PAYTO_ADDRESS="${fake_key}" scripts/check-x402-env.sh >"${out_file}" 2>"${err_file}" || key_exit=$?
if [[ "${key_exit}" -ne 1 ]]; then
  echo "FAIL: check-x402-env: 64-hex value: expected exit 1, got ${key_exit}" >&2
  failures=$((failures + 1))
elif grep -qF "${fake_key}" "${out_file}" "${err_file}"; then
  echo "FAIL: check-x402-env: 64-hex value: the rejected value was printed (must be length-only)" >&2
  failures=$((failures + 1))
else
  echo "PASS: check-x402-env: 64-hex value (rejected, value not printed)"
fi

if [[ "${failures}" -ne 0 ]]; then
  echo "test-check-compose-policy: ${failures} check(s) FAILED" >&2
  exit 1
fi

echo "test-check-compose-policy: all checks passed"
