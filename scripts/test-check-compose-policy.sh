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
  # Every fail fixture names the violation it must trigger ("# expect: <substring>"), so a
  # fixture can't pass for an unrelated reason (e.g. a YAML error).
  expected="$(sed -n 's/^# expect: //p' "${fixture}")"
  if [[ -z "${expected}" ]]; then
    echo "FAIL: ${name}: fixture has no '# expect:' line" >&2
    failures=$((failures + 1))
  elif ! grep -qF -- "${expected}" "${err_file}"; then
    echo "FAIL: ${name}: output lacks the expected violation \"${expected}\"" >&2
    sed 's/^/  /' "${err_file}" >&2
    failures=$((failures + 1))
  fi
done

# --- scripts/check-compose-policy.sh: fixtures that must PASS ---
for fixture in "${FIXTURES_DIR}"/pass-*.yml; do
  name="$(basename "${fixture}" .yml)"
  expect_exit "check-compose-policy: ${name}" 0 env COMPOSE_FILE="${fixture}" scripts/check-compose-policy.sh
done

# --- scripts/check-compose-policy.sh: the real compose file must PASS ---
expect_exit "check-compose-policy: deploy/compose/docker-compose.yml" 0 scripts/check-compose-policy.sh

# --- scripts/check-nginx-conf.sh (the dashboard's nginx.conf rules, ADR-0022) ---
NGINX_FIXTURES_DIR="scripts/testdata/nginx-conf"
for fixture in "${NGINX_FIXTURES_DIR}"/fail-*.conf; do
  name="$(basename "${fixture}" .conf)"
  expect_exit "check-nginx-conf: ${name}" 1 env NGINX_CONF="${fixture}" scripts/check-nginx-conf.sh
  expected="$(sed -n 's/^# expect: //p' "${fixture}")"
  if [[ -z "${expected}" ]]; then
    echo "FAIL: ${name}: fixture has no '# expect:' line" >&2
    failures=$((failures + 1))
  elif ! grep -qF -- "${expected}" "${err_file}"; then
    echo "FAIL: ${name}: output lacks the expected violation \"${expected}\"" >&2
    sed 's/^/  /' "${err_file}" >&2
    failures=$((failures + 1))
  fi
done
for fixture in "${NGINX_FIXTURES_DIR}"/pass-*.conf; do
  name="$(basename "${fixture}" .conf)"
  expect_exit "check-nginx-conf: ${name}" 0 env NGINX_CONF="${fixture}" scripts/check-nginx-conf.sh
done
expect_exit "check-nginx-conf: deploy/compose/nginx.conf" 0 scripts/check-nginx-conf.sh

# --- scripts/prepare-ingest-secrets.sh: only the two ingest secrets, never buyer.key ---
ingest_secrets_src="${work_dir}/secrets-src"
ingest_secrets_dest="${work_dir}/ingest-secrets"
mkdir -p "${ingest_secrets_src}" "${ingest_secrets_dest}"
for f in mkk_credentials openai_api_key buyer.key other.txt; do
  echo "placeholder" >"${ingest_secrets_src}/${f}"
done
echo "stale" >"${ingest_secrets_dest}/buyer.key"
expect_exit "prepare-ingest-secrets: runs" 0 scripts/prepare-ingest-secrets.sh "${ingest_secrets_src}" "${ingest_secrets_dest}"
listing="$(ls -A "${ingest_secrets_dest}" | sort | tr '\n' ' ')"
if [[ "${listing}" == "mkk_credentials openai_api_key " && "$(stat -c '%a' "${ingest_secrets_dest}")" == "700" ]]; then
  echo "PASS: prepare-ingest-secrets: exactly mkk_credentials + openai_api_key in a 0700 dir (stale entries purged)"
else
  echo "FAIL: prepare-ingest-secrets: unexpected directory content '${listing}'" >&2
  failures=$((failures + 1))
fi
if grep -q "prepare-ingest-secrets.sh" Makefile && ! grep -E "secrets-dir=.*/secrets/?[\" ]" Makefile >/dev/null; then
  echo "PASS: Makefile: ingest-backfill uses the per-run secrets dir"
else
  echo "FAIL: Makefile: ingest-backfill must not point --saiman.secrets-dir at the whole secrets/" >&2
  failures=$((failures + 1))
fi

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

# read-public-env.sh (used by the Makefile to take the payTo address from .env): reads only
# allowlisted public keys, handles export/quotes/comments, last assignment wins.
env_fixture="scripts/testdata/read-public-env/sample.env.txt"
payto="$(scripts/read-public-env.sh X402_SELLER_PAYTO_ADDRESS "${env_fixture}")"
if [[ "${payto}" == "0x1111111111111111111111111111111111111111" ]]; then
  echo "PASS: read-public-env: reads the last payTo assignment"
else
  echo "FAIL: read-public-env: unexpected payTo value" >&2
  failures=$((failures + 1))
fi
expect_exit "read-public-env: refuses a non-allowlisted key" 2 scripts/read-public-env.sh X402_BUYER_PRIVATE_KEY "${env_fixture}"
expect_exit "read-public-env: missing file prints nothing" 0 scripts/read-public-env.sh X402_SELLER_PAYTO_ADDRESS scripts/testdata/read-public-env/absent.env.txt
if env -u X402_SELLER_PAYTO_ADDRESS make -s -p -n help ENV_FILE="${env_fixture}" 2>/dev/null | grep -q "placeholder-not-a-key"; then
  echo "FAIL: Makefile: a non-allowlisted .env value reached make's database" >&2
  failures=$((failures + 1))
else
  echo "PASS: Makefile: only the payTo address is read from the env file"
fi

# --- scripts/secrets-from-dotenv.sh and ensure-secret-files.sh (placeholder values only) ---
sfd_dir="${work_dir}/sfd"
mkdir -p "${sfd_dir}"
placeholder="sk-placeholder-NOT-A-REAL-KEY-0123456789"
printf '%s\n' "# comment" "OTHER=leave-me-out" "export OPENAI_API_KEY=\"${placeholder}\"  # trailing" >"${sfd_dir}/good.env"
printf '%s\n' "OPENAI_API_KEY=has spaces inside-${placeholder}" >"${sfd_dir}/spaces.env"
printf '%s\n' "OPENAI_API_KEY=" >"${sfd_dir}/empty.env"

no_leak() { # description, then checks that out/err never contain the placeholder
  if grep -qF "${placeholder}" "${out_file}" "${err_file}"; then
    echo "FAIL: $1: the value appeared in stdout/stderr" >&2
    failures=$((failures + 1))
  else
    echo "PASS: $1 (value not printed)"
  fi
}

expect_exit "secrets-from-dotenv: writes the key" 0 env ENV_FILE="${sfd_dir}/good.env" SECRETS_DIR="${sfd_dir}/s" scripts/secrets-from-dotenv.sh
no_leak "secrets-from-dotenv: success output"
if [[ "$(cat "${sfd_dir}/s/openai_api_key")" == "${placeholder}" && "$(stat -c %a "${sfd_dir}/s/openai_api_key")" == "644" && "$(stat -c %a "${sfd_dir}/s")" == "700" ]] \
  && grep -q "written (${#placeholder} bytes)" "${out_file}"; then
  echo "PASS: secrets-from-dotenv: trimmed value, mode 0644/0700, only the byte count printed"
else
  echo "FAIL: secrets-from-dotenv: unexpected file content, mode or output" >&2
  failures=$((failures + 1))
fi
expect_exit "secrets-from-dotenv: refuses to overwrite without FORCE" 1 env ENV_FILE="${sfd_dir}/good.env" SECRETS_DIR="${sfd_dir}/s" scripts/secrets-from-dotenv.sh
no_leak "secrets-from-dotenv: overwrite refusal"
expect_exit "secrets-from-dotenv: FORCE=1 overwrites" 0 env FORCE=1 ENV_FILE="${sfd_dir}/good.env" SECRETS_DIR="${sfd_dir}/s" scripts/secrets-from-dotenv.sh
expect_exit "secrets-from-dotenv: value with whitespace is rejected" 1 env ENV_FILE="${sfd_dir}/spaces.env" SECRETS_DIR="${sfd_dir}/s2" scripts/secrets-from-dotenv.sh
no_leak "secrets-from-dotenv: whitespace rejection"
if [[ -e "${sfd_dir}/s2/openai_api_key" ]] || compgen -G "${sfd_dir}/s2/.openai_api_key.*" >/dev/null; then
  echo "FAIL: secrets-from-dotenv: rejected run left a file behind" >&2
  failures=$((failures + 1))
else
  echo "PASS: secrets-from-dotenv: rejected run leaves no file"
fi
expect_exit "secrets-from-dotenv: empty value is rejected" 1 env ENV_FILE="${sfd_dir}/empty.env" SECRETS_DIR="${sfd_dir}/s3" scripts/secrets-from-dotenv.sh
expect_exit "secrets-from-dotenv: missing env file is rejected" 1 env ENV_FILE="${sfd_dir}/absent.env" SECRETS_DIR="${sfd_dir}/s4" scripts/secrets-from-dotenv.sh

esf_dir="${sfd_dir}/e"
expect_exit "ensure-secret-files: creates missing files" 0 env SECRETS_DIR="${esf_dir}" scripts/ensure-secret-files.sh
printf 'keep' >"${esf_dir}/mkk_credentials"
expect_exit "ensure-secret-files: second run is a no-op" 0 env SECRETS_DIR="${esf_dir}" scripts/ensure-secret-files.sh
if [[ ! -s "${esf_dir}/openai_api_key" && "$(stat -c %a "${esf_dir}/openai_api_key")" == "644" && "$(cat "${esf_dir}/mkk_credentials")" == "keep" && ! -s "${out_file}" ]]; then
  echo "PASS: ensure-secret-files: empty 0644 files, never overwrites existing content"
else
  echo "FAIL: ensure-secret-files: unexpected state after second run" >&2
  failures=$((failures + 1))
fi
expect_exit "secrets-check: reports without contents" 0 env SECRETS_DIR="${esf_dir}" scripts/secrets-check.sh
if grep -q "keep" "${out_file}"; then
  echo "FAIL: secrets-check printed file contents" >&2
  failures=$((failures + 1))
fi

if [[ "${failures}" -ne 0 ]]; then
  echo "test-check-compose-policy: ${failures} check(s) FAILED" >&2
  exit 1
fi

echo "test-check-compose-policy: all checks passed"
