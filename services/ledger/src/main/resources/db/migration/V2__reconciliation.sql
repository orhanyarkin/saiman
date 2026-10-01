-- Reconciliation against Base Sepolia (T5, ADR-0018): where the chain fact came from, and one row per payment
-- checked by a run, so a report lists MATCHED and PENDING items too (mismatches alone live in
-- reconciliation_mismatch).

ALTER TABLE payment
    ADD COLUMN chain_block bigint CHECK (chain_block >= 0);

CREATE TABLE reconciliation_item (
    run_id              uuid        NOT NULL REFERENCES reconciliation_run (id),
    payment_id          uuid        NOT NULL REFERENCES payment (id),
    status              text        NOT NULL CHECK (status IN ('MATCHED', 'PENDING', 'MISMATCH', 'TX_UNKNOWN')),
    tx_hash             text        CHECK (tx_hash ~ '^0x[0-9a-f]{64}$'),
    mismatch_kind       text,
    ledger_amount_atomic bigint,
    chain_amount_atomic  bigint,
    adjustment_entry_id uuid        REFERENCES journal_entry (id),
    checked_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, payment_id)
);
