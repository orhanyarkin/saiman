-- V10: least privilege for the running service (ADR-0024).
--
-- Flyway runs this as the schema owner (orchestrator_owner); the service itself connects as ${app_role}
-- (orchestrator_app), which may only read and write rows. It cannot run DDL, drop or disable triggers, replace the
-- trigger functions below, truncate, or read Flyway's history.

GRANT USAGE ON SCHEMA ${flyway:defaultSchema} TO ${app_role};
-- No DELETE by default: deleting a spend_day row would reset the global daily cap, deleting a run its budget
-- counters. The only code that deletes rows is Spring Modulith's event publication registry (completion-mode
-- DELETE removes acknowledged publications), so event_publication is the one table granted DELETE below.
-- (grep of src/main finds no other DELETE: payment_event_log, tool_result, approval and the rest are never removed.)
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA ${flyway:defaultSchema} TO ${app_role};
GRANT DELETE ON ${flyway:defaultSchema}.event_publication TO ${app_role};
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA ${flyway:defaultSchema} TO ${app_role};

-- Tables and sequences added by later migrations (created by the owner) get the same grants.
ALTER DEFAULT PRIVILEGES IN SCHEMA ${flyway:defaultSchema}
    GRANT SELECT, INSERT, UPDATE ON TABLES TO ${app_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA ${flyway:defaultSchema}
    GRANT USAGE, SELECT ON SEQUENCES TO ${app_role};

REVOKE ALL ON ${flyway:defaultSchema}.flyway_schema_history FROM ${app_role};

-- committed_atomic only ever grows. The spend guard adds the amount of a settled payment (moveRun/moveDay in
-- BudgetSpendGuard add :committed >= 0); release, recovery and every other path leave it alone, and nothing in the
-- code base decreases it. A statement that would decrease it (a bug, an injection, a hand-written UPDATE by the
-- service's own role) is refused. The function is owned by the schema owner: the service role can neither replace it
-- nor drop or disable the triggers (that needs table ownership). Only a superuser can bypass them, and
-- session_replication_role is superuser-only.
CREATE FUNCTION committed_atomic_is_monotonic() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.committed_atomic < OLD.committed_atomic THEN
        RAISE EXCEPTION '%.committed_atomic must not decrease', TG_TABLE_NAME USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- Defence in depth for the missing DELETE grant: rows of run and spend_day are never deleted, whoever asks (a
-- superuser must set session_replication_role or disable the trigger to do it on purpose).
CREATE FUNCTION counter_rows_are_never_deleted() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% rows must not be deleted', TG_TABLE_NAME USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER run_never_deleted
    BEFORE DELETE ON run
    FOR EACH ROW
EXECUTE FUNCTION counter_rows_are_never_deleted();

CREATE TRIGGER spend_day_never_deleted
    BEFORE DELETE ON spend_day
    FOR EACH ROW
EXECUTE FUNCTION counter_rows_are_never_deleted();

CREATE TRIGGER run_committed_monotonic
    BEFORE UPDATE ON run
    FOR EACH ROW
EXECUTE FUNCTION committed_atomic_is_monotonic();

CREATE TRIGGER spend_day_committed_monotonic
    BEFORE UPDATE ON spend_day
    FOR EACH ROW
EXECUTE FUNCTION committed_atomic_is_monotonic();
