#!/usr/bin/env bash
# Creates the compose secret source files that don't exist yet, as EMPTY files (mode 0600,
# directory 0700), so `docker compose up` doesn't fail on a missing bind source on a clean
# clone. The apps fail closed on an empty credential when they first need it. Never
# overwrites an existing file and never prints file contents (ADR-0009 M2 amendment).
set -euo pipefail

readonly SECRET_NAMES=(mkk_credentials openai_api_key)
dir="${SECRETS_DIR:-secrets}"

if [[ ! -d "$dir" ]]; then
  (umask 077 && mkdir -p "$dir")
  chmod 700 "$dir"
  echo "ensure-secret-files: created directory ${dir}/ (0700)"
fi

for name in "${SECRET_NAMES[@]}"; do
  path="${dir}/${name}"
  if [[ ! -e "$path" ]]; then
    (umask 077 && : >"$path")
    chmod 600 "$path"
    echo "ensure-secret-files: created empty ${path} (0600); fill it before using the feature"
  fi
done
