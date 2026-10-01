-- V6: payment events through the transactional outbox (ADR-0016) and HELD resolution (ADR-0013 amendment,
-- ADR-0018, docs/design/m4-ledger.md).

-- Spring Modulith 2.1 JDBC event publication registry, its v2 structure (schemas/v2/schema-postgresql.sql in
-- spring-modulith-events-jdbc 2.1.1), owned by Flyway instead of Modulith's own initializer so the table lives
-- in this schema and changes only through a migration. A publication is written in the same transaction as the
-- state change that published the event and deleted once Kafka acknowledged it (completion-mode DELETE).
CREATE TABLE event_publication
(
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

-- How a SETTLED or RELEASED intent was resolved: the facilitator's /settle answer, or a chain read of
-- authorizationState past validBefore (HeldPaymentResolver).
ALTER TABLE payment_intent
    ADD COLUMN resolved_by text
        CONSTRAINT payment_intent_resolved_by_valid CHECK (resolved_by IS NULL OR resolved_by IN ('FACILITATOR', 'CHAIN')),
    ADD COLUMN resolved_at timestamptz;

-- One EIP-3009 authorization is one payment: the payment key (network:asset:payer:nonce) must be unique.
CREATE UNIQUE INDEX payment_intent_authorization_unique
    ON payment_intent (lower(payer), lower(auth_nonce))
    WHERE auth_nonce IS NOT NULL;

-- The resolver's work list.
CREATE INDEX payment_intent_held_idx ON payment_intent (valid_before) WHERE status = 'HELD';

-- M3 history: every SETTLED intent was settled by the facilitator's /settle answer.
UPDATE payment_intent SET resolved_by = 'FACILITATOR', resolved_at = updated_at WHERE status = 'SETTLED';

-- One row per (intent, event kind) published through the outbox, inserted in the transaction that publishes
-- the event: a payment event is published at most once (live or by the startup backfill of M3 history), and
-- the backfill finds what is missing with an anti-join.
CREATE TABLE payment_event_log
(
    payment_intent_id uuid        NOT NULL REFERENCES payment_intent (id),
    kind              text        NOT NULL
        CONSTRAINT payment_event_log_kind_valid CHECK (kind IN ('AUTHORIZED', 'SETTLED', 'FAILED')),
    event_id          uuid        NOT NULL CONSTRAINT payment_event_log_event_unique UNIQUE,
    published_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (payment_intent_id, kind)
);
