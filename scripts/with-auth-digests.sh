#!/usr/bin/env bash
# Runs a command with the SHA-256 digests of the API tokens exported for docker compose
# interpolation (ADR-0023). The services hold only these digests; the raw tokens stay in secrets/.
# Digests are not secrets, but the raw tokens are: this script never prints them and never
# puts them in an environment variable or argv, it only pipes the file through sha256sum.
#
#   SAIMAN_AUTH_READER_TOKEN_SHA256            <- secrets/api_reader_token       (orchestrator, ledger)
#   SAIMAN_AUTH_OPERATOR_TOKEN_SHA256          <- secrets/api_operator_token     (orchestrator, ledger)
#   SAIMAN_AUTH_SERVICE_TOKENS_LEDGER_SHA256   <- secrets/seller_service_token_ledger  (seller-api)
#   SAIMAN_AUTH_SERVICE_TOKENS_EVALS_SHA256    <- secrets/seller_service_token_evals   (seller-api)
#
# The hash covers the file content without trailing CR/LF, which is what the services see
# (the generator writes no newline; a hand-edited file may carry one).
#
# Usage: scripts/with-auth-digests.sh <command> [args...]
# Test hook: with --print it prints the four NAME=digest lines instead of running a command.
set -euo pipefail

dir="${SECRETS_DIR:-secrets}"

# Known test fixtures must never be real tokens: the literals are read from TestTokens.java at run time.
test_tokens_file="$(dirname "${BASH_SOURCE[0]}")/../libs/api-security/src/testFixtures/java/io/github/orhanyarkin/saiman/apisecurity/testfixtures/TestTokens.java"

digest_of() {
  local file="$1" token digest distinct
  if [[ ! -s "${file}" ]]; then
    echo "with-auth-digests: ${file} is missing or empty (run scripts/ensure-secret-files.sh)" >&2
    return 1
  fi
  token="$(tr -d '\r\n' <"${file}")"
  if [[ ! "${token}" =~ ^[A-Za-z0-9_-]{43,128}$ ]]; then
    echo "with-auth-digests: ${file} is not 43-128 characters of [A-Za-z0-9_-]; delete it and run 'make up' to regenerate" >&2
    return 1
  fi
  distinct="$(fold -w1 <<<"${token}" | sort -u | wc -l)"
  if [[ "${distinct}" -lt 12 ]]; then
    echo "with-auth-digests: ${file} has too little character diversity to be random; delete it and run 'make up' to regenerate" >&2
    return 1
  fi
  digest="$(printf '%s' "${token}" | sha256sum | cut -d' ' -f1)"
  if [[ -r "${test_tokens_file}" ]] && grep -oE '"[A-Za-z0-9_-]{32,}"' "${test_tokens_file}" | tr -d '"' | grep -qxF -e "${token}" -e "${digest}"; then
    echo "with-auth-digests: ${file} equals a known test token; delete it and run 'make up' to regenerate" >&2
    return 1
  fi
  printf '%s\n' "${digest}"
}

reader="$(digest_of "${dir}/api_reader_token")"
operator="$(digest_of "${dir}/api_operator_token")"
svc_ledger="$(digest_of "${dir}/seller_service_token_ledger")"
svc_evals="$(digest_of "${dir}/seller_service_token_evals")"

if [[ "${1:-}" == "--print" ]]; then
  printf 'SAIMAN_AUTH_READER_TOKEN_SHA256=%s\n' "${reader}"
  printf 'SAIMAN_AUTH_OPERATOR_TOKEN_SHA256=%s\n' "${operator}"
  printf 'SAIMAN_AUTH_SERVICE_TOKENS_LEDGER_SHA256=%s\n' "${svc_ledger}"
  printf 'SAIMAN_AUTH_SERVICE_TOKENS_EVALS_SHA256=%s\n' "${svc_evals}"
  exit 0
fi

[[ $# -gt 0 ]] || {
  echo "usage: with-auth-digests.sh <command> [args...]" >&2
  exit 2
}

export SAIMAN_AUTH_READER_TOKEN_SHA256="${reader}"
export SAIMAN_AUTH_OPERATOR_TOKEN_SHA256="${operator}"
export SAIMAN_AUTH_SERVICE_TOKENS_LEDGER_SHA256="${svc_ledger}"
export SAIMAN_AUTH_SERVICE_TOKENS_EVALS_SHA256="${svc_evals}"
exec "$@"
