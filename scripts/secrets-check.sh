#!/usr/bin/env bash
# Reports whether each secret file exists, is empty or holds data, and its mode. Never
# prints contents (or lengths beyond empty/non-empty). Mounted secrets (mkk_credentials,
# openai_api_key, x402_buyer_private_key) must be 0644 so the container user can read them; the protection is the
# 0700 secrets/ directory. buyer.key (console buyer only) is not mounted and stays 0600.
set -euo pipefail

dir="${SECRETS_DIR:-secrets}"

if [[ -d "$dir" ]]; then
  dir_mode="$(stat -c '%a' "$dir")"
  printf '  %-28s %-8s mode %s\n' "${dir}/" "dir" "$dir_mode"
  if [[ "$dir_mode" != "700" ]]; then
    echo "  WARNING: ${dir}/ should be mode 700 (chmod 700 ${dir}); it is what protects the 0644 files inside" >&2
  fi
fi

for entry in mkk_credentials:644 openai_api_key:644 x402_buyer_private_key:644 buyer.key:600; do
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
    echo "  WARNING: ${path} is empty; the orchestrator's paid runs fail closed until it holds a testnet key" >&2
  fi
done
