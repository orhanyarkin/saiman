-- V1: the seller's settlement records and the transactional outbox (ADR-0016, docs/design/m4-ledger.md).

-- One row per paid authorization the seller reported: the payment key is network:asset:payer:nonce, lower-case
-- (AuthorizationRef.paymentKey()). A replayed report of the same authorization inserts nothing.
CREATE TABLE settlement
(
    payment_key   text PRIMARY KEY,
    tx_hash       text,
    amount_atomic bigint      NOT NULL CHECK (amount_atomic > 0),
    pay_to        text        NOT NULL,
    payer         text        NOT NULL,
    outcome       text        NOT NULL CHECK (outcome IN ('SETTLED', 'SETTLE_FAILED')),
    reason_code   text,
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- Spring Modulith 2.1 JDBC event publication registry, its v2 structure (schemas/v2/schema-postgresql.sql in
-- spring-modulith-events-jdbc 2.1.1), owned by Flyway so the table lives in this schema and changes only through a
-- migration (the same DDL as the orchestrator's V6). Written in the transaction that records the settlement and
-- deleted once Kafka acknowledged the publication (completion-mode DELETE).
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
