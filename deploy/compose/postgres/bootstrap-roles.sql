-- Per-service Postgres roles, schemas and ownership adoption (ADR-0024).
-- Executed by bootstrap-roles.sh as the superuser; idempotent. Passwords arrive as the
-- environment variables PW_<SERVICE>_<OWNER|APP> and are read with \getenv (never printed).
--
-- Grants inside the schemas (DML on tables, the ledger's INSERT-only postings, ...) are NOT
-- here: each service versions them in its own Flyway migration, run by <svc>_owner.

\getenv pw_orchestrator_owner PW_ORCHESTRATOR_OWNER
\getenv pw_orchestrator_app PW_ORCHESTRATOR_APP
\getenv pw_ledger_owner PW_LEDGER_OWNER
\getenv pw_ledger_app PW_LEDGER_APP
\getenv pw_seller_api_owner PW_SELLER_API_OWNER
\getenv pw_seller_api_app PW_SELLER_API_APP
\getenv pw_ingest_owner PW_INGEST_OWNER
\getenv pw_ingest_app PW_INGEST_APP

-- Role -> password pairs (session-local; dropped with the connection).
CREATE TEMP TABLE bootstrap_role (name text PRIMARY KEY, svc text NOT NULL, pw text NOT NULL);
INSERT INTO bootstrap_role (name, svc, pw) VALUES
  ('orchestrator_owner', 'orchestrator', :'pw_orchestrator_owner'),
  ('orchestrator_app',   'orchestrator', :'pw_orchestrator_app'),
  ('ledger_owner',       'ledger',       :'pw_ledger_owner'),
  ('ledger_app',         'ledger',       :'pw_ledger_app'),
  ('seller_api_owner',   'seller_api',   :'pw_seller_api_owner'),
  ('seller_api_app',     'seller_api',   :'pw_seller_api_app'),
  ('ingest_owner',       'ingest',       :'pw_ingest_owner'),
  ('ingest_app',         'ingest',       :'pw_ingest_app');

-- 1. Roles. CREATE ROLE has no IF NOT EXISTS; the attributes and the password are re-applied on
--    every run, so a rotated secret file takes effect with `make db-roles`.
SELECT format('CREATE ROLE %I LOGIN', name) FROM bootstrap_role
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles r WHERE r.rolname = bootstrap_role.name) \gexec
SELECT format('ALTER ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', name, pw)
  FROM bootstrap_role \gexec
-- Search path per role; the ingest roles also see `public`, where pgvector lives.
SELECT format('ALTER ROLE %I SET search_path = %s', name,
              CASE WHEN svc = 'ingest' THEN 'ingest, public' ELSE svc END)
  FROM bootstrap_role \gexec

-- 2. Schemas owned by the owner role (pre-M6 schemas belong to the superuser: adopt them).
SELECT format('CREATE SCHEMA IF NOT EXISTS %I AUTHORIZATION %I', svc, svc || '_owner')
  FROM (SELECT DISTINCT svc FROM bootstrap_role) s \gexec
SELECT format('ALTER SCHEMA %I OWNER TO %I', svc, svc || '_owner')
  FROM (SELECT DISTINCT svc FROM bootstrap_role) s \gexec
SELECT format('GRANT USAGE ON SCHEMA %I TO %I', svc, svc || '_app')
  FROM (SELECT DISTINCT svc FROM bootstrap_role) s \gexec

-- 3. Adopt pre-M6 objects: everything the superuser created in a service schema (tables incl.
--    flyway_schema_history and the Modulith event_publication tables, standalone sequences,
--    views, materialized views, functions, procedures, aggregates, enums/domains/composites)
--    becomes owned by <svc>_owner. Sequences owned by a column (serial/identity) follow their
--    table and are skipped; extension members stay superuser-owned.
DO $adopt$
DECLARE
  s text;
  o text;
  r record;
BEGIN
  FOR s IN SELECT DISTINCT svc FROM bootstrap_role LOOP
    o := s || '_owner';

    -- tables, partitioned tables, foreign tables, then views and materialized views
    FOR r IN
      SELECT c.relname, c.relkind
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
       WHERE n.nspname = s
         AND c.relkind IN ('r', 'p', 'f', 'v', 'm')
         AND pg_get_userbyid(c.relowner) <> o
         AND NOT EXISTS (SELECT 1 FROM pg_depend d
                          WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e')
       ORDER BY c.relkind IN ('v', 'm'), c.relname
    LOOP
      EXECUTE format('ALTER %s %I.%I OWNER TO %I',
        CASE r.relkind WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW'
                       WHEN 'f' THEN 'FOREIGN TABLE' ELSE 'TABLE' END,
        s, r.relname, o);
    END LOOP;

    -- standalone sequences (not owned by a column)
    FOR r IN
      SELECT c.relname
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
       WHERE n.nspname = s
         AND c.relkind = 'S'
         AND pg_get_userbyid(c.relowner) <> o
         AND NOT EXISTS (SELECT 1 FROM pg_depend d
                          WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype IN ('a', 'i', 'e'))
    LOOP
      EXECUTE format('ALTER SEQUENCE %I.%I OWNER TO %I', s, r.relname, o);
    END LOOP;

    -- functions, procedures, aggregates
    FOR r IN
      SELECT p.oid::regprocedure::text AS sig, p.prokind
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
       WHERE n.nspname = s
         AND pg_get_userbyid(p.proowner) <> o
         AND NOT EXISTS (SELECT 1 FROM pg_depend d
                          WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e')
    LOOP
      EXECUTE format('ALTER %s %s OWNER TO %I',
        CASE r.prokind WHEN 'p' THEN 'PROCEDURE' WHEN 'a' THEN 'AGGREGATE' ELSE 'FUNCTION' END,
        r.sig, o);
    END LOOP;

    -- enums, ranges, standalone composite types and domains (array and row types follow their base)
    FOR r IN
      SELECT t.typname, t.typtype
        FROM pg_type t
        JOIN pg_namespace n ON n.oid = t.typnamespace
        LEFT JOIN pg_class c ON c.oid = t.typrelid
       WHERE n.nspname = s
         AND (t.typtype IN ('e', 'r', 'd') OR (t.typtype = 'c' AND c.relkind = 'c'))
         AND pg_get_userbyid(t.typowner) <> o
         AND NOT EXISTS (SELECT 1 FROM pg_depend d
                          WHERE d.classid = 'pg_type'::regclass AND d.objid = t.oid AND d.deptype = 'e')
    LOOP
      EXECUTE format('ALTER %s %I.%I OWNER TO %I',
        CASE r.typtype WHEN 'd' THEN 'DOMAIN' ELSE 'TYPE' END, s, r.typname, o);
    END LOOP;
  END LOOP;
END
$adopt$;

-- 4. Database access: nobody but the superuser and the eight roles may connect.
REVOKE ALL ON DATABASE saiman FROM PUBLIC;
SELECT format('GRANT CONNECT ON DATABASE saiman TO %I', name) FROM bootstrap_role \gexec

-- 5. pgvector stays superuser-owned in `public` (the ingest roles reach it via their search_path).
--    A volume where an older migration put it in another schema keeps it there (no move).
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
