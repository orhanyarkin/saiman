#!/usr/bin/env bash
# One-shot cluster bootstrap for the per-service Postgres roles (ADR-0024, ADR-0028). Idempotent and
# safe on an existing volume or database.
#
# Two independent switches (defaults reproduce the compose `db-init` service exactly):
#   BOOTSTRAP_SECRET_SOURCE=files (default) | env
#       files: passwords from /run/secrets/pg_<svc>_<owner|app>_password (SECRETS_DIR overrides) and the
#              superuser password from pg_superuser_password.
#       env:   passwords from the environment variables PW_<SVC>_<OWNER|APP> (e.g. PW_LEDGER_APP);
#              the connecting role's own password comes from PGPASSWORD. Used by the AWS demo-up workflow
#              (values read from SSM, never printed).
#   BOOTSTRAP_RDS=0 (default) | 1
#       1: the connecting role is the RDS master (rds_superuser: NOSUPERUSER, CREATEROLE, CREATEDB).
#          No superuser-only statements, no superuser-password rotation, no legacy-password fallback,
#          and the `vector` extension must already exist (the caller creates it).
#
# Connection: standard libpq variables (PGHOST, PGPORT, PGUSER, PGDATABASE, PGPASSWORD, PGSSLMODE).
# Passwords reach psql through process environment variables read with \getenv, never through
# argv or a printed statement. Nothing here prints a password, and `set -x` must never be added.
set -euo pipefail

secret_source="${BOOTSTRAP_SECRET_SOURCE:-files}"
rds="${BOOTSTRAP_RDS:-0}"
secrets_dir="${SECRETS_DIR:-/run/secrets}"
sql_file="${BOOTSTRAP_SQL:-$(dirname "${BASH_SOURCE[0]}")/bootstrap-roles.sql}"

case "${secret_source}" in files | env) ;; *)
  echo "bootstrap-roles: BOOTSTRAP_SECRET_SOURCE must be 'files' or 'env'" >&2
  exit 1
  ;;
esac
case "${rds}" in 0 | 1) ;; *)
  echo "bootstrap-roles: BOOTSTRAP_RDS must be 0 or 1" >&2
  exit 1
  ;;
esac

for svc in orchestrator ledger seller_api ingest; do
  for kind in owner app; do
    name="PW_${svc^^}_${kind^^}"
    if [[ "${secret_source}" == "files" ]]; then
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
    else
      value="${!name:-}"
      if [[ -z "${value}" ]]; then
        echo "bootstrap-roles: environment variable ${name} is not set" >&2
        exit 1
      fi
    fi
    export "${name}=${value}"
  done
done

psql_args=(-v ON_ERROR_STOP=1 -X -q -h "${PGHOST:-postgres}" -U "${PGUSER:-saiman}" -d "${PGDATABASE:-saiman}")

if [[ "${rds}" == "1" ]]; then
  # The master password is managed outside this script; the caller supplies it as PGPASSWORD.
  if [[ -z "${PGPASSWORD:-}" ]]; then
    echo "bootstrap-roles: BOOTSTRAP_RDS=1 needs PGPASSWORD (the master user's password)" >&2
    exit 1
  fi
  export PW_SUPERUSER=""
  psql_args+=(-v rds=true)
  exec psql "${psql_args[@]}" -f "${sql_file}"
fi

psql_args+=(-v rds=false)

if [[ "${secret_source}" == "files" ]]; then
  super_file="${secrets_dir}/pg_superuser_password"
  if [[ ! -s "${super_file}" ]]; then
    echo "bootstrap-roles: ${super_file} is missing or empty (run scripts/ensure-secret-files.sh)" >&2
    exit 1
  fi
  new_super="$(tr -d '\r\n' <"${super_file}")"
else
  new_super="${PGPASSWORD:-}"
  if [[ -z "${new_super}" ]]; then
    echo "bootstrap-roles: BOOTSTRAP_SECRET_SOURCE=env needs PGPASSWORD (the superuser password)" >&2
    exit 1
  fi
fi
export PW_SUPERUSER="${new_super}"

if [[ "${secret_source}" == "files" ]]; then
  # Migration path for volumes created before the superuser password became a secret: they still
  # accept the legacy literal. Try the secret first; if the server rejects it, connect with the legacy
  # password once, and the SQL below then rotates the superuser to the secret. The legacy literal is
  # not a credential anymore after that run.
  export PGPASSWORD="${new_super}"
  if ! psql "${psql_args[@]}" -c 'select 1' >/dev/null 2>&1; then
    echo "bootstrap-roles: WARNING: legacy superuser password fallback in use (secret file not yet accepted); rotating the superuser to pg_superuser_password now" >&2
    export PGPASSWORD=saiman
  fi
fi

exec psql "${psql_args[@]}" -f "${sql_file}"
