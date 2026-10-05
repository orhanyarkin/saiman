#!/usr/bin/env bash
# Reports whether each secret file exists, is empty or holds data, and its mode. Never
# prints contents (or lengths beyond empty/non-empty). Mounted secrets (mkk_credentials,
# openai_api_key, x402_buyer_private_key) must be 0644 so the container user can read them; the protection is the
# 0700 secrets/ directory. buyer.key (console buyer only) and the human API tokens (api_reader_token, api_operator_token) are not mounted and stay 0600.
set -euo pipefail

dir="${SECRETS_DIR:-secrets}"

if [[ -d "$dir" ]]; then
  dir_mode="$(stat -c '%a' "$dir")"
  printf '  %-28s %-8s mode %s\n' "${dir}/" "dir" "$dir_mode"
  if [[ "$dir_mode" != "700" ]]; then
    echo "  WARNING: ${dir}/ should be mode 700 (chmod 700 ${dir}); it is what protects the 0644 files inside" >&2
  fi
fi

entries=(mkk_credentials:644 openai_api_key:644 x402_buyer_private_key:644 buyer.key:600
  api_reader_token:600 api_operator_token:600 seller_service_token_ledger:644 seller_service_token_evals:644)
for svc in orchestrator ledger seller_api ingest; do
  entries+=("pg_${svc}_owner_password:644" "pg_${svc}_app_password:644")
done
for entry in "${entries[@]}"; do
  name="${entry%%:*}"
  want="${entry##*:}"
  path="${dir}/${name}"
  if [[ ! -e "$path" ]]; then
    state="absent"
    mode="-"
  else
    mode="$(stat -c '%a' "$path")"
    if [[ -s "$path" ]]; then state="present"; else state="empty"; fi
  fi
  printf '  %-28s %-8s mode %s\n' "$path" "$state" "$mode"
  if [[ "$mode" != "-" && "$mode" != "$want" ]]; then
    echo "  WARNING: ${path} should be mode ${want}" >&2
  fi
  if [[ "$state" == "empty" && "$name" == "x402_buyer_private_key" ]]; then
    echo "  WARNING: ${path} is empty; the orchestrator fails closed at startup until it holds a testnet key" >&2
  fi
done
