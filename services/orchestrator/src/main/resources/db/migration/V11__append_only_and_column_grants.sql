-- V11: narrow the runtime role's grants further (ADR-0024, milestone audit L3). V10 stays untouched (applied).
--
-- Derived from the code (grep of src/main for UPDATE / DELETE / ON CONFLICT):
--   run_event, tool_result, payment_event_log   INSERT only (ON CONFLICT DO NOTHING at most): append-only.
--   event_publication                            Modulith's registry updates and deletes its own rows: unchanged.
--   run, spend_day, payment_intent, approval     UPDATE only on the state and counter columns listed below.
-- Everything else a row says when it is inserted (a run's question and budgets, an intent's key, tool, args hash and
-- resource, the amounts of an approval) cannot be changed by the running service afterwards.

REVOKE UPDATE, DELETE, TRUNCATE ON ${flyway:defaultSchema}.run_event FROM ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON ${flyway:defaultSchema}.tool_result FROM ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON ${flyway:defaultSchema}.payment_event_log FROM ${app_role};

-- run: the lifecycle (status, started_at, trace_id, result, failure_code, finished_at), the spend guard's counters
-- (reserved_atomic, committed_atomic; committed only grows, see the V10 trigger), the LLM cost, and the event sequence.
-- Not question, budget_atomic, llm_budget_usd_micros, created_at.
REVOKE UPDATE ON ${flyway:defaultSchema}.run FROM ${app_role};
GRANT UPDATE (status, started_at, finished_at, trace_id, result, failure_code, reserved_atomic, committed_atomic,
              llm_cost_usd_micros, next_seq)
    ON ${flyway:defaultSchema}.run TO ${app_role};

REVOKE UPDATE ON ${flyway:defaultSchema}.spend_day FROM ${app_role};
GRANT UPDATE (reserved_atomic, committed_atomic) ON ${flyway:defaultSchema}.spend_day TO ${app_role};

-- approval: only the decision. amount_atomic, pay_to, resource, payment_intent_id and run_id are what the human saw.
REVOKE UPDATE ON ${flyway:defaultSchema}.approval FROM ${app_role};
GRANT UPDATE (status, decided_at, decided_by) ON ${flyway:defaultSchema}.approval TO ${app_role};

-- payment_intent: the state machine and the facts it learns on the way (offer, authorization, settlement). Not id,
-- run_id, idempotency_key, tool, args_hash, resource, created_at.
REVOKE UPDATE ON ${flyway:defaultSchema}.payment_intent FROM ${app_role};
GRANT UPDATE (status, deny_reason, amount_atomic, pay_to, network, asset, reserved_day, payer, auth_nonce,
              valid_before, tx_hash, resolved_by, resolved_at, resolution_attempted_at, updated_at)
    ON ${flyway:defaultSchema}.payment_intent TO ${app_role};

-- Tables added later start append-only; a migration that needs UPDATE or DELETE grants it explicitly.
ALTER DEFAULT PRIVILEGES IN SCHEMA ${flyway:defaultSchema} REVOKE UPDATE ON TABLES FROM ${app_role};
