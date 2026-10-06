#!/usr/bin/env bash
# Self-test for deploy/compose/postgres/{bootstrap-roles,corpus-export,corpus-restore} (ADR-0024, ADR-0028).
# Starts throwaway Postgres containers from the image compose uses (no published ports, removed on exit),
# then proves:
#   (a) bootstrap as the superuser, twice with secret files and once with PW_* env vars (idempotent,
#       adopts a pre-existing superuser-owned object);
#   (b) bootstrap as a NOSUPERUSER CREATEROLE CREATEDB role that emulates RDS's rds_superuser, with the
#       vector extension pre-created, twice (and a failure when the extension is missing);
#   (c) a corpus export -> restore round trip on a scratch database, including idempotent re-restore
#       and the Flyway-version guard.
# The RDS emulation is a proxy: the first real `demo-up` is the final check. Test passwords are random
# per run and never printed.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="${repo_root}/deploy/compose/docker-compose.yml"
image="$(grep -m1 -o 'pgvector/pgvector:[^ ]*' "${compose_file}")"
if [[ -z "${image}" ]]; then
  echo "test-bootstrap-roles: cannot find the postgres image in ${compose_file}" >&2
  exit 1
fi
scripts_dir="${repo_root}/deploy/compose/postgres"
migrations_dir="${repo_root}/services/ingest/src/main/resources/db/migration"

suffix="$$-$(date +%s)"
container_a="saiman-bootstrap-test-a-${suffix}"
container_b="saiman-bootstrap-test-b-${suffix}"
pass=0
failed=0

cleanup() {
  docker rm -f "${container_a}" "${container_b}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

rand() { head -c 24 /dev/urandom | base64 | tr -d '=+/\n'; }
super_pw="$(rand)"
master_pw="$(rand)"

declare -A pw
for svc in orchestrator ledger seller_api ingest; do
  for kind in owner app; do
    pw["${svc}_${kind}"]="$(rand)"
  done
done

env_args=()
for svc in orchestrator ledger seller_api ingest; do
  for kind in owner app; do
    env_args+=(-e "PW_${svc^^}_${kind^^}=${pw[${svc}_${kind}]}")
  done
done

ok() {
  pass=$((pass + 1))
  echo "  ok   $1"
}
bad() {
  failed=$((failed + 1))
  echo "  FAIL $1" >&2
}
expect() { # description, expected, actual
  if [[ "$2" == "$3" ]]; then ok "$1"; else bad "$1 (expected '$2', got '$3')"; fi
}

start() { # container, POSTGRES_USER
  docker run -d --name "$1" -e POSTGRES_USER="$2" -e POSTGRES_PASSWORD="$3" -e POSTGRES_DB=saiman \
    -v "${scripts_dir}:/bootstrap:ro" -v "${migrations_dir}:/migrations:ro" "${image}" >/dev/null
  for _ in $(seq 1 60); do
    # The image restarts the server once after init; require two consecutive successes on TCP.
    if docker exec "$1" pg_isready -h 127.0.0.1 -U "$2" -d saiman >/dev/null 2>&1; then
      sleep 2
      docker exec "$1" pg_isready -h 127.0.0.1 -U "$2" -d saiman >/dev/null 2>&1 && return 0
    fi
    sleep 1
  done
  echo "test-bootstrap-roles: $1 did not become ready" >&2
  exit 1
}

# psql inside a container: container, user, password, database, then psql args (SQL on stdin or -c).
q() {
  local c="$1" u="$2" p="$3" d="$4"
  shift 4
  docker exec -i -e PGPASSWORD="${p}" "${c}" psql -X -q -At -v ON_ERROR_STOP=1 -h 127.0.0.1 -U "${u}" -d "${d}" "$@"
}

# Bootstrap run: container, user, password, database, extra docker-exec args...
bootstrap() {
  local c="$1" u="$2" p="$3" d="$4"
  shift 4
  docker exec -e PGHOST=127.0.0.1 -e PGUSER="${u}" -e PGPASSWORD="${p}" -e PGDATABASE="${d}" "$@" \
    "${c}" bash /bootstrap/bootstrap-roles.sh
}

# Common assertions after a bootstrap: container, admin user, admin password, database.
verify_bootstrap() {
  local c="$1" u="$2" p="$3" d="$4" svc owner app
  expect "8 service roles exist" "8" "$(q "${c}" "${u}" "${p}" "${d}" -c "select count(*) from pg_roles where rolname ~ '^(orchestrator|ledger|seller_api|ingest)_(owner|app)\$'")"
  expect "roles are NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS LOGIN" "8" \
    "$(q "${c}" "${u}" "${p}" "${d}" -c "select count(*) from pg_roles where rolname ~ '^(orchestrator|ledger|seller_api|ingest)_(owner|app)\$' and not rolsuper and not rolcreatedb and not rolcreaterole and not rolreplication and not rolbypassrls and rolcanlogin")"
  for svc in orchestrator ledger seller_api ingest; do
    owner="${svc}_owner"
    app="${svc}_app"
    expect "schema ${svc} owned by ${owner}" "${owner}" "$(q "${c}" "${u}" "${p}" "${d}" -c "select pg_get_userbyid(nspowner) from pg_namespace where nspname='${svc}'")"
    expect "${app} has USAGE on schema ${svc}" "t" "$(q "${c}" "${u}" "${p}" "${d}" -c "select has_schema_privilege('${app}','${svc}','USAGE')")"
    expect "${app} cannot CREATE in schema ${svc}" "f" "$(q "${c}" "${u}" "${p}" "${d}" -c "select has_schema_privilege('${app}','${svc}','CREATE')")"
    expect "${owner} can CREATE in schema ${svc}" "t" "$(q "${c}" "${u}" "${p}" "${d}" -c "select has_schema_privilege('${owner}','${svc}','CREATE')")"
    expect "${owner} logs in with its password" "1" "$(q "${c}" "${owner}" "${pw[${svc}_owner]}" "${d}" -c "select 1")"
    expect "${app} logs in with its password" "1" "$(q "${c}" "${app}" "${pw[${svc}_app]}" "${d}" -c "select 1")"
  done
  expect "ingest_app search_path includes public" "ingest, public" "$(q "${c}" ingest_app "${pw[ingest_app]}" "${d}" -c "show search_path")"
  expect "PUBLIC has no CONNECT on the database" "t" "$(q "${c}" "${u}" "${p}" "${d}" -c "select coalesce(datacl::text !~ '(^|,)=', false) from pg_database where datname=current_database()")"
  expect "vector extension present" "1" "$(q "${c}" "${u}" "${p}" "${d}" -c "select count(*) from pg_extension where extname='vector'")"
}

# Migrate the ingest schema as ingest_owner with the real Flyway SQL, and fake Flyway's history table.
migrate_ingest() { # container, database
  local c="$1" d="$2" f
  q "${c}" ingest_owner "${pw[ingest_owner]}" "${d}" >/dev/null <<'SQL'
CREATE TABLE ingest.flyway_schema_history (
  installed_rank int PRIMARY KEY, version varchar(50), description text NOT NULL, success boolean NOT NULL);
INSERT INTO ingest.flyway_schema_history VALUES (1, '1', 'ingest', true), (2, '2', 'missing status', true), (3, '3', 'grants', true);
SQL

  for f in V1__ingest V2__missing_status V3__grants; do
    # shellcheck disable=SC2016  # ${app_role} is a Flyway placeholder, not a shell expansion
    sed 's/\${app_role}/ingest_app/g' "${migrations_dir}/${f}.sql" |
      docker exec -i -e PGPASSWORD="${pw[ingest_owner]}" "${c}" \
        psql -X -q -v ON_ERROR_STOP=1 -h 127.0.0.1 -U ingest_owner -d "${d}" -c 'set search_path = ingest' -f - >/dev/null
  done
}

echo "image: ${image}"

# ---------------------------------------------------------------- (a) superuser
echo "(a) superuser bootstrap"
start "${container_a}" saiman "${super_pw}"
# A pre-M6 object owned by the superuser in a service schema: must be adopted.
q "${container_a}" saiman "${super_pw}" saiman >/dev/null <<'SQL'
CREATE SCHEMA ledger;
CREATE TABLE ledger.legacy_marker (id int);
SQL

docker exec "${container_a}" bash -c 'umask 077; mkdir -p /tmp/secrets'
for key in "${!pw[@]}"; do
  printf '%s' "${pw[${key}]}" | docker exec -i "${container_a}" bash -c "umask 077; cat > /tmp/secrets/pg_${key}_password"
done
printf '%s' "${super_pw}" | docker exec -i "${container_a}" bash -c 'umask 077; cat > /tmp/secrets/pg_superuser_password'

for run in 1 2; do
  if bootstrap "${container_a}" saiman "" saiman -e SECRETS_DIR=/tmp/secrets >/dev/null 2>&1; then
    ok "files-mode bootstrap run ${run} succeeds"
  else
    # Re-run once to show the error text (psql errors never contain passwords).
    bad "files-mode bootstrap run ${run} failed"
    bootstrap "${container_a}" saiman "" saiman -e SECRETS_DIR=/tmp/secrets 2>&1 | sed 's/^/    /' >&2 || true
  fi
done
if bootstrap "${container_a}" saiman "${super_pw}" saiman -e BOOTSTRAP_SECRET_SOURCE=env "${env_args[@]}" >/dev/null 2>&1; then
  ok "env-mode bootstrap succeeds"
else
  bad "env-mode bootstrap failed"
fi
verify_bootstrap "${container_a}" saiman "${super_pw}" saiman
expect "pre-existing superuser-owned table adopted" "ledger_owner" "$(q "${container_a}" saiman "${super_pw}" saiman -c "select tableowner from pg_tables where schemaname='ledger' and tablename='legacy_marker'")"
expect "superuser keeps its password (file secret)" "1" "$(q "${container_a}" saiman "${super_pw}" saiman -c "select 1")"
expect "postgres maintenance db closed to PUBLIC" "f" "$(q "${container_a}" saiman "${super_pw}" saiman -c "select has_database_privilege('ingest_app','postgres','CONNECT')")"
if bootstrap "${container_a}" saiman "${super_pw}" saiman -e BOOTSTRAP_SECRET_SOURCE=env >/dev/null 2>&1; then
  bad "env mode without PW_* variables should fail"
else
  ok "env mode without PW_* variables fails fast"
fi

# ---------------------------------------------------------------- (c) corpus round trip (same container)
echo "(c) corpus export -> restore round trip"
migrate_ingest "${container_a}" saiman
q "${container_a}" ingest_owner "${pw[ingest_owner]}" saiman >/dev/null <<'SQL'
SET search_path = ingest, public;
INSERT INTO source_document (id, source, external_id, ticker, title, disclosure_class, disclosure_type, source_url, published_at, retrieved_at, chunk_count, status)
VALUES ('kap:1', 'kap', '1', 'THYAO', 'Ozel Durum Aciklamasi', 'FR', 'Finansal Rapor', 'https://example.test/1', '2023-05-01T10:00:00Z', '2023-12-01T00:00:00Z', 2, 'INDEXED'),
       ('kap:2', 'kap', '2', 'GARAN', 'Temettu', 'ODA', 'Genel', 'https://example.test/2', '2023-06-01T10:00:00Z', '2023-12-01T00:00:00Z', 1, 'INDEXED');
INSERT INTO chunk (id, content, metadata, embedding)
SELECT 'c' || g, 'Sirket temettu dagitimina karar verdi ' || g,
       jsonb_build_object('documentId', CASE WHEN g <= 2 THEN 'kap:1' ELSE 'kap:2' END, 'ticker', CASE WHEN g <= 2 THEN 'THYAO' ELSE 'GARAN' END),
       array_fill(((g % 7) + 1)::real / 7, ARRAY[1536])::vector
  FROM generate_series(1, 3) g;
INSERT INTO source_cursor (source, ticker, cursor_index, done) VALUES ('kap', 'THYAO', 100, true);
INSERT INTO dead_letter (source, external_id, stage, error_class, error_message, attempts) VALUES ('kap', '9', 'fetch', 'X', 'boom', 3);
SQL

out=/tmp/corpus-out
if docker exec -e PGHOST=127.0.0.1 -e PGUSER=ingest_owner -e PGPASSWORD="${pw[ingest_owner]}" "${container_a}" \
  bash /bootstrap/corpus-export.sh "${out}" >/dev/null; then
  ok "export as ingest_owner (non-superuser) succeeds"
else
  bad "export failed"
fi
expect "dump excludes flyway_schema_history" "0" "$(docker exec "${container_a}" bash -c "pg_restore -l ${out}/ingest-corpus.dump | grep -c flyway_schema_history || true")"
meta="$(docker exec "${container_a}" cat "${out}/ingest-corpus.meta.json")"
expect "metadata flyway_version" "3" "$(q "${container_a}" saiman "${super_pw}" saiman -v m="${meta}" <<<"select (:'m'::jsonb)->>'flyway_version'")"
expect "metadata row counts" "2/3/1/1" "$(q "${container_a}" saiman "${super_pw}" saiman -v m="${meta}" <<<"select concat_ws('/', (:'m'::jsonb)#>>'{row_counts,source_document}', (:'m'::jsonb)#>>'{row_counts,chunk}', (:'m'::jsonb)#>>'{row_counts,source_cursor}', (:'m'::jsonb)#>>'{row_counts,dead_letter}')")"
expect "metadata holds a watermark" "t" "$(q "${container_a}" saiman "${super_pw}" saiman -v m="${meta}" <<<"select (:'m'::jsonb)#>>'{watermark,source_document_updated_at}' is not null")"
if docker exec "${container_a}" grep -q -F -e "${pw[ingest_owner]}" -e "${super_pw}" "${out}/ingest-corpus.meta.json"; then
  bad "metadata leaks a password"
else
  ok "metadata contains no secret"
fi

# Scratch target: a second database, bootstrapped and migrated, empty.
q "${container_a}" saiman "${super_pw}" saiman -c "create database scratch" >/dev/null
q "${container_a}" saiman "${super_pw}" scratch -c "create extension vector with schema public" >/dev/null
bootstrap "${container_a}" saiman "${super_pw}" scratch -e BOOTSTRAP_SECRET_SOURCE=env "${env_args[@]}" >/dev/null
migrate_ingest "${container_a}" scratch

restore() { docker exec -e PGHOST=127.0.0.1 -e PGDATABASE=scratch -e PGSSLMODE=prefer -e PW_INGEST_OWNER="${pw[ingest_owner]}" "${container_a}" bash /bootstrap/corpus-restore.sh "${out}"; }
if restore >/dev/null; then ok "restore into the empty scratch database succeeds"; else bad "restore failed"; fi
expect "restored chunk rows" "3" "$(q "${container_a}" saiman "${super_pw}" scratch -c "select count(*) from ingest.chunk")"
expect "restored documents" "2" "$(q "${container_a}" saiman "${super_pw}" scratch -c "select count(*) from ingest.source_document")"
expect "generated tsvector columns rebuilt" "3" "$(q "${container_a}" saiman "${super_pw}" scratch -c "select count(*) from ingest.chunk where content_tsv @@ to_tsquery('turkish','temettu')")"
expect "restore did not copy the Flyway history" "3" "$(q "${container_a}" saiman "${super_pw}" scratch -c "select count(*) from ingest.flyway_schema_history")"
second="$(restore 2>&1)"
if [[ "${second}" == *"skipping restore"* ]]; then ok "second restore is a no-op"; else bad "second restore did not skip (${second})"; fi
expect "row count unchanged after second restore" "3" "$(q "${container_a}" saiman "${super_pw}" scratch -c "select count(*) from ingest.chunk")"

q "${container_a}" saiman "${super_pw}" scratch -c "truncate ingest.chunk, ingest.source_document, ingest.source_cursor, ingest.dead_letter" >/dev/null
q "${container_a}" saiman "${super_pw}" scratch -c "update ingest.flyway_schema_history set version='4' where installed_rank=3" >/dev/null
if restore >/dev/null 2>&1; then bad "restore must refuse a Flyway version mismatch"; else ok "restore refuses a Flyway version mismatch"; fi
expect "nothing restored on mismatch" "0" "$(q "${container_a}" saiman "${super_pw}" scratch -c "select count(*) from ingest.chunk")"

# ---------------------------------------------------------------- (b) emulated rds_superuser
echo "(b) non-superuser (rds_superuser emulation) bootstrap"
start "${container_b}" postgres "${super_pw}"
q "${container_b}" postgres "${super_pw}" saiman >/dev/null <<SQL
CREATE ROLE rdsmaster LOGIN NOSUPERUSER CREATEROLE CREATEDB PASSWORD '${master_pw}';
ALTER DATABASE saiman OWNER TO rdsmaster;
SQL
if bootstrap "${container_b}" rdsmaster "${master_pw}" saiman -e BOOTSTRAP_SECRET_SOURCE=env -e BOOTSTRAP_RDS=1 "${env_args[@]}" >/dev/null 2>&1; then
  bad "RDS-mode bootstrap must fail while the vector extension is missing"
else
  ok "RDS-mode bootstrap fails when the vector extension is missing"
fi
# On RDS the extension is created by the master user (rds_superuser may create trusted extensions);
# in this emulation the real superuser does it, as the image's vector is not marked trusted.
q "${container_b}" postgres "${super_pw}" saiman -c "create extension vector with schema public" >/dev/null
for run in 1 2; do
  if bootstrap "${container_b}" rdsmaster "${master_pw}" saiman -e BOOTSTRAP_SECRET_SOURCE=env -e BOOTSTRAP_RDS=1 "${env_args[@]}" >/dev/null 2>&1; then
    ok "RDS-mode bootstrap run ${run} succeeds"
  else
    bad "RDS-mode bootstrap run ${run} failed"
    bootstrap "${container_b}" rdsmaster "${master_pw}" saiman -e BOOTSTRAP_SECRET_SOURCE=env -e BOOTSTRAP_RDS=1 "${env_args[@]}" 2>&1 | sed 's/^/    /' >&2 || true
  fi
done
verify_bootstrap "${container_b}" rdsmaster "${master_pw}" saiman
expect "master user is still not a superuser" "f" "$(q "${container_b}" rdsmaster "${master_pw}" saiman -c "select rolsuper from pg_roles where rolname='rdsmaster'")"
expect "master password untouched" "1" "$(q "${container_b}" rdsmaster "${master_pw}" saiman -c "select 1")"
expect "ingest_owner can create tables in ingest" "t" "$(q "${container_b}" ingest_owner "${pw[ingest_owner]}" saiman -c "create table ingest.t(id int); select true")"
if q "${container_b}" ingest_app "${pw[ingest_app]}" saiman -c "create table ingest.t2(id int)" >/dev/null 2>&1; then
  bad "ingest_app must not create tables"
else
  ok "ingest_app cannot create tables"
fi

echo
echo "test-bootstrap-roles: ${pass} checks passed, ${failed} failed"
[[ "${failed}" -eq 0 ]]
