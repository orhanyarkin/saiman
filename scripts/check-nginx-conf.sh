#!/usr/bin/env bash
# Static checks on deploy/compose/nginx.conf (ADR-0022), run in CI and by
# scripts/check-compose-policy.sh. The dashboard is same-origin, so the conf must never:
#   - answer CORS (no Access-Control-* header, no add_header ... Origin) or handle OPTIONS: the
#     backends' CSRF protection relies on a cross-site JSON POST having nothing to preflight against;
#   - pin or rewrite the Host header (only `proxy_set_header Host $http_host;`): the backends'
#     DNS-rebinding guards must see the Host the browser sent.
# It must keep a default server that answers 444 and a named server for localhost/127.0.0.1.
# Override the file with NGINX_CONF=<path> (used by the self-test fixtures).
set -euo pipefail

NGINX_CONF="${NGINX_CONF:-deploy/compose/nginx.conf}"

fail_check() {
  echo "check-nginx-conf: FAIL: $*" >&2
}

if [[ ! -f "${NGINX_CONF}" ]]; then
  fail_check "${NGINX_CONF} not found"
  exit 1
fi

# Strip comments so a commented-out line neither triggers nor satisfies a rule.
conf="$(sed -E 's/[[:space:]]*#.*$//' "${NGINX_CONF}")"

violations=0

if grep -qiE 'access-control-' <<<"${conf}"; then
  fail_check "Access-Control-* found: no CORS headers (same-origin routing only, ADR-0022)"
  violations=1
fi
if grep -qiE 'add_header[^;]*origin' <<<"${conf}"; then
  fail_check "add_header ... Origin found: no origin reflection (ADR-0022)"
  violations=1
fi
if grep -qE '\bOPTIONS\b' <<<"${conf}"; then
  fail_check "OPTIONS handling found: nothing may answer a preflight (ADR-0022)"
  violations=1
fi

host_lines="$(grep -iE 'proxy_set_header[[:space:]]+Host\b' <<<"${conf}" || true)"
if [[ -z "${host_lines}" ]]; then
  fail_check "no 'proxy_set_header Host \$http_host;' found: the original Host must reach the backends"
  violations=1
elif grep -viE '^[[:space:]]*proxy_set_header[[:space:]]+Host[[:space:]]+\$http_host[[:space:]]*;[[:space:]]*$' <<<"${host_lines}" >/dev/null; then
  fail_check "proxy_set_header Host must be exactly '\$http_host' (no fixed Host, no \$host/\$proxy_host)"
  violations=1
fi

if ! grep -qE 'listen[[:space:]]+[^;]*default_server' <<<"${conf}" || ! grep -qE 'return[[:space:]]+444[[:space:]]*;' <<<"${conf}"; then
  fail_check "missing the default server that answers 444 (listen ... default_server; return 444;)"
  violations=1
fi
if ! grep -qE 'server_name[[:space:]]+localhost[[:space:]]+127\.0\.0\.1[[:space:]]*;' <<<"${conf}"; then
  fail_check "missing 'server_name localhost 127.0.0.1;'"
  violations=1
fi

if [[ ${violations} -ne 0 ]]; then
  exit 1
fi

echo "check-nginx-conf: PASS (no CORS/OPTIONS, Host passed as \$http_host, 444 default server, server_name localhost 127.0.0.1)"
