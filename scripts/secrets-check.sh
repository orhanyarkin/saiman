#!/usr/bin/env bash
# Reports whether each secret file exists, is empty or holds data, and its mode. Never
# prints contents (or lengths beyond empty/non-empty).
set -euo pipefail

dir="${SECRETS_DIR:-secrets}"
for name in mkk_credentials openai_api_key buyer.key; do
  path="${dir}/${name}"
  if [[ ! -e "$path" ]]; then
    state="absent"
    mode="-"
  else
    mode="$(stat -c '%a' "$path")"
    if [[ -s "$path" ]]; then state="present"; else state="empty"; fi
  fi
  printf '  %-28s %-8s mode %s\n' "$path" "$state" "$mode"
done
