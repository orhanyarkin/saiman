-- M4b audit (ADR-0021): Kafka is unauthenticated until M6, so a CreditNoteIssued is a claim. Reconciliation asks
-- seller-api (GET /internal/credit-notes/{paymentKey}) whether it really issued the credit note.

-- A credit note the seller has no row for, or whose tx hash or amount differs from the ledger's: reported only
-- (nothing posted; the chain did not move). Not part of libs/shared MismatchKind or the published
-- ledger.reconciliation-mismatch.v1 schema (an enum), so it lives in this table and the report only.
ALTER TABLE reconciliation_mismatch DROP CONSTRAINT reconciliation_mismatch_kind_check;
ALTER TABLE reconciliation_mismatch
    ADD CONSTRAINT reconciliation_mismatch_kind_check CHECK (kind IN (
        'AMOUNT_MISMATCH', 'PARTY_MISMATCH', 'TX_NOT_FOUND', 'TX_FAILED', 'TX_NOT_FOR_AUTHORIZATION',
        'SETTLED_BUT_UNUSED', 'UNUSED_BUT_SETTLED', 'CONFLICTING_TX', 'ENCUMBRANCE_NOT_CLEARED',
        'BOOKS_OPEN', 'CONFLICTING_FACT', 'CREDIT_NOTE_UNCORROBORATED'));

-- Positive corroboration, cached so the seller is asked once per payment. Bound to the tx hash and amount the
-- seller confirmed: a row only counts while it equals the ledger's credited values. Insert-only.
CREATE TABLE credit_note_corroboration (
    payment_id      uuid        PRIMARY KEY REFERENCES payment (id),
    tx_hash         text        NOT NULL CHECK (tx_hash ~ '^0x[0-9a-f]{64}$'),
    amount_atomic   bigint      NOT NULL CHECK (amount_atomic > 0 AND amount_atomic <= 9007199254740991),
    corroborated_at timestamptz NOT NULL
);
