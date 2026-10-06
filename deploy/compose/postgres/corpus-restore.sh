#!/usr/bin/env bash
# Restore the ingest corpus produced by corpus-export.sh (ADR-0028, ADR-0024).
#
# Usage: corpus-restore.sh [DIR]           (default build/corpus/; expects ingest-corpus.dump + .meta.json)
# Runs as ingest_owner (the schema owner, so no superuser and no trigger tricks are needed). The target
# schema must already be migrated by Flyway to the version recorded in the metadata.
# Idempotent: when source_document or chunk already hold rows, nothing is restored (exit 0).
# Connection: libpq variables; PGUSER defaults to ingest_owner, the password comes from PGPASSWORD,
# else PW_INGEST_OWNER, else the file named by PGPASSWORD_FILE. PGSSLMODE is honoured (set it to
# `require` against RDS). Nothing secret is printed.
set -euo pipefail

dir="${1:-build/corpus}"
dump_file="${dir}/ingest-corpus.dump"
meta_file="${dir}/ingest-corpus.meta.json"

for f in "${dump_file}" "${meta_file}"; do
  if [[ ! -s "${f}" ]]; then
    echo "corpus-restore: ${f} is missing or empty" >&2
    exit 1
  fi
done

export PGHOST="${PGHOST:-localhost}"
export PGDATABASE="${PGDATABASE:-saiman}"
export PGUSER="${PGUSER:-ingest_owner}"
if [[ -z "${PGPASSWORD:-}" ]]; then
  if [[ -n "${PW_INGEST_OWNER:-}" ]]; then
    export PGPASSWORD="${PW_INGEST_OWNER}"
  elif [[ -n "${PGPASSWORD_FILE:-}" ]]; then
    PGPASSWORD="$(tr -d '\r\n' <"${PGPASSWORD_FILE}")"
    export PGPASSWORD
  fi
fi

meta="$(cat "${meta_file}")"
# (psql does not interpolate variables in -c strings, hence stdin.)
expected="$(psql -X -q -At -v ON_ERROR_STOP=1 -v meta="${meta}" <<<"SELECT (:'meta'::jsonb) ->> 'flyway_version'")"
actual="$(psql -X -q -At -v ON_ERROR_STOP=1 -c \
  "SELECT version FROM ingest.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")"
if [[ -z "${actual}" || "${expected}" != "${actual}" ]]; then
  echo "corpus-restore: Flyway version mismatch (dump ${expected:-?}, target ${actual:-none}); migrate the target to the dump's version first" >&2
  exit 1
fi

existing="$(psql -X -q -At -v ON_ERROR_STOP=1 -c \
  "SELECT (SELECT count(*) FROM ingest.source_document) + (SELECT count(*) FROM ingest.chunk)")"
if [[ "${existing}" != "0" ]]; then
  echo "corpus-restore: target already holds ${existing} corpus rows; skipping restore"
  exit 0
fi

pg_restore --data-only --no-owner --no-privileges --exit-on-error --single-transaction \
  --dbname="${PGDATABASE}" "${dump_file}"

# Verify against the recorded row counts.
mismatch="$(psql -X -q -At -v ON_ERROR_STOP=1 -v meta="${meta}" <<'SQL'
SELECT coalesce(string_agg(e.key || ': expected ' || e.value || ', got ' || a.c, '; '), '')
  FROM jsonb_each_text((:'meta'::jsonb) -> 'row_counts') e
  CROSS JOIN LATERAL (
    SELECT (xpath('/row/c/text()',
                  query_to_xml(format('SELECT count(*) AS c FROM ingest.%I', e.key), false, true, '')))[1]::text AS c) a
 WHERE a.c <> e.value;
SQL
)"
if [[ -n "${mismatch}" ]]; then
  echo "corpus-restore: row count mismatch after restore: ${mismatch}" >&2
  exit 1
fi
echo "corpus-restore: restored ${dump_file}"
