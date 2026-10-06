#!/usr/bin/env bash
# Policy check for the generated demo-lite nginx config (nginx/default.conf, ADR-0028).
# Usage: check-nginx-conf.sh <file>
#
# Fails when the config
#   - mentions /internal anywhere (the eval route must never be proxied, ADR-0025),
#   - lacks any add_header line of deploy/compose/nginx.conf (security headers, byte for byte),
#   - still has a resolver directive or the Docker DNS address 127.0.0.11,
#   - names a compose hostname (orchestrator:, ledger:, otel-collector:, seller-api:, ingest:, ...),
#   - contains any http(s) URL that is not http://127.0.0.1:<port>.
# On top of that it reuses scripts/check-nginx-conf.sh (no CORS/OPTIONS, Host passthrough, no injected
# Authorization, 444 default server, server_tokens, upstream allowlist) instead of copying its rules:
# the loopback upstreams are mapped back to the compose spelling in a temp copy, and that copy is
# handed to the compose check. An upstream that is not one of the three known loopback ports stays
# unmapped and is therefore rejected by the compose check's allowlist.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "${here}/../../../.." && pwd)"
compose_conf="${repo}/deploy/compose/nginx.conf"

fail_check() {
  echo "check-nginx-conf(aws): FAIL: $*" >&2
}

if [[ $# -ne 1 ]]; then
  echo "usage: check-nginx-conf.sh <file>" >&2
  exit 2
fi
file="$1"
if [[ ! -f "${file}" ]]; then
  fail_check "${file} not found"
  exit 1
fi

# Comments stripped so a commented-out line neither triggers nor satisfies a rule.
conf="$(sed -E 's/[[:space:]]*#.*$//' "${file}")"
violations=0

if grep -qiE '/internal' <<<"${conf}"; then
  fail_check "/internal found: the eval endpoint must never be routed (ADR-0025)"
  violations=1
fi
if grep -qE '127\.0\.0\.11|^[[:space:]]*resolver[[:space:]]' <<<"${conf}"; then
  fail_check "resolver / Docker DNS (127.0.0.11) found: upstreams are loopback literals on AWS, no resolver"
  violations=1
fi
# (A bare word such as the /api/v1/ledger/ path or the $ledger variable is fine; a host is `//name` or `name:port`.)
if grep -qiE '//(orchestrator|ledger|otel-collector|seller-api|ingest|postgres|kafka|redis|jaeger)\b|\b(orchestrator|ledger|otel-collector|seller-api|ingest|postgres|kafka|redis|jaeger):[0-9]+' <<<"${conf}"; then
  fail_check "compose hostname found: the generated config may only proxy to 127.0.0.1"
  violations=1
fi
while IFS= read -r url; do
  if ! [[ "${url}" =~ ^https?://127\.0\.0\.1:[0-9]+$ ]]; then
    fail_check "upstream '${url}' is not http://127.0.0.1:<port>"
    violations=1
  fi
done < <(grep -oE 'https?://[^[:space:];"]+' <<<"${conf}" || true)

# Security headers: every add_header of the compose config must be present verbatim.
if [[ -f "${compose_conf}" ]]; then
  while IFS= read -r header; do
    if ! grep -qxF -- "${header}" "${file}"; then
      fail_check "missing security header line: ${header}"
      violations=1
    fi
  done < <(grep -E '^[[:space:]]*add_header[[:space:]]' "${compose_conf}")
fi
for name in X-Content-Type-Options Referrer-Policy X-Frame-Options Content-Security-Policy; do
  if ! grep -qE "add_header[[:space:]]+${name}[[:space:]].*always;" <<<"${conf}"; then
    fail_check "add_header ${name} ... always; not found"
    violations=1
  fi
done

# Reuse the compose policy: map the known loopback upstreams back to compose names in a temp copy.
tmp="$(mktemp)"
trap 'rm -f "${tmp}"' EXIT
sed -e 's|http://127\.0\.0\.1:8080|http://orchestrator:8080|g' \
  -e 's|http://127\.0\.0\.1:8082|http://ledger:8082|g' \
  -e 's|http://127\.0\.0\.1:4318|http://otel-collector:4318|g' "${file}" >"${tmp}"
if ! NGINX_CONF="${tmp}" "${repo}/scripts/check-nginx-conf.sh" >/dev/null 2>"${tmp}.err"; then
  sed 's|^|  |' "${tmp}.err" >&2
  fail_check "scripts/check-nginx-conf.sh rejected the config (details above)"
  violations=1
fi
rm -f "${tmp}.err"

if [[ ${violations} -ne 0 ]]; then
  exit 1
fi
echo "check-nginx-conf(aws): PASS (loopback upstreams only, no resolver, no /internal, security headers intact, compose policy holds)"
