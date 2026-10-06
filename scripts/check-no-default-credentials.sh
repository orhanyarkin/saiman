#!/usr/bin/env bash
# Fails if a service's main application*.yaml gives a secret-like property a non-empty default, for example
# `password: ${SPRING_DATASOURCE_PASSWORD:saiman}`. The published images contain these files, and a missing secret
# must stop the service instead of falling back to a built-in credential (ADR-0009, ADR-0023, ADR-0024).
# Usernames may keep a default. Run from the repository root.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

pattern='^[[:space:]]*[A-Za-z0-9_.-]*(password|passwd|token|secret|api-key|apikey|private-key|privatekey)[A-Za-z0-9_.-]*:[[:space:]]*"?\$\{[^}:]+:[^}]+\}'
files=()
while IFS= read -r f; do files+=("$f"); done < <(find services libs -path '*/src/main/resources/*' \( -name 'application*.yaml' -o -name 'application*.yml' -o -name 'application*.properties' \) -not -path '*/build/*' | sort)
[[ ${#files[@]} -gt 0 ]] || { echo "check-no-default-credentials: no application config files found" >&2; exit 2; }

bad=0
for f in "${files[@]}"; do
  if hits=$(grep -nE "$pattern" "$f"); then
    while IFS= read -r line; do
      # Print the key only, never the value after the colon.
      key=$(printf '%s' "$line" | sed -E 's/^([0-9]+):[[:space:]]*([^:]+):.*/\1: \2/')
      echo "check-no-default-credentials: $f:$key has a built-in default; remove it" >&2
    done <<<"$hits"
    bad=1
  fi
done
[[ $bad -eq 0 ]] || exit 1
echo "check-no-default-credentials: OK (${#files[@]} config files, no secret-like property has a default)"
