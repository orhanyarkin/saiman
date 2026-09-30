-- V2: runs, their event log and the spend-control plane (ADR-0013, ADR-0014, docs/design/m3-orchestrator.md).
--
-- Postgres is the authority for every money decision: the budget checks run under row locks in
-- one transaction, and the CHECK constraints below back the invariants the code enforces.
-- Lock order everywhere: approval -> payment_intent -> run -> spend_day (no deadlock).

CREATE TABLE run (
    id                    uuid PRIMARY KEY,
    question              text        NOT NULL,
    status                text        NOT NULL
        CONSTRAINT run_status_valid CHECK (status IN ('QUEUED', 'RUNNING', 'AWAITING_APPROVAL', 'SUCCEEDED', 'FAILED')),
    -- USDC atomic units (6 decimals). Set once at POST /runs; the trigger below makes it immutable.
    budget_atomic         bigint      NOT NULL CONSTRAINT run_budget_positive CHECK (budget_atomic > 0),
    reserved_atomic       bigint      NOT NULL DEFAULT 0 CONSTRAINT run_reserved_non_negative CHECK (reserved_atomic >= 0),
    committed_atomic      bigint      NOT NULL DEFAULT 0 CONSTRAINT run_committed_non_negative CHECK (committed_atomic >= 0),
    -- USD micro-dollars (6 decimals); the router's ScopedCostGuard enforces it (ADR-0011 amendment).
    llm_budget_usd_micros bigint      NOT NULL CONSTRAINT run_llm_budget_positive CHECK (llm_budget_usd_micros > 0),
    llm_cost_usd_micros   bigint      NOT NULL DEFAULT 0 CONSTRAINT run_llm_cost_non_negative CHECK (llm_cost_usd_micros >= 0),
    -- The last seq handed out; "UPDATE run SET next_seq = next_seq + 1 RETURNING next_seq" yields 1, 2, ...
    next_seq              integer     NOT NULL DEFAULT 0,
    trace_id              text,
    result                jsonb,
    failure_code          text,
    created_at            timestamptz NOT NULL DEFAULT now(),
    started_at            timestamptz,
    finished_at           timestamptz,
    -- Held reservations stay in reserved_atomic, so this bounds everything that may still settle.
    CONSTRAINT run_spend_within_budget CHECK (reserved_atomic + committed_atomic <= budget_atomic)
);

-- No code path, endpoint or model output may change a run's budget once it exists.
CREATE FUNCTION run_budget_is_immutable() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.budget_atomic IS DISTINCT FROM OLD.budget_atomic THEN
        RAISE EXCEPTION 'run.budget_atomic is immutable' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER run_budget_immutable
    BEFORE UPDATE ON run
    FOR EACH ROW
EXECUTE FUNCTION run_budget_is_immutable();

CREATE TABLE run_event (
    run_id     uuid        NOT NULL REFERENCES run (id),
    seq        integer     NOT NULL CONSTRAINT run_event_seq_positive CHECK (seq >= 1),
    type       text        NOT NULL,
    payload    jsonb       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, seq)
);

CREATE TABLE payment_intent (
    id              uuid PRIMARY KEY,
    run_id          uuid        NOT NULL REFERENCES run (id),
    -- 128-bit SecureRandom, base64url; never derived from model text. Never logged or put in an event.
    idempotency_key text        NOT NULL CONSTRAINT payment_intent_key_unique UNIQUE,
    tool            text        NOT NULL,
    args_hash       text        NOT NULL,
    -- The full request URI, built by code from configuration; reserve() refuses any other resource.
    resource        text        NOT NULL,
    status          text        NOT NULL
        CONSTRAINT payment_intent_status_valid CHECK (status IN ('PENDING', 'AWAITING_APPROVAL', 'APPROVED', 'RESERVED',
                                                                 'SIGNED', 'SETTLED', 'HELD', 'RELEASED', 'DENIED',
                                                                 'REJECTED', 'EXPIRED')),
    -- Filled from the seller's 402 offer at reserve time.
    amount_atomic   bigint CONSTRAINT payment_intent_amount_positive CHECK (amount_atomic > 0),
    pay_to          text,
    network         text,
    asset           text,
    -- The UTC spend_day row the reservation was counted on, so commit/release move the same day.
    reserved_day    date,
    -- Recorded by SpendGuard.signed before the signature leaves the process (M4 reconciliation).
    payer           text,
    auth_nonce      text,
    valid_before    bigint,
    tx_hash         text,
    deny_reason     text
        CONSTRAINT payment_intent_deny_reason_valid CHECK (deny_reason IS NULL OR deny_reason IN (
            'RUN_BUDGET', 'DAILY_CAP', 'PAYEE_NOT_ALLOWED', 'OVER_PER_REQUEST_MAX', 'UNKNOWN_INTENT',
            'APPROVAL_REJECTED', 'APPROVAL_EXPIRED', 'MAX_PAID_CALLS', 'INVALID_ARGS')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX payment_intent_run_idx ON payment_intent (run_id);

-- One row per UTC day: the global daily cap counts reserved (including held) and committed spend.
CREATE TABLE spend_day (
    day              date PRIMARY KEY,
    reserved_atomic  bigint NOT NULL DEFAULT 0 CONSTRAINT spend_day_reserved_non_negative CHECK (reserved_atomic >= 0),
    committed_atomic bigint NOT NULL DEFAULT 0 CONSTRAINT spend_day_committed_non_negative CHECK (committed_atomic >= 0)
);

CREATE TABLE approval (
    id                uuid PRIMARY KEY,
    payment_intent_id uuid        NOT NULL CONSTRAINT approval_intent_unique UNIQUE REFERENCES payment_intent (id),
    run_id            uuid        NOT NULL REFERENCES run (id),
    -- What the human approved: the fresh 402 on the re-sent request must match all three.
    amount_atomic     bigint      NOT NULL CONSTRAINT approval_amount_positive CHECK (amount_atomic > 0),
    pay_to            text        NOT NULL,
    resource          text        NOT NULL,
    status            text        NOT NULL
        CONSTRAINT approval_status_valid CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
    requested_at      timestamptz NOT NULL DEFAULT now(),
    decided_at        timestamptz,
    expires_at        timestamptz NOT NULL
);

CREATE INDEX approval_run_idx ON approval (run_id);
