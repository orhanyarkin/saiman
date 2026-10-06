#!/usr/bin/env bash
# Verifies that the SSM parameters demo-up creates (ops/put-demo-parameters.sh) are exactly the ones the
# ECS task definition injects (demo-lite/containers.tf `secrets[].valueFrom`), plus the documented extras
# that no container reads. Offline, no AWS. A heuristic parser of containers.tf: it takes the right-hand
# sides of `UPPER_SNAKE = "lower_snake"` lines that contain an underscore and expands the two `pg_` templates
# over the schemas listed in `apps` and the owner/app pair; the sanity floor catches a parser that stopped
# matching.
#
# Usage: check-ssm-names.sh [<containers.tf> [<put-demo-parameters.sh>]]
# Exit 0 equal, 1 drift, 2 usage.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
containers="${1:-${here}/../demo-lite/containers.tf}"
putter="${2:-${here}/put-demo-parameters.sh}"
[[ -f "${containers}" && -f "${putter}" ]] || {
  echo "usage: check-ssm-names.sh [<containers.tf> [<put-demo-parameters.sh>]]" >&2
  exit 2
}
# Written by put-demo-parameters.sh for humans and tooling only; no container reads them.
readonly EXTRAS=(api_reader_token api_operator_token)

work="$(mktemp -d "${TMPDIR:-/tmp}/saiman-ssm-names.XXXXXX")"
trap 'rm -rf "${work}"' EXIT

# Schemas of the four services: `orchestrator = { schema = "orchestrator", port = 8080 }` lines.
grep -oE 'schema *= *"[a-z_]+"' "${containers}" | sed -E 's/.*"([a-z_]+)"/\1/' | sort -u >"${work}/schemas.txt"

# Candidate right-hand sides.
# (`KEY = "value"` anywhere on a line, also the inline `{ PGPASSWORD = "..." }` forms; database user names such as
# ingest_owner and `${a.schema}_app` are excluded.)
grep -oE '[A-Z][A-Z0-9_]* *= *"[a-z0-9_${}.]+[^"]*"' "${containers}" |
  sed -E 's/^[^"]*"([^"]+)"$/\1/' |
  grep '_' | grep -vE '[A-Z:]|^\$\{|_(owner|app)$' >"${work}/raw.txt" || true
# Template sides of the `for pair in setproduct(...)` expression (key is a quoted string, not UPPER_SNAKE).
grep -oE '"pg_\$\{[^"]*_password"' "${containers}" | tr -d '"' >>"${work}/raw.txt" || true

: >"${work}/expected.txt"
# Placeholders as written in containers.tf (quoted below so bash matches them literally).
t_a='${a.schema}'
t_b='${local.apps[pair[0]].schema}'
t_role='${pair[1]}'
while IFS= read -r raw; do
  [[ -n "${raw}" ]] || continue
  if [[ "${raw}" == *'${'* ]]; then
    while IFS= read -r schema; do
      expanded="${raw//"${t_a}"/${schema}}"
      expanded="${expanded//"${t_b}"/${schema}}"
      if [[ "${expanded}" == *"${t_role}"* ]]; then
        for role in owner app; do
          echo "${expanded//"${t_role}"/${role}}"
        done
      else
        echo "${expanded}"
      fi
    done <"${work}/schemas.txt"
  else
    echo "${raw}"
  fi
done <"${work}/raw.txt" | grep -E '^[a-z0-9_]+$' | sort -u >"${work}/expected.txt"

"${putter}" --list | sort -u >"${work}/created-all.txt"
printf '%s\n' "${EXTRAS[@]}" | sort -u >"${work}/extras.txt"
comm -23 "${work}/created-all.txt" "${work}/extras.txt" >"${work}/created.txt"

count="$(wc -l <"${work}/expected.txt")"
if [[ "${count}" -lt 13 ]]; then
  echo "check-ssm-names: only ${count} valueFrom names found in ${containers##*/} (expected at least 13): the parser needs an update" >&2
  exit 1
fi

if diff -u "${work}/expected.txt" "${work}/created.txt" >"${work}/diff.txt"; then
  echo "check-ssm-names: OK, ${count} parameters, identical to containers.tf valueFrom (extras not read by containers: ${EXTRAS[*]})"
else
  echo "check-ssm-names: DRIFT between containers.tf (-) and put-demo-parameters.sh (+):" >&2
  cat "${work}/diff.txt" >&2
  exit 1
fi
