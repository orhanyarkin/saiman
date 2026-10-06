#!/usr/bin/env bash
# Export the ingest corpus (ADR-0028, ADR-0024): a data-only pg_dump of schema `ingest` without
# flyway_schema_history (custom format) plus a JSON metadata file. The dump holds public KAP data only,
# but it is still stored privately (state bucket), never in git.
#
# Usage: corpus-export.sh [OUT_DIR]        (default build/corpus/)
# Writes OUT_DIR/ingest-corpus.dump and OUT_DIR/ingest-corpus.meta.json.
# Connection: libpq variables (PGHOST, PGPORT, PGUSER, PGDATABASE, PGSSLMODE); the password comes from
# PGPASSWORD or, if that is unset, from the file named by PGPASSWORD_FILE. Nothing is printed except
# the output paths. A role that can read schema ingest (superuser, ingest_owner or ingest_app) works.
set -euo pipefail

out_dir="${1:-build/corpus}"
dump_file="${out_dir}/ingest-corpus.dump"
meta_file="${out_dir}/ingest-corpus.meta.json"

export PGHOST="${PGHOST:-localhost}"
export PGDATABASE="${PGDATABASE:-saiman}"
export PGUSER="${PGUSER:-saiman}"
if [[ -z "${PGPASSWORD:-}" && -n "${PGPASSWORD_FILE:-}" ]]; then
  PGPASSWORD="$(tr -d '\r\n' <"${PGPASSWORD_FILE}")"
  export PGPASSWORD
fi

mkdir -p "${out_dir}"

# Metadata first: if the schema is missing or unreadable we fail before writing a dump.
version="$(psql -X -q -At -v ON_ERROR_STOP=1 -c \
  "SELECT version FROM ingest.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")"
if [[ -z "${version}" ]]; then
  echo "corpus-export: no successful Flyway migration found in ingest.flyway_schema_history" >&2
  exit 1
fi

# Row counts for every ingest table except Flyway's history, and the ingest watermark (newest document
# update and publication, newest scan cursor update). All values are deterministic for a given corpus.
psql -X -q -At -v ON_ERROR_STOP=1 -v flyway_version="${version}" >"${meta_file}.tmp" <<'SQL'
SELECT jsonb_pretty(jsonb_build_object(
  'format', 1,
  'schema', 'ingest',
  'flyway_version', :'flyway_version',
  'watermark', jsonb_build_object(
    'source_document_updated_at', (SELECT max(updated_at) FROM ingest.source_document),
    'source_document_published_at', (SELECT max(published_at) FROM ingest.source_document),
    'source_cursor_updated_at', (SELECT max(updated_at) FROM ingest.source_cursor)),
  'row_counts', (
    SELECT coalesce(jsonb_object_agg(c.relname,
             (xpath('/row/c/text()',
                    query_to_xml(format('SELECT count(*) AS c FROM ingest.%I', c.relname), false, true, '')))[1]::text::bigint),
             '{}'::jsonb)
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'ingest' AND c.relkind IN ('r', 'p') AND c.relname <> 'flyway_schema_history')));
SQL

pg_dump --data-only --format=custom --schema=ingest \
  --exclude-table=ingest.flyway_schema_history \
  --no-owner --no-privileges --file="${dump_file}"

mv "${meta_file}.tmp" "${meta_file}"
echo "corpus-export: wrote ${dump_file} and ${meta_file}"
