#!/usr/bin/env bash
# Offline self-test for the demo-lite assets (ADR-0028): runs in CI and locally, needs only bash,
# coreutils, python3 and wget. No AWS, no pnpm build (a fixture web dist is used), no network but loopback.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "${here}/../../../.." && pwd)"
work="$(mktemp -d)"
pids=()
cleanup() {
  for pid in "${pids[@]}"; do kill "${pid}" 2>/dev/null || true; done
  rm -rf "${work}"
}
trap cleanup EXIT

fails=0
ok() { echo "ok:   $*"; }
bad() {
  echo "FAIL: $*" >&2
  fails=$((fails + 1))
}
expect_pass() { # <description> <command...>
  local desc="$1"
  shift
  if "$@" >"${work}/out.txt" 2>&1; then ok "${desc}"; else
    bad "${desc}"
    sed 's/^/      /' "${work}/out.txt" >&2
  fi
}
expect_fail() { # <description> <command...>
  local desc="$1"
  shift
  if "$@" >"${work}/out.txt" 2>&1; then bad "${desc} (expected a failure, got success)"; else ok "${desc}"; fi
}

# --- fixture web dist ----------------------------------------------------------------------------
fixture="${work}/fixture-dist"
mkdir -p "${fixture}/assets/nested"
echo '<!doctype html><title>fixture</title>' >"${fixture}/index.html"
echo 'console.log("fixture");' >"${fixture}/assets/app.js"
echo 'body{}' >"${fixture}/assets/nested/app.css"

# --- 1. build, manifest, layout -------------------------------------------------------------------
build_a="${work}/a"
build_b="${work}/b"
"${here}/build-assets.sh" --web-dist "${fixture}" "${build_a}" >"${work}/manifest-a.txt" 2>"${work}/build-a.err" \
  && ok "build-assets.sh builds from a fixture web dist" \
  || {
    bad "build-assets.sh failed"
    cat "${work}/build-a.err" >&2
  }
for f in web/dist/index.html web/dist/assets/app.js web/dist/assets/nested/app.css nginx/default.conf \
  otel/config.yaml otel/grafana.yaml postgres/bootstrap-roles.sh postgres/bootstrap-roles.sql postgres/corpus-restore.sh; do
  if [[ -f "${build_a}/${f}" ]] && grep -q "  ${f}\$" "${work}/manifest-a.txt"; then ok "staged and in manifest: ${f}"; else bad "missing or not in manifest: ${f}"; fi
done
if [[ "$(wc -l <"${work}/manifest-a.txt")" -eq "$(find "${build_a}" -type f | wc -l)" ]]; then ok "manifest lists exactly the staged files"; else bad "manifest line count differs from file count"; fi
if LC_ALL=C sort -c -k2 "${work}/manifest-a.txt" 2>/dev/null; then ok "manifest is sorted by path"; else bad "manifest is not sorted"; fi
expect_fail "refuses a non-empty out-dir" "${here}/build-assets.sh" --web-dist "${fixture}" "${build_a}"
expect_fail "rejects a dist without index.html" "${here}/build-assets.sh" --web-dist "${work}/a/otel" "${work}/c"

# copied files are byte-identical to their sources
for f in bootstrap-roles.sh bootstrap-roles.sql corpus-restore.sh; do
  if cmp -s "${repo}/deploy/compose/postgres/${f}" "${build_a}/postgres/${f}"; then ok "postgres/${f} is a verbatim copy"; else bad "postgres/${f} differs from source"; fi
done
if [[ "$(stat -c %a "${build_a}/postgres/corpus-restore.sh")" == 755 && "$(stat -c %a "${build_a}/nginx/default.conf")" == 644 ]]; then ok "stable permissions (0755 scripts, 0644 data)"; else bad "unexpected permissions"; fi

# --- 2. nginx generation: only the documented edits ------------------------------------------------
generated="${build_a}/nginx/default.conf"
expect_pass "generated nginx config passes check-nginx-conf.sh" "${here}/check-nginx-conf.sh" "${generated}"
# Map back to compose spelling and diff against the source: the only difference may be the resolver block.
sed -e 's|http://127\.0\.0\.1:8080|http://orchestrator:8080|g' -e 's|http://127\.0\.0\.1:8082|http://ledger:8082|g' \
  -e 's|http://127\.0\.0\.1:4318|http://otel-collector:4318|g' "${generated}" >"${work}/roundtrip.conf"
diff "${repo}/deploy/compose/nginx.conf" "${work}/roundtrip.conf" | grep -E '^[<>]' >"${work}/conf.diff" || true
if grep -q '^>' "${work}/conf.diff"; then bad "generated config adds lines beyond the upstream rewrite"; else ok "no added lines after mapping upstreams back"; fi
if [[ "$(grep -c '^<' "${work}/conf.diff")" -eq 3 ]] && grep -q '^< resolver 127.0.0.11' "${work}/conf.diff"; then ok "only the resolver block (2 comment lines + the directive) was removed"; else
  bad "unexpected removed lines"
  cat "${work}/conf.diff" >&2
fi
if grep -q '/internal' "${generated}"; then bad "generated config mentions /internal"; else ok "generated config never mentions /internal"; fi

# --- 3. mutations of the generated file must fail the check -----------------------------------------
mutate() { # <name> <sed-or-shell function name>
  local name="$1"
  cp "${generated}" "${work}/mut.conf"
  "$2" "${work}/mut.conf"
  expect_fail "mutation rejected: ${name}" "${here}/check-nginx-conf.sh" "${work}/mut.conf"
}
m_internal() { printf '\nserver {\n    listen 81;\n    location /internal/v1/eval/ { proxy_pass http://127.0.0.1:8081; }\n}\n' >>"$1"; }
m_internal_rewrite() { sed -i 's|location = /index.html {|location = /index.html {\n        rewrite ^/x$ /internal/v1/eval/run break;|' "$1"; }
m_resolver() { sed -i '1i resolver 127.0.0.11 valid=10s ipv6=off;' "$1"; }
m_resolver_other() { sed -i '1i resolver 8.8.8.8;' "$1"; }
m_hostname() { sed -i 's|http://127\.0\.0\.1:8080|http://orchestrator:8080|' "$1"; }
m_hostname_collector() { sed -i 's|http://127\.0\.0\.1:4318|http://otel-collector:4318|' "$1"; }
m_external() { sed -i 's|http://127\.0\.0\.1:8082|http://203.0.113.9:8082|' "$1"; }
m_seller() { sed -i 's|http://127\.0\.0\.1:8082|http://127.0.0.1:8081|' "$1"; }
m_header() { sed -i '/add_header X-Frame-Options/d' "$1"; }
m_csp() { sed -i "s/default-src 'self'/default-src *'/" "$1"; }
m_cors() { sed -i '1i add_header Access-Control-Allow-Origin "*" always;' "$1"; }
m_444() { sed -i 's/return 444;/return 200;/' "$1"; }
mutate "route /internal/" m_internal
mutate "rewrite to /internal/" m_internal_rewrite
mutate "Docker resolver 127.0.0.11" m_resolver
mutate "any resolver directive" m_resolver_other
mutate "orchestrator compose hostname" m_hostname
mutate "otel-collector compose hostname" m_hostname_collector
mutate "non-loopback upstream" m_external
mutate "upstream to seller-api port" m_seller
mutate "missing X-Frame-Options header" m_header
mutate "weakened Content-Security-Policy" m_csp
mutate "CORS header" m_cors
mutate "444 default server removed" m_444

# --- 4. determinism ----------------------------------------------------------------------------------
"${here}/build-assets.sh" --web-dist "${fixture}" "${build_b}" >"${work}/manifest-b.txt" 2>/dev/null
if cmp -s "${work}/manifest-a.txt" "${work}/manifest-b.txt"; then ok "two builds produce identical manifests"; else bad "manifests differ between builds"; fi
if diff -r "${build_a}" "${build_b}" >/dev/null; then ok "two builds produce identical trees"; else bad "trees differ between builds"; fi

# --- 5. no secret-looking strings ---------------------------------------------------------------------
# Patterns mirror scripts/check-compose-policy.sh (`keyish`) plus common token shapes.
secret_re='-----BEGIN [A-Z ]*PRIVATE KEY|0x[0-9a-fA-F]{64}|AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{30,}|sk-[A-Za-z0-9_-]{20,}|PRIVATE_?KEY|\.pem\b|xox[bap]-[0-9A-Za-z-]{10,}'
if grep -rEn -e "${secret_re}" "${build_a}" >"${work}/secret-hits.txt"; then
  bad "secret-looking strings in the assets"
  sed -E 's/^([^:]*:[0-9]+):.*/\1: <redacted>/' "${work}/secret-hits.txt" >&2
else ok "no secret-looking strings in any staged file"; fi
# The Grafana overlay must only reference the environment, never hold a value.
if grep -q '\${env:GRAFANA_OTLP_AUTH}' "${build_a}/otel/grafana.yaml" && ! grep -qE 'Basic [A-Za-z0-9+/=]{12,}' "${build_a}/otel/grafana.yaml"; then ok "grafana overlay reads its credential from the environment"; else bad "grafana overlay does not use \${env:GRAFANA_OTLP_AUTH}"; fi
if ! grep -qE 'otlp_http|GRAFANA_' "${build_a}/otel/config.yaml"; then ok "base collector config has no Grafana exporter"; else bad "base collector config has a Grafana exporter or env reference"; fi

# --- 6. readiness.sh against fake servers --------------------------------------------------------------
free_port() { python3 -I -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])'; }
serve() { # <dir> <port>
  python3 -I -m http.server "$2" --bind 127.0.0.1 --directory "$1" >/dev/null 2>&1 &
  pids+=("$!")
}
mkdir -p "${work}/up/actuator" "${work}/down/actuator"
printf '{"status":"UP","groups":["liveness","readiness"]}' >"${work}/up/actuator/health"
printf '{"status":"DOWN","components":{"db":{"status":"UP","details":{"secret-looking":"visible"}}}}' >"${work}/down/actuator/health"
p1="$(free_port)"
p2="$(free_port)"
p3="$(free_port)"
serve "${work}/up" "${p1}"
serve "${work}/up" "${p2}"
serve "${work}/down" "${p3}"
sleep 1

READINESS_TARGETS="alpha:${p1} beta:${p2}" READINESS_TIMEOUT_SECONDS=10 READINESS_INTERVAL=1 "${here}/readiness.sh" >"${work}/ready-up.txt" 2>&1 \
  && ok "readiness.sh exits 0 when every target is UP" || bad "readiness.sh failed with all targets UP"
if grep -q 'alpha' "${work}/ready-up.txt" && grep -q 'beta' "${work}/ready-up.txt" && ! grep -q 'status' "${work}/ready-up.txt"; then ok "readiness output names services only"; else bad "readiness output unexpected"; fi

start="$(date +%s)"
if READINESS_TARGETS="alpha:${p1} gamma:${p3}" READINESS_TIMEOUT_SECONDS=3 READINESS_INTERVAL=1 "${here}/readiness.sh" >"${work}/ready-down.txt" 2>&1; then
  bad "readiness.sh exited 0 although a target is DOWN"
else ok "readiness.sh times out (non-zero) when a target stays DOWN"; fi
elapsed=$(($(date +%s) - start))
if [[ ${elapsed} -ge 3 && ${elapsed} -lt 15 ]]; then ok "timeout honoured (${elapsed}s for a 3s limit)"; else bad "timeout took ${elapsed}s"; fi
if grep -q 'gamma' "${work}/ready-down.txt" && ! grep -qE 'status|details|visible|components' "${work}/ready-down.txt"; then ok "timeout output names the failing service and no response body"; else bad "timeout output leaks or omits info"; fi

# A target that comes up late: UP after ~2s.
mkdir -p "${work}/late/actuator"
p4="$(free_port)"
(
  sleep 2
  printf '{"status":"UP"}' >"${work}/late/actuator/health"
) &
pids+=("$!")
serve "${work}/late" "${p4}"
READINESS_TARGETS="late:${p4}" READINESS_TIMEOUT_SECONDS=10 READINESS_INTERVAL=1 "${here}/readiness.sh" >"${work}/ready-late.txt" 2>&1 \
  && ok "readiness.sh waits for a service that comes up late" || bad "readiness.sh did not pick up the late service"

if [[ ${fails} -ne 0 ]]; then
  echo "test-assets: ${fails} check(s) FAILED" >&2
  exit 1
fi
echo "test-assets: all checks passed"
