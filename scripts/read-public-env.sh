#!/usr/bin/env bash
# Prints the value of ONE allowlisted public variable from an env file (default: the repo-root
# .env), so `make` can pick up X402_SELLER_PAYTO_ADDRESS without the user exporting it.
# It never prints any other line: the same file holds API keys and the buyer wallet key, and
# those must never be loaded into make or its recipes (CLAUDE.md rule 2, ADR-0009).
set -euo pipefail

readonly ALLOWED_KEYS=(X402_SELLER_PAYTO_ADDRESS)

key="${1:?usage: read-public-env.sh KEY [ENV_FILE]}"
file="${2:-.env}"

allowed=false
for k in "${ALLOWED_KEYS[@]}"; do
  [[ "$key" == "$k" ]] && allowed=true
done
if [[ "$allowed" != true ]]; then
  echo "read-public-env: refusing to read '$key' (only public variables are allowlisted)" >&2
  exit 2
fi

[[ -r "$file" ]] || exit 0

# Last assignment wins, like a shell would. Accepts `KEY=v`, `export KEY=v`, quotes and a
# trailing ` # comment`; prints nothing if the key is absent.
awk -v key="$key" '
  {
    line = $0
    sub(/^[ \t]*(export[ \t]+)?/, "", line)
    if (index(line, key "=") != 1) next
    value = substr(line, length(key) + 2)
    sub(/[ \t]+#.*$/, "", value)
    gsub(/^[ \t]+|[ \t]+$/, "", value)
    if (value ~ /^".*"$/ || value ~ /^\047.*\047$/) value = substr(value, 2, length(value) - 2)
    found = value
  }
  END { if (found != "") print found }
' "$file"
