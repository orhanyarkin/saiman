#!/usr/bin/env bash
# Prepares the demo-up secret files in <secrets-dir> (mode 0700, files 0600) (ADR-0028, ADR-0009).
#
#   - Generated secrets (DB passwords, service and API tokens) come from the repo's own generator,
#     scripts/ensure-secret-files.sh, so local and AWS demos share one definition of "a valid secret".
#   - The three values only a human has come from the environment, i.e. GitHub environment secrets:
#       X402_BUYER_PRIVATE_KEY   throwaway TESTNET key (Base Sepolia, eip155:84532), never generated
#       OPENAI_API_KEY           the capped demo key
#       GRAFANA_OTLP_AUTH        optional, base64(instance-id:token) for the Grafana Cloud OTLP gateway
#   - Every value is registered with `::add-mask::` so a later accidental echo is blanked in the log.
#
# Usage: prepare-demo-secrets.sh <secrets-dir>
# Never prints a secret. Exit 2 on missing or malformed input.
set -euo pipefail

[[ $# -eq 1 ]] || {
  echo "usage: prepare-demo-secrets.sh <secrets-dir>" >&2
  exit 2
}
dir="$1"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "${here}/../../../.." && pwd)"

die() {
  echo "::error::prepare-demo-secrets: $*" >&2
  exit 2
}

buyer="${X402_BUYER_PRIVATE_KEY:-}"
openai="${OPENAI_API_KEY:-}"
grafana="${GRAFANA_OTLP_AUTH:-}"
[[ -n "${buyer}" ]] || die "environment secret X402_BUYER_PRIVATE_KEY is not set (demo-apply environment)"
[[ "${buyer}" =~ ^(0x)?[0-9a-fA-F]{64}$ ]] || die "X402_BUYER_PRIVATE_KEY is not a 32-byte hex key"
[[ -n "${openai}" ]] || die "environment secret OPENAI_API_KEY is not set (demo-apply environment)"

umask 077
SECRETS_DIR="${dir}" "${repo}/scripts/ensure-secret-files.sh" >/dev/null 2>&1 ||
  die "scripts/ensure-secret-files.sh failed (re-run it locally to see why)"
chmod 700 "${dir}"

# ensure-secret-files.sh creates the credential files empty and the generated ones 0644 (container uids
# need that for compose bind mounts); on a CI runner nothing else reads them, so tighten to 0600.
printf '%s' "${buyer}" >"${dir}/x402_buyer_private_key"
printf '%s' "${openai}" >"${dir}/openai_api_key"
if [[ -n "${grafana}" ]]; then
  printf '%s' "${grafana}" >"${dir}/grafana_otlp_auth"
fi
# The task-local Redis password (/saiman/demo/redis_password) is not part of the compose secret set:
# generated here the same way as the DB passwords (`openssl rand -hex 32`).
if [[ ! -s "${dir}/redis_password" ]]; then
  openssl rand -hex 32 | tr -d '\n' >"${dir}/redis_password"
fi
# Not used by the demo; remove so nothing stale could be uploaded or scanned.
rm -f "${dir}/mkk_credentials"
chmod 600 "${dir}"/*

# Mask every value (single-line secrets) before any other step can touch them.
for f in "${dir}"/*; do
  [[ -f "${f}" && -s "${f}" ]] || continue
  value="$(tr -d '\r\n' <"${f}")"
  echo "::add-mask::${value}"
done
echo "prepare-demo-secrets: ${dir} ready ($(find "${dir}" -type f | wc -l) files, all masked, none printed)"
