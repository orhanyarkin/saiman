#!/usr/bin/env bash
# Static checks on deploy/compose/nginx.conf (ADR-0022), run in CI and by
# scripts/check-compose-policy.sh. The dashboard is same-origin, so the conf must never:
#   - answer CORS (no Access-Control-* header, no add_header ... Origin) or handle OPTIONS: the
#     backends' CSRF protection relies on a cross-site JSON POST having nothing to preflight against;
#   - pin or rewrite the Host header (only `proxy_set_header Host $http_host;`): the backends'
#     DNS-rebinding guards must see the Host the browser sent.
# Also: no `proxy_set_header Authorization`, no /internal anywhere, no seller-api/ingest upstream (ADR-0023, ADR-0025).
# It must keep a default server that answers 444 and a named server for localhost/127.0.0.1, set
# server_tokens off at http level and keep regex locations under /api/ nested in `location ^~ /api/`.
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

# ADR-0023/0025: nginx injects no credentials (a token in the proxy would authenticate anyone who
# reaches the dashboard port), and nothing under /internal is ever routed (the eval endpoint
# /internal/v1/eval/** lives on seller-api and must stay unreachable from the browser origin);
# seller-api and ingest are not proxy targets at all.
if grep -qiE 'proxy_set_header[[:space:]]+Authorization' <<<"${conf}"; then
  fail_check "proxy_set_header Authorization found: nginx must not inject credentials (ADR-0023)"
  violations=1
fi
if grep -qiE '/internal' <<<"${conf}"; then
  fail_check "/internal found: no location, rewrite or proxy_pass may mention /internal (eval endpoint must never be reachable through nginx, ADR-0025)"
  violations=1
fi
if grep -qiE '(seller-api|ingest)(:[0-9]+)?' <<<"${conf}"; then
  fail_check "seller-api/ingest referenced: nginx may only proxy to orchestrator, ledger and the otel collector"
  violations=1
fi

# Upstream allowlist: the only places nginx may send traffic are the orchestrator, the ledger and
# the OTel collector, spelled exactly (a variable holding one of them, or the literal URL).
allowed_upstreams=(http://ledger:8082 http://orchestrator:8080 http://otel-collector:4318)
is_allowed_upstream() {
  local u
  for u in "${allowed_upstreams[@]}"; do [[ "$1" == "${u}" ]] && return 0; done
  return 1
}
while read -r _ name value; do
  value="${value%;}"
  if ! is_allowed_upstream "${value}"; then
    fail_check "'set ${name} ${value}': only ${allowed_upstreams[*]} may be upstream values"
    violations=1
  fi
done < <(grep -E '^[[:space:]]*set[[:space:]]+\$[A-Za-z_]+[[:space:]]+[^;]+;' <<<"${conf}" | sed -E 's/^[[:space:]]+//')
while read -r _ target; do
  target="${target%;}"
  case "${target}" in
    '$ledger' | '$orchestrator' | '$collector') ;;
    *)
      if ! is_allowed_upstream "${target}"; then
        fail_check "proxy_pass ${target}: only the exact orchestrator, ledger and collector upstreams are allowed"
        violations=1
      fi
      ;;
  esac
done < <(grep -E '^[[:space:]]*proxy_pass[[:space:]]+[^;]+;' <<<"${conf}" | sed -E 's/^[[:space:]]+//')

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

# Brace depth of every line: 0 = http context, 1 = inside a server, 2 = inside a location of a server.
depths="$(awk '{ print depth "\t" $0; n = gsub(/\{/, "{"); m = gsub(/\}/, "}"); depth += n - m }' <<<"${conf}")"

# server_tokens must be off at http level (nginx's own error pages, e.g. the default server's 400).
if ! grep -qE $'^0\tserver_tokens[[:space:]]+off[[:space:]]*;' <<<"${depths}"; then
  fail_check "'server_tokens off;' must be set at http level (top of the file), not only inside a server"
  violations=1
fi

# A top-level regex location for /api/... is shadowed by the '^~ /api/' prefix location (which switches regex
# matching off), so it never runs: nest it inside that block (found by the M5 audit for the SSE location).
if grep -qE $'^1\t[[:space:]]*location[[:space:]]+~\*?[[:space:]]+\\^?/api/' <<<"${depths}" \
  && grep -qE $'^1\t[[:space:]]*location[[:space:]]+\\^~[[:space:]]+/api/' <<<"${depths}"; then
  fail_check "a top-level 'location ~ ^/api/...' is shadowed by 'location ^~ /api/'; nest it inside that block"
  violations=1
fi

if [[ ${violations} -ne 0 ]]; then
  exit 1
fi

echo "check-nginx-conf: PASS (no CORS/OPTIONS, Host passed as \$http_host, 444 default server, server_name localhost 127.0.0.1)"
