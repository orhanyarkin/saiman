-- V8: narrow the runtime role's writes to what the code does (milestone audit L3; ADR-0024). V7 is applied on live
-- databases and stays as it is; this migration only takes privileges away. Runs as ledger_owner.

-- payment: never deleted; only the projection columns PaymentRepository.update and the reconciliation updates write.
-- The authorization facts (payment_key, payer, nonce, amount_atomic, asset, network, pay_to, valid_*) stay frozen.
-- Column-level UPDATE still allows SELECT ... FOR UPDATE (it needs UPDATE on at least one column).
REVOKE UPDATE, DELETE, TRUNCATE ON TABLE ${flyway:defaultSchema}.payment FROM ${app_role};
GRANT UPDATE (payment_intent_id, run_id, buyer_state, seller_state, chain_state, buyer_tx_hash, seller_tx_hash,
              chain_tx_hash, chain_block, last_checked_at, updated_at)
    ON TABLE ${flyway:defaultSchema}.payment TO ${app_role};

-- reconciliation_run: never deleted; failInterruptedRuns and finishRun write only the outcome columns.
REVOKE UPDATE, DELETE, TRUNCATE ON TABLE ${flyway:defaultSchema}.reconciliation_run FROM ${app_role};
GRANT UPDATE (status, finished_at, safe_block, checked, matched, pending, resolved_used, resolved_unused, mismatches)
    ON TABLE ${flyway:defaultSchema}.reconciliation_run TO ${app_role};

-- event_publication keeps full DML: Spring Modulith updates completion and deletes completed publications
-- (completion-mode DELETE, libs/eventing). It is the only table ledger_app may DELETE from.
REVOKE TRUNCATE ON TABLE ${flyway:defaultSchema}.event_publication FROM ${app_role};

-- Future tables: read and insert only; a migration that needs more grants it explicitly.
ALTER DEFAULT PRIVILEGES IN SCHEMA ${flyway:defaultSchema}
    REVOKE UPDATE, DELETE ON TABLES FROM ${app_role};
