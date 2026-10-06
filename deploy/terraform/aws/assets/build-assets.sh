#!/usr/bin/env bash
# Stage the demo-lite deploy assets (ADR-0028) into <out-dir>. The `assets` container of the ECS task
# runs `aws s3 sync s3://<state-bucket>/demo/assets/<session_id>/ /assets`; the demo-up workflow uploads
# exactly this directory there. Nothing is uploaded here and no AWS call is made.
#
# Usage: build-assets.sh [--web-dist DIR] <out-dir>
#   --web-dist DIR  copy an already built web dist instead of running `pnpm --dir web build`
#                   (the default build is the live-mode build: VITE_DEMO_MODE is unset).
#   <out-dir>       must not exist or must be empty (the script never deletes anything).
#
# Layout of <out-dir>:
#   web/dist/**                 the dashboard (nginx root)
#   nginx/default.conf          deploy/compose/nginx.conf with 127.0.0.1 upstreams and no Docker resolver
#   otel/config.yaml            collector config (OTLP on loopback, debug exporter)
#   otel/grafana.yaml           optional Grafana Cloud overlay; the task passes it only when GRAFANA_* is set
#   postgres/bootstrap-roles.sh|sql, postgres/corpus-restore.sh   copied from deploy/compose/postgres
#   scripts/readiness.sh                                           copied from this directory (busybox readiness container)
#
# Deterministic: sorted traversal, modes 0644/0755, mtime 0, no timestamps in content. The manifest
# (sha256 per file, sorted) is printed on stdout, followed by one `manifest-sha256 <hex>` line (aggregate over
# the manifest text), and is the same for the same inputs. No secrets are read.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "${here}/../../../.." && pwd)"

# Compose-side upstreams and their loopback replacements in the single task network namespace.
# Ports are the services' own ports (docker-compose.yml); the otel collector listens on 4318 (OTLP/HTTP).
nginx_upstreams=("http://orchestrator:8080|http://127.0.0.1:8080"
  "http://ledger:8082|http://127.0.0.1:8082"
  "http://otel-collector:4318|http://127.0.0.1:4318")

die() {
  echo "build-assets: $*" >&2
  exit 1
}

web_dist=""
out=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --web-dist)
      [[ $# -ge 2 ]] || die "--web-dist needs a directory"
      web_dist="$2"
      shift 2
      ;;
    -h | --help)
      sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d'
      exit 0
      ;;
    -*) die "unknown option $1" ;;
    *)
      [[ -z "${out}" ]] || die "exactly one <out-dir> expected"
      out="$1"
      shift
      ;;
  esac
done
[[ -n "${out}" ]] || die "usage: build-assets.sh [--web-dist DIR] <out-dir>"

if [[ -e "${out}" ]]; then
  [[ -d "${out}" ]] || die "${out} exists and is not a directory"
  [[ -z "$(ls -A "${out}")" ]] || die "${out} is not empty (refusing to delete anything)"
fi

if [[ -z "${web_dist}" ]]; then
  command -v pnpm >/dev/null || die "pnpm not found; build the web app yourself and pass --web-dist"
  (cd "${repo}/web" && env -u VITE_DEMO_MODE pnpm build) >&2
  web_dist="${repo}/web/dist"
fi
[[ -f "${web_dist}/index.html" ]] || die "${web_dist}/index.html not found: not a built web dist"

mkdir -p "${out}/web/dist" "${out}/nginx" "${out}/otel" "${out}/postgres" "${out}/scripts"
out="$(cd "${out}" && pwd)"

# --- web/dist: copy file by file in sorted order, normalised modes ----------------------------------
while IFS= read -r -d '' rel; do
  rel="${rel#./}"
  mkdir -p "${out}/web/dist/$(dirname "${rel}")"
  install -m 0644 "${web_dist}/${rel}" "${out}/web/dist/${rel}"
done < <(cd "${web_dist}" && find . -type f -print0 | LC_ALL=C sort -z)

# --- nginx/default.conf -------------------------------------------------------------------------
# Only two edits: upstream hosts -> loopback, and the Docker-resolver block (comment + directive) removed.
# Everything else, including the missing /internal route and the security headers, stays byte-identical.
sed_args=()
for pair in "${nginx_upstreams[@]}"; do
  sed_args+=(-e "s|${pair%%|*}|${pair##*|}|g")
done
awk '
  /^# Docker.s embedded DNS/ { skipping = 1 }
  skipping { if ($0 ~ /^resolver 127\.0\.0\.11/) { skipping = 0 } ; next }
  { print }
' "${repo}/deploy/compose/nginx.conf" | sed "${sed_args[@]}" >"${out}/nginx/default.conf"
chmod 0644 "${out}/nginx/default.conf"
"${here}/check-nginx-conf.sh" "${out}/nginx/default.conf" >&2 || die "generated nginx config failed its policy check"

# --- otel ---------------------------------------------------------------------------------------
install -m 0644 "${here}/templates/otel-config.yaml" "${out}/otel/config.yaml"
install -m 0644 "${here}/templates/otel-grafana.yaml" "${out}/otel/grafana.yaml"

# --- postgres scripts (copied) --------------------------------------------------------------------
install -m 0755 "${repo}/deploy/compose/postgres/bootstrap-roles.sh" "${out}/postgres/bootstrap-roles.sh"
install -m 0644 "${repo}/deploy/compose/postgres/bootstrap-roles.sql" "${out}/postgres/bootstrap-roles.sql"
install -m 0755 "${repo}/deploy/compose/postgres/corpus-restore.sh" "${out}/postgres/corpus-restore.sh"
install -m 0755 "${here}/readiness.sh" "${out}/scripts/readiness.sh"

# --- normalise and print the manifest ------------------------------------------------------------
find "${out}" -exec touch -h -d @0 {} +
manifest="$(cd "${out}" && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sed 's|  \./|  |')"
printf '%s\n' "${manifest}"
# Aggregate digest (the demo-lite variable assets_manifest_sha256): sha256 over the manifest text above, one
# "sha256  path" line per file, sorted by path, newline-terminated. The assets container recomputes it.
printf 'manifest-sha256 %s\n' "$(printf '%s\n' "${manifest}" | sha256sum | cut -d' ' -f1)"
