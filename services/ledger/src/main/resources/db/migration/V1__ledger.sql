-- Ledger schema (ADR-0017, docs/design/m4-ledger.md). Flyway runs this with default schema `ledger`,
-- so unqualified names land there; function bodies name the schema explicitly through the
-- ${flyway:defaultSchema} placeholder because they run with the caller's search_path.

-- Chart of accounts. Accounts are created on first use (USDC, 6 decimals, lower-case addresses).
CREATE TABLE account (
    code       text        NOT NULL CHECK (length(code) BETWEEN 1 AND 128),
    book       text        NOT NULL CHECK (book IN ('BUYER', 'SELLER', 'PLATFORM')),
    type       text        NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'REVENUE', 'EXPENSE', 'SUSPENSE')),
    asset      text        NOT NULL CHECK (asset ~ '^[A-Z0-9]{2,10}$'),
    decimals   integer     NOT NULL CHECK (decimals BETWEEN 0 AND 18),
    wallet     text        CHECK (wallet ~ '^0x[0-9a-f]{40}$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (code, asset),
    UNIQUE (code, asset, decimals)
);

-- Per-payment projection: one row per EIP-3009 authorization (payment key network:asset:payer:nonce).
-- The only mutable table; it decides which entries an event still implies (ADR-0017).
CREATE TABLE payment (
    id                uuid        PRIMARY KEY,
    payment_key       text        NOT NULL UNIQUE,
    network           text        NOT NULL CHECK (network = 'eip155:84532'),
    asset_address     text        NOT NULL CHECK (asset_address ~ '^0x[0-9a-f]{40}$'),
    payer             text        NOT NULL CHECK (payer ~ '^0x[0-9a-f]{40}$'),
    nonce             text        NOT NULL CHECK (nonce ~ '^0x[0-9a-f]{64}$'),
    pay_to            text        NOT NULL CHECK (pay_to ~ '^0x[0-9a-f]{40}$'),
    amount_atomic     bigint      NOT NULL CHECK (amount_atomic > 0),
    asset             text        NOT NULL,
    decimals          integer     NOT NULL,
    valid_before      bigint      NOT NULL CHECK (valid_before > 0),
    payment_intent_id uuid,
    run_id            uuid,
    buyer_state       text        NOT NULL CHECK (buyer_state IN ('NONE', 'AUTHORIZED', 'SETTLED', 'RELEASED')),
    seller_state      text        NOT NULL CHECK (seller_state IN ('NONE', 'SETTLED', 'SETTLE_FAILED')),
    chain_state       text        NOT NULL DEFAULT 'UNKNOWN' CHECK (chain_state IN ('UNKNOWN', 'USED', 'UNUSED')),
    buyer_tx_hash     text        CHECK (buyer_tx_hash ~ '^0x[0-9a-f]{64}$'),
    seller_tx_hash    text        CHECK (seller_tx_hash ~ '^0x[0-9a-f]{64}$'),
    chain_tx_hash     text        CHECK (chain_tx_hash ~ '^0x[0-9a-f]{64}$'),
    last_checked_at   timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX payment_due_for_reconciliation ON payment (last_checked_at NULLS FIRST, valid_before);

CREATE TABLE journal_entry (
    id                uuid        PRIMARY KEY,
    payment_id        uuid        REFERENCES payment (id),
    payment_key       text,
    book              text        NOT NULL CHECK (book IN ('BUYER', 'SELLER', 'PLATFORM')),
    kind              text        NOT NULL CHECK (kind IN ('ENCUMBER', 'SETTLE', 'RELEASE', 'SALE', 'CREDIT_NOTE',
                                                           'ADJUSTMENT', 'REVERSAL', 'LLM_USAGE')),
    source_event_id   text,
    reverses_entry_id uuid        REFERENCES journal_entry (id),
    description       text        NOT NULL CHECK (length(description) BETWEEN 1 AND 256),
    effective_at      timestamptz NOT NULL,
    recorded_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK ((kind = 'REVERSAL') = (reverses_entry_id IS NOT NULL))
);

-- Business-key idempotency: each one-per-payment kind is posted at most once per book (ADR-0016's second layer).
CREATE UNIQUE INDEX journal_entry_once_per_payment ON journal_entry (payment_key, book, kind)
    WHERE kind IN ('ENCUMBER', 'SETTLE', 'RELEASE', 'SALE', 'CREDIT_NOTE');
-- An entry is reversed at most once.
CREATE UNIQUE INDEX journal_entry_reversed_once ON journal_entry (reverses_entry_id)
    WHERE reverses_entry_id IS NOT NULL;
CREATE INDEX journal_entry_by_payment ON journal_entry (payment_id);

CREATE TABLE posting (
    id            bigint  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id      uuid    NOT NULL REFERENCES journal_entry (id),
    account_code  text    NOT NULL,
    side          text    NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
    amount_atomic bigint  NOT NULL CHECK (amount_atomic > 0),
    asset         text    NOT NULL,
    decimals      integer NOT NULL,
    FOREIGN KEY (account_code, asset, decimals) REFERENCES account (code, asset, decimals)
);

CREATE INDEX posting_by_entry ON posting (entry_id);
CREATE INDEX posting_by_account ON posting (account_code, asset);

-- Balanced postings, checked at COMMIT (deferred), so an entry and its postings can be inserted in any order
-- inside one transaction: every entry touched by the transaction has >= 2 postings and, per asset,
-- debits equal credits. Fires for new entries (catches an entry without postings) and new postings (catches a
-- posting added to an older entry).
CREATE FUNCTION check_entry_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    v_entry      uuid;
    v_postings   integer;
    v_unbalanced integer;
BEGIN
    IF TG_TABLE_NAME = 'posting' THEN
        v_entry := NEW.entry_id;
    ELSE
        v_entry := NEW.id;
    END IF;

    SELECT count(*) INTO v_postings FROM ${flyway:defaultSchema}.posting WHERE entry_id = v_entry;
    IF v_postings < 2 THEN
        RAISE EXCEPTION 'journal entry % has % posting(s); at least two are required', v_entry, v_postings
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT count(*) INTO v_unbalanced
      FROM (SELECT asset, decimals
              FROM ${flyway:defaultSchema}.posting
             WHERE entry_id = v_entry
             GROUP BY asset, decimals
            HAVING sum(CASE side WHEN 'DEBIT' THEN amount_atomic ELSE -amount_atomic END) <> 0) AS unbalanced;
    IF v_unbalanced > 0 THEN
        RAISE EXCEPTION 'journal entry % is unbalanced: debits differ from credits for % asset(s)', v_entry, v_unbalanced
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER journal_entry_balanced
    AFTER INSERT ON journal_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_entry_balanced();

CREATE CONSTRAINT TRIGGER posting_balanced
    AFTER INSERT ON posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_entry_balanced();

-- Append-only: corrections are REVERSAL / ADJUSTMENT entries, never edits (ADR-0017). These are ordinary
-- (ENABLE ORIGIN) triggers, so `session_replication_role = replica` still disables them: that is the gap
-- scripts/ledger-tamper-demo.sh uses on purpose (per-service DB roles are M6).
CREATE FUNCTION reject_ledger_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '%.% is append-only: % is not allowed; post a reversing entry instead',
        TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER journal_entry_immutable
    BEFORE UPDATE OR DELETE ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_change();
CREATE TRIGGER journal_entry_no_truncate
    BEFORE TRUNCATE ON journal_entry
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_change();
CREATE TRIGGER posting_immutable
    BEFORE UPDATE OR DELETE ON posting
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_change();
CREATE TRIGGER posting_no_truncate
    BEFORE TRUNCATE ON posting
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_change();

-- Reconciliation (T5 fills these; ADR-0018).
CREATE TABLE reconciliation_run (
    id              uuid        PRIMARY KEY,
    started_at      timestamptz NOT NULL,
    finished_at     timestamptz,
    status          text        NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED')),
    network         text        NOT NULL CHECK (network = 'eip155:84532'),
    safe_block      bigint,
    checked         integer     NOT NULL DEFAULT 0,
    matched         integer     NOT NULL DEFAULT 0,
    pending         integer     NOT NULL DEFAULT 0,
    resolved_used   integer     NOT NULL DEFAULT 0,
    resolved_unused integer     NOT NULL DEFAULT 0,
    mismatches      integer     NOT NULL DEFAULT 0
);

CREATE TABLE reconciliation_mismatch (
    id                  uuid        PRIMARY KEY,
    run_id              uuid        NOT NULL REFERENCES reconciliation_run (id),
    payment_id          uuid        NOT NULL REFERENCES payment (id),
    kind                text        NOT NULL CHECK (kind IN ('AMOUNT_MISMATCH', 'PARTY_MISMATCH', 'TX_NOT_FOUND',
                                                             'TX_FAILED', 'TX_NOT_FOR_AUTHORIZATION',
                                                             'SETTLED_BUT_UNUSED', 'UNUSED_BUT_SETTLED',
                                                             'CONFLICTING_TX', 'ENCUMBRANCE_NOT_CLEARED')),
    ledger_amount_atomic bigint,
    chain_amount_atomic  bigint,
    asset               text,
    decimals            integer,
    reported_tx_hash    text,
    chain_tx_hash       text,
    adjustment_entry_id uuid        REFERENCES journal_entry (id),
    detected_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (payment_id, kind)
);

-- Consumer dedupe (libs/eventing InboxGuard, ADR-0016).
CREATE TABLE inbox (
    event_id    text        NOT NULL,
    consumer    text        NOT NULL,
    topic       text        NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);

-- Spring Modulith 2.1 JDBC event publication registry (the outbox for ledger.* events), copied from
-- spring-modulith-events-jdbc 2.1.1 schemas/v2/schema-postgresql.sql so Flyway owns it in this schema
-- (Modulith's own schema initialisation stays off).
CREATE TABLE event_publication (
    id                     uuid                     NOT NULL,
    listener_id            text                     NOT NULL,
    event_type             text                     NOT NULL,
    serialized_event       text                     NOT NULL,
    publication_date       timestamp with time zone NOT NULL,
    completion_date        timestamp with time zone,
    status                 text,
    completion_attempts    integer,
    last_resubmission_date timestamp with time zone,
    PRIMARY KEY (id)
);
CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);
