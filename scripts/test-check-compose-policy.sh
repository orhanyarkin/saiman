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

# --- scripts/check-compose-policy.sh: fixtures generated from pass-m6b-migrators.yml by a jq mutation ---
# Each row: name, a jq filter applied to the (valid) base, then one or more substrings the output
# must contain. Keeps the base in one file instead of one copy per case (ADR-0027).
MUTATION_BASE="${FIXTURES_DIR}/pass-m6b-migrators.yml"
mutation_dir="${work_dir}/mutations"
mkdir -p "${mutation_dir}"
mutation_fail() {
  local name="$1" filter="$2"
  shift 2
  local fixture="${mutation_dir}/fail-${name}.yml"
  if ! sed '/^#/d' "${MUTATION_BASE}" | jq "${filter}" >"${fixture}"; then
    echo "FAIL: mutation ${name}: the jq filter does not apply to the base fixture" >&2
    failures=$((failures + 1))
    return 0
  fi
  expect_exit "check-compose-policy (mutation): ${name}" 1 env COMPOSE_FILE="${fixture}" scripts/check-compose-policy.sh
  local expected
  for expected in "$@"; do
    if ! grep -qF -- "${expected}" "${err_file}"; then
      echo "FAIL: mutation ${name}: output lacks the expected violation \"${expected}\"" >&2
      sed 's/^/  /' "${err_file}" >&2
      failures=$((failures + 1))
    fi
  done
}

M='.services["ledger-migrate"]'
mutation_fail migrate-no-profile "del(${M}.profiles)" 'ledger-migrate: profiles must be exactly [apps]'
mutation_fail migrate-other-profile "${M}.profiles = [\"migrate\"]" 'ledger-migrate: profiles must be exactly [apps]'
mutation_fail migrate-no-profile-no-run-mode \
  "del(${M}.profiles) | del(${M}.environment.SAIMAN_RUN_MODE) | ${M}.restart = \"always\" | ${M}.command = [\"sleep\", \"1\"] | ${M}.environment.SPRING_FLYWAY_URL = \"jdbc:postgresql://evil/x\"" \
  'ledger-migrate: profiles must be exactly [apps]' 'ledger-migrate: SAIMAN_RUN_MODE must be "migrate"' 'ledger-migrate: restart must be "no"' \
  'ledger-migrate: command/entrypoint overrides are not allowed' 'ledger-migrate: environment SPRING_FLYWAY_URL is not allowed'
mutation_fail migrate-unprofiled-extra-key "del(${M}.profiles) | ${M}.user = \"0\"" 'ledger-migrate: key user is not allowed on a migrate one-shot'
mutation_fail migrate-extra-hosts "${M}.extra_hosts = [\"evil:10.0.0.1\"]" 'ledger-migrate: key extra_hosts is not allowed on a migrate one-shot'
mutation_fail migrate-configs "${M}.configs = [\"x\"]" 'ledger-migrate: key configs is not allowed on a migrate one-shot'
mutation_fail migrate-volume "${M}.volumes = [\"scratch:/tmp\"]" 'ledger-migrate: key volumes is not allowed on a migrate one-shot'
mutation_fail migrate-double-underscore-flyway-url "${M}.environment.SPRING_FLYWAY__URL = \"jdbc:postgresql://evil/x\"" \
  'ledger-migrate: environment SPRING_FLYWAY_URL is not allowed' 'is not UPPER_SNAKE_CASE'
mutation_fail migrate-shadowed-key "${M}.environment.SPRING_FLYWAY__USER = \"saiman\"" 'collapse to the same name after normalisation'
mutation_fail app-no-profile 'del(.services.ledger.profiles)' 'ledger: has no profile and is not an always-on infrastructure service'
mutation_fail unknown-service-no-profile '.services.rogue = {image: "busybox:1"}' 'rogue: has no profile and is not an always-on infrastructure service'
mutation_fail app-extra-profile '.services.ledger.profiles = ["apps", "debug"] | .services.ledger.environment.SPRING_FLYWAY_ENABLED = "true"' \
  'ledger: SPRING_FLYWAY_ENABLED must be "false"'
mutation_fail app-extra-profile-no-migrator-dependency '.services.ledger.profiles = ["apps", "debug"] | del(.services.ledger.depends_on)' \
  'ledger: must depend on ledger-migrate with condition service_completed_successfully'
mutation_fail app-volumes-from-migrator '.services.ledger.volumes_from = ["ledger-migrate"]' 'ledger: volumes_from is not allowed'
mutation_fail volumes-from-any '.services.evals.volumes_from = ["postgres"]' 'evals: volumes_from is not allowed'
mutation_fail app-named-volume-bind-secrets \
  '.volumes = {secvol: {driver: "local", driver_opts: {type: "none", o: "bind", device: "../../secrets"}}} | .services.ledger.volumes = ["secvol:/mnt/s:ro"]' \
  'ledger: named volume secvol is bound to a host path'
mutation_fail app-privileged '.services.ledger.privileged = true' 'ledger: privileged is not allowed'
mutation_fail app-cap-add '.services.ledger.cap_add = ["NET_RAW"]' 'ledger: cap_add is not allowed'
mutation_fail app-devices '.services.ledger.devices = ["/dev/sda:/dev/sda"]' 'ledger: devices is not allowed'
mutation_fail app-security-opt '.services.ledger.security_opt = ["seccomp=unconfined"]' 'ledger: security_opt is not allowed'
mutation_fail app-pid-host '.services.ledger.pid = "host"' 'ledger: pid is not allowed'
mutation_fail app-ipc-host '.services.ledger.ipc = "host"' 'ledger: ipc is not allowed'
mutation_fail app-network-mode-host '.services.ledger.network_mode = "host"' 'ledger: network_mode is not allowed'
mutation_fail app-userns-mode '.services.ledger.userns_mode = "host"' 'ledger: userns_mode is not allowed'
mutation_fail migrate-privileged "${M}.privileged = true" 'ledger-migrate: privileged is not allowed'
mutation_fail app-inject-double-underscore-config-import '.services.ledger.environment.SPRING__CONFIG_IMPORT = "file:/x"' 'ledger: environment defines SPRING_CONFIG_IMPORT'
mutation_fail app-inject-double-underscore-config-import-2 '.services.ledger.environment.SPRING_CONFIG__IMPORT = "file:/x"' 'ledger: environment defines SPRING_CONFIG_IMPORT'
mutation_fail app-inject-indexed-autoconfigure-exclude '.services.ledger.environment.SPRING_AUTOCONFIGURE_EXCLUDE_0 = "x"' 'ledger: environment SPRING_AUTOCONFIGURE_EXCLUDE is not allowed'
mutation_fail app-secrets-dir '.services.ledger.environment.SAIMAN_SECRETS_DIR = "/tmp/x"' 'ledger: environment defines SAIMAN_SECRETS_DIR'
mutation_fail app-bpl-debug '.services.ledger.environment.BPL_DEBUG_ENABLED = "true"' 'ledger: environment defines BPL_DEBUG_ENABLED'
mutation_fail app-bpl-jmx '.services.ledger.environment.BPL_JMX_ENABLED = "true"' 'ledger: environment defines BPL_JMX_ENABLED'
mutation_fail migrate-secrets-dir "${M}.environment.SAIMAN_SECRETS_DIR = \"/tmp/x\"" 'ledger-migrate: environment defines SAIMAN_SECRETS_DIR'

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

# --- M6: generated secrets, digests, ingest backfill secrets (ADR-0023/0024) ---
gen_dir="${work_dir}/gen"
expect_exit "ensure-secret-files: generates DB passwords and tokens" 0 env SECRETS_DIR="${gen_dir}" scripts/ensure-secret-files.sh
tok="$(cat "${gen_dir}/api_reader_token")"
if [[ "${tok}" =~ ^[A-Za-z0-9_-]{43}$ && "$(stat -c %a "${gen_dir}/api_reader_token")" == "600" \
  && "$(stat -c %a "${gen_dir}/seller_service_token_ledger")" == "644" \
  && "$(cat "${gen_dir}/pg_ledger_app_password")" =~ ^[0-9a-f]{64}$ ]]; then
  echo "PASS: ensure-secret-files: token 43 base64url chars (0600 human / 0644 mounted), password 64 hex"
else
  echo "FAIL: ensure-secret-files: generated secrets have the wrong shape or mode" >&2
  failures=$((failures + 1))
fi
if grep -qF "${tok}" "${out_file}" "${err_file}"; then
  echo "FAIL: ensure-secret-files printed a token" >&2
  failures=$((failures + 1))
fi
before="$(cat "${gen_dir}"/pg_* "${gen_dir}"/api_* "${gen_dir}"/seller_service_token_* | sha256sum)"
expect_exit "ensure-secret-files: rerun generates nothing" 0 env SECRETS_DIR="${gen_dir}" scripts/ensure-secret-files.sh
after="$(cat "${gen_dir}"/pg_* "${gen_dir}"/api_* "${gen_dir}"/seller_service_token_* | sha256sum)"
if [[ "${before}" == "${after}" && ! -s "${out_file}" ]]; then
  echo "PASS: ensure-secret-files: existing generated secrets are kept and the rerun is silent"
else
  echo "FAIL: ensure-secret-files: rerun changed or printed something" >&2
  failures=$((failures + 1))
fi
digests="$(SECRETS_DIR="${gen_dir}" scripts/with-auth-digests.sh --print)"
want="$(tr -d '\r\n' <"${gen_dir}/api_reader_token" | sha256sum | cut -d' ' -f1)"
if grep -qx "SAIMAN_AUTH_READER_TOKEN_SHA256=${want}" <<<"${digests}" && ! grep -qF "${tok}" <<<"${digests}"; then
  echo "PASS: with-auth-digests: SHA-256 of the token file, token not printed"
else
  echo "FAIL: with-auth-digests: unexpected digests" >&2
  failures=$((failures + 1))
fi
ing_dest="${work_dir}/ing2"
scripts/prepare-ingest-secrets.sh "${gen_dir}" "${ing_dest}" 2>/dev/null
touch "${gen_dir}/mkk_credentials"
if [[ -f "${ing_dest}/spring.datasource.password" && -f "${ing_dest}/spring.flyway.password" && ! -e "${ing_dest}/api_reader_token" && ! -e "${ing_dest}/pg_ledger_app_password" ]]; then
  echo "PASS: prepare-ingest-secrets: ingest DB passwords mapped to spring.* properties, nothing else"
else
  echo "FAIL: prepare-ingest-secrets: unexpected ingest secrets dir" >&2
  failures=$((failures + 1))
fi

# --- with-auth-digests.sh rejects weak or known tokens (ADR-0023) ---
bad_dir="${work_dir}/badtok"
cp -r "${gen_dir}" "${bad_dir}"
for case in "short:abc123" "lowdiversity:$(printf 'ab%.0s' {1..30})" "badchars:$(printf 'a-b_c%.0s' {1..10})!!!!!!!!!!" "testtoken:saiman-test-reader-token-0123456789abcdefghij"; do
  name="${case%%:*}"
  printf '%s' "${case#*:}" >"${bad_dir}/api_reader_token"
  expect_exit "with-auth-digests: rejects ${name} token" 1 env SECRETS_DIR="${bad_dir}" scripts/with-auth-digests.sh --print
  if grep -qF "${case#*:}" "${out_file}" "${err_file}"; then
    echo "FAIL: with-auth-digests: ${name} token was printed" >&2
    failures=$((failures + 1))
  fi
done

# --- with-auth-digests.sh never puts a token in argv (stub wrappers log every argv) ---
stub_bin="${work_dir}/stubbin"
argv_log="${work_dir}/argv.log"
mkdir -p "${stub_bin}"
: >"${argv_log}"
for tool in grep fold sort tr cut sha256sum wc mktemp; do
  real="$(command -v "${tool}")"
  printf '#!/usr/bin/env bash\nprintf "%%s\\n" "$0 $*" >>"%s"\nexec "%s" "$@"\n' "${argv_log}" "${real}" >"${stub_bin}/${tool}"
  chmod +x "${stub_bin}/${tool}"
done
PATH="${stub_bin}:${PATH}" SECRETS_DIR="${gen_dir}" scripts/with-auth-digests.sh --print >/dev/null
leaked=0
for f in api_reader_token api_operator_token seller_service_token_ledger seller_service_token_evals; do
  grep -qF "$(cat "${gen_dir}/${f}")" "${argv_log}" && leaked=1
done
if [[ -s "${argv_log}" && "${leaked}" -eq 0 ]]; then
  echo "PASS: with-auth-digests: no token in the argv of any helper process (${argv_log##*/} had $(wc -l <"${argv_log}") calls)"
else
  echo "FAIL: with-auth-digests: a token appeared in argv (or the stubs saw nothing)" >&2
  failures=$((failures + 1))
fi
expect_exit "curl-auth: refuses -v" 2 scripts/curl-auth.sh reader -v http://localhost:1/
expect_exit "curl-auth: refuses a -sSv cluster" 2 scripts/curl-auth.sh reader -sSv http://localhost:1/
expect_exit "curl-auth: refuses -D-" 2 scripts/curl-auth.sh operator -D- http://localhost:1/
expect_exit "capture-demo self-test" 0 scripts/capture-demo/test-scrub.sh

if [[ "${failures}" -ne 0 ]]; then
  echo "test-check-compose-policy: ${failures} check(s) FAILED" >&2
  exit 1
fi

echo "test-check-compose-policy: all checks passed"
