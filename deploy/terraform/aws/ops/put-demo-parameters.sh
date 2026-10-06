#!/usr/bin/env bash
# Writes the demo's SSM parameters under /saiman/demo/ from the secret files in <secrets-dir> (ADR-0028).
# SecureStrings (AWS-managed aws/ssm key), `--overwrite`, values only via `--value file://...`: a secret is
# never an argv element, never in the environment of the aws process and never printed.
#
# The ECS task definition (demo-lite/containers.tf) injects exactly the PARAMETERS below as secrets[].valueFrom;
# ops/check-ssm-names.sh diffs this list against containers.tf so the two cannot drift.
#
#   pg_superuser_password (file)  ->  /saiman/demo/pg_master_password   (the RDS master, same value as TF_VAR_db_master_password)
#   <name> (file)                 ->  /saiman/demo/<name>               (every other parameter)
#   api_reader_token, api_operator_token: stored for `make demo-capture` / the human (ops/demo-tokens.sh);
#   no container reads them (the services hold only their SHA-256 digests).
#   expires-at (String, not secret) + an `expires-at` tag: see ops/demo-expiry.sh for why the tag.
#
# Usage: put-demo-parameters.sh --region eu-central-1 --expires-at <RFC 3339 UTC> <secrets-dir>
set -euo pipefail

# parameter name <- secret file name. Keep the container-injected names equal to containers.tf.
readonly PARAMETERS=(
  "pg_master_password:pg_superuser_password"
  "pg_orchestrator_owner_password:pg_orchestrator_owner_password"
  "pg_orchestrator_app_password:pg_orchestrator_app_password"
  "pg_ledger_owner_password:pg_ledger_owner_password"
  "pg_ledger_app_password:pg_ledger_app_password"
  "pg_seller_api_owner_password:pg_seller_api_owner_password"
  "pg_seller_api_app_password:pg_seller_api_app_password"
  "pg_ingest_owner_password:pg_ingest_owner_password"
  "pg_ingest_app_password:pg_ingest_app_password"
  "x402_buyer_private_key:x402_buyer_private_key"
  "openai_api_key:openai_api_key"
  "seller_service_token_ledger:seller_service_token_ledger"
  "redis_password:redis_password"
  "api_reader_token:api_reader_token"
  "api_operator_token:api_operator_token"
)
# Only written when the file exists (Grafana Cloud is optional; containers.tf reads it only with an endpoint).
readonly OPTIONAL_PARAMETERS=("grafana_otlp_auth:grafana_otlp_auth")

region=""
expires_at=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --region)
      [[ $# -ge 2 ]] || exit 2
      region="$2"
      shift 2
      ;;
    --expires-at)
      [[ $# -ge 2 ]] || exit 2
      expires_at="$2"
      shift 2
      ;;
    --list)
      # Machine-readable parameter names, used by check-ssm-names.sh.
      for pair in "${PARAMETERS[@]}" "${OPTIONAL_PARAMETERS[@]}"; do echo "${pair%%:*}"; done
      exit 0
      ;;
    -*)
      echo "put-demo-parameters: unknown option $1" >&2
      exit 2
      ;;
    *)
      break
      ;;
  esac
done
[[ $# -eq 1 && -n "${region}" && -n "${expires_at}" ]] || {
  echo "usage: put-demo-parameters.sh --region <region> --expires-at <RFC 3339 UTC> <secrets-dir>" >&2
  exit 2
}
dir="$1"
[[ "${expires_at}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || {
  echo "put-demo-parameters: --expires-at must look like 2026-10-06T18:00:00Z" >&2
  exit 2
}
export AWS_PAGER=""

put_secure() { # <parameter name> <file>
  aws --region "${region}" ssm put-parameter --name "/saiman/demo/$1" --type SecureString --overwrite \
    --value "file://$2" --no-cli-pager >/dev/null
  echo "put-demo-parameters: /saiman/demo/$1 (SecureString)"
}

for pair in "${PARAMETERS[@]}"; do
  name="${pair%%:*}"
  file="${dir}/${pair##*:}"
  [[ -s "${file}" ]] || {
    echo "::error::put-demo-parameters: ${file##*/} is missing or empty" >&2
    exit 2
  }
  put_secure "${name}" "${file}"
done
for pair in "${OPTIONAL_PARAMETERS[@]}"; do
  file="${dir}/${pair##*:}"
  if [[ -s "${file}" ]]; then
    put_secure "${pair%%:*}" "${file}"
  fi
done

# The expiry first matters for the reaper: it exists before anything billable is created.
aws --region "${region}" ssm put-parameter --name /saiman/demo/expires-at --type String --overwrite \
  --value "${expires_at}" --no-cli-pager >/dev/null
aws --region "${region}" ssm add-tags-to-resource --resource-type Parameter --resource-id /saiman/demo/expires-at \
  --tags "Key=expires-at,Value=${expires_at}" >/dev/null
echo "put-demo-parameters: /saiman/demo/expires-at = ${expires_at} (String + tag)"
