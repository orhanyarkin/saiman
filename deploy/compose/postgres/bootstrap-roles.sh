#!/usr/bin/env bash
# One-shot cluster bootstrap for the per-service Postgres roles (ADR-0024). Runs as the
# `db-init` compose service with the superuser, is idempotent and safe on an existing volume.
#
# Passwords come from the per-service secret files (/run/secrets/pg_<svc>_<owner|app>_password)
# and reach psql through process environment variables read with \getenv, never through
# argv or a printed statement. Nothing here prints a password.
set -euo pipefail

secrets_dir="${SECRETS_DIR:-/run/secrets}"
sql_file="${BOOTSTRAP_SQL:-$(dirname "${BASH_SOURCE[0]}")/bootstrap-roles.sql}"

for svc in orchestrator ledger seller_api ingest; do
  for kind in owner app; do
    file="${secrets_dir}/pg_${svc}_${kind}_password"
    if [[ ! -s "${file}" ]]; then
      echo "bootstrap-roles: ${file} is missing or empty (run scripts/ensure-secret-files.sh)" >&2
      exit 1
    fi
    value="$(tr -d '\r\n' <"${file}")"
    if [[ -z "${value}" ]]; then
      echo "bootstrap-roles: ${file} holds no password" >&2
      exit 1
    fi
    name="PW_${svc^^}_${kind^^}"
    export "${name}=${value}"
  done
done

exec psql -v ON_ERROR_STOP=1 -X -q \
  -h "${PGHOST:-postgres}" -U "${PGUSER:-saiman}" -d "${PGDATABASE:-saiman}" \
  -f "${sql_file}"
