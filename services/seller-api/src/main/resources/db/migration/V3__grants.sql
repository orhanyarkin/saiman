-- V3: runtime grants (ADR-0024). Flyway runs as the schema owner (seller_api_owner); the running service connects as
-- ${app_role} (DML only: no DDL, no TRUNCATE, no trigger control, no access to Flyway's history table). The role name
-- comes from the Flyway placeholder app_role (spring.flyway.placeholders.app_role).
GRANT USAGE ON SCHEMA ${flyway:defaultSchema} TO ${app_role};

-- settlement: the seller's book of reported settlements. Inserted, and upgraded in place from SETTLE_FAILED to
-- SETTLED (SettlementRecorder's ON CONFLICT DO UPDATE sets exactly these three columns); never deleted by the service.
-- UPDATE is granted per column, so the amount, payer, payee, key and timestamp can not be rewritten.
GRANT SELECT, INSERT ON ${flyway:defaultSchema}.settlement TO ${app_role};
REVOKE UPDATE ON ${flyway:defaultSchema}.settlement FROM ${app_role};
GRANT UPDATE (tx_hash, outcome, reason_code) ON ${flyway:defaultSchema}.settlement TO ${app_role};

-- And the only update the book knows is the upgrade SETTLE_FAILED -> SETTLED (owner-owned trigger; only a superuser
-- can switch it off).
CREATE FUNCTION ${flyway:defaultSchema}.settlement_upgrade_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF OLD.outcome = 'SETTLE_FAILED' AND NEW.outcome = 'SETTLED' THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'settlement rows only change from SETTLE_FAILED to SETTLED' USING ERRCODE = '42501';
END;
$$;

CREATE TRIGGER settlement_upgrade_only
    BEFORE UPDATE ON ${flyway:defaultSchema}.settlement
    FOR EACH ROW
EXECUTE FUNCTION ${flyway:defaultSchema}.settlement_upgrade_only();

-- credit_note: what the seller owes buyers (ADR-0021), the evidence the ledger corroborates credit notes against.
-- Append-only for the service (ON CONFLICT DO NOTHING needs INSERT only): no UPDATE, no DELETE.
GRANT SELECT, INSERT ON ${flyway:defaultSchema}.credit_note TO ${app_role};

-- event_publication: the Modulith outbox (ADR-0016). Completed publications are deleted, resubmissions update.
GRANT SELECT, INSERT, UPDATE, DELETE ON ${flyway:defaultSchema}.event_publication TO ${app_role};

GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA ${flyway:defaultSchema} TO ${app_role};

-- Tables created by later migrations (run by the same owner) get plain DML automatically; a later append-only table
-- must REVOKE what it does not want, as above.
ALTER DEFAULT PRIVILEGES IN SCHEMA ${flyway:defaultSchema} GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${app_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA ${flyway:defaultSchema} GRANT USAGE, SELECT ON SEQUENCES TO ${app_role};

-- Defence in depth for adopted pre-M6 objects that may carry older grants: take back what the service must not do.
REVOKE DELETE, TRUNCATE, REFERENCES, TRIGGER ON ${flyway:defaultSchema}.settlement FROM ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON ${flyway:defaultSchema}.credit_note FROM ${app_role};
REVOKE TRUNCATE, REFERENCES, TRIGGER ON ${flyway:defaultSchema}.event_publication FROM ${app_role};

-- Flyway's own bookkeeping is the owner's business only.
REVOKE ALL ON ${flyway:defaultSchema}.flyway_schema_history FROM ${app_role};
