#!/usr/bin/env bash
# Prepares the compose secret source files that don't exist yet. Never overwrites a file that
# has content and never prints a secret (ADR-0009, ADR-0023, ADR-0024).
#
# 1. Credentials the human supplies (mkk_credentials, openai_api_key, x402_buyer_private_key):
#    created EMPTY (mode 0644 inside the 0700 directory: compose file secrets keep the host owner
#    and mode, and the container user's uid (1002 in the Paketo images) differs from the host
#    user's, so 0600 would be unreadable there and the optional configtree would silently yield
#    empty credentials), so `docker compose up` doesn't fail on a missing bind source on a clean
#    clone. The apps fail closed on an empty credential when they first need it. The buyer key is
#    never generated, derived or copied by this script; while it is empty the orchestrator fails
#    closed at startup (blank key, ADR-0008) and restarts until you fill it; a warning says so on
#    every run.
# 2. Secrets this repo generates, only when the file is missing or empty (random, no newline):
#    - mounted into a container, so 0644 in the 0700 dir for the uid reason above:
#      pg_<svc>_owner_password, pg_<svc>_app_password for orchestrator|ledger|seller_api|ingest
#      (`openssl rand -hex 32`, ADR-0024) and seller_service_token_ledger / seller_service_token_evals
#      (32 random bytes, base64url, ADR-0023);
#    - never mounted anywhere, humans and `make` only, so 0600: api_reader_token, api_operator_token
#      (32 random bytes, base64url, ADR-0023).
#    Rotating one = delete the file and run `make up` (tokens) or `make db-roles` (DB passwords).
set -euo pipefail

readonly CREDENTIAL_NAMES=(mkk_credentials openai_api_key x402_buyer_private_key)
readonly DB_PASSWORD_NAMES=(
  pg_orchestrator_owner_password pg_orchestrator_app_password
  pg_ledger_owner_password pg_ledger_app_password
  pg_seller_api_owner_password pg_seller_api_app_password
  pg_ingest_owner_password pg_ingest_app_password
)
readonly MOUNTED_TOKEN_NAMES=(seller_service_token_ledger seller_service_token_evals)
readonly HUMAN_TOKEN_NAMES=(api_reader_token api_operator_token)
dir="${SECRETS_DIR:-secrets}"

if [[ ! -d "$dir" ]]; then
  (umask 077 && mkdir -p "$dir")
  chmod 700 "$dir"
  echo "ensure-secret-files: created directory ${dir}/ (0700)"
fi

for name in "${CREDENTIAL_NAMES[@]}"; do
  path="${dir}/${name}"
  if [[ ! -e "$path" ]]; then
    : >"$path"
    chmod 644 "$path"
    echo "ensure-secret-files: created empty ${path} (0644 in the 0700 ${dir}/ dir); fill it before using the feature"
  fi
done

# 32 random bytes as base64url without padding: 43 characters of [A-Za-z0-9_-].
new_token() {
  openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n'
}
new_password() {
  openssl rand -hex 32 | tr -d '\n'
}

# generate <path> <mode> <generator>: writes a fresh value only when the file is missing or empty.
generate() {
  local path="$1" mode="$2" generator="$3" value
  if [[ -s "$path" ]]; then
    return 0
  fi
  value="$("$generator")"
  [[ -n "$value" ]] || {
    echo "ensure-secret-files: openssl produced no output for ${path}" >&2
    exit 1
  }
  (umask 077 && printf '%s' "$value" >"$path")
  chmod "$mode" "$path"
  echo "ensure-secret-files: generated ${path} (${mode}, random, not printed)"
}

for name in "${DB_PASSWORD_NAMES[@]}"; do
  generate "${dir}/${name}" 644 new_password
done
for name in "${MOUNTED_TOKEN_NAMES[@]}"; do
  generate "${dir}/${name}" 644 new_token
done
for name in "${HUMAN_TOKEN_NAMES[@]}"; do
  generate "${dir}/${name}" 600 new_token
done

if [[ ! -s "${dir}/x402_buyer_private_key" ]]; then
  echo "ensure-secret-files: WARNING: ${dir}/x402_buyer_private_key is empty; the orchestrator fails closed at startup (blank key, ADR-0008) and stays down until you put a throwaway TESTNET buyer key in it (see README, 'Secrets for M2 (RAG)' / M3 addition; mode 0644 inside the 0700 ${dir}/ dir)" >&2
fi
