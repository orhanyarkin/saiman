-- M4 audit hardening (docs/THREAT_MODEL.md "Ledger and reconciliation (M4)"). Redpanda is unauthenticated until
-- M6, so every ledger input is range-bounded here as well as in the shared records: one forged payments.* record
-- must not be able to overflow a query, book a phantom twin of a real authorization or blind the audit.

-- Overflow-proof bounds. validBefore before 2100-01-01 (AuthorizationRef.MAX_VALID_BEFORE) keeps every
-- "validBefore + grace" far from 2^63; amounts up to 2^53-1 (what every JSON consumer reads exactly).
ALTER TABLE payment
    ADD CONSTRAINT payment_valid_before_bounded CHECK (valid_before > 0 AND valid_before < 4102444800),
    ADD CONSTRAINT payment_amount_bounded CHECK (amount_atomic <= 9007199254740991),
    -- Test USDC on Base Sepolia is the only asset the system pays or books (AuthorizationRef.USDC, lower-case).
    ADD CONSTRAINT payment_asset_is_usdc CHECK (asset_address = '0x036cbd53842c5426634e7929541ec2318f3dcf7e');

ALTER TABLE posting
    ADD CONSTRAINT posting_amount_bounded CHECK (amount_atomic <= 9007199254740991);

-- Phantom bookings: one EIP-3009 authorization is (payer, nonce) on one token; a second row with the same pair
-- (another asset or network spelling) is a forged twin, never a second payment.
CREATE UNIQUE INDEX payment_one_per_authorization ON payment (lower(payer), lower(nonce));

-- New mismatch kinds (libs/shared MismatchKind): BOOKS_OPEN from reconciliation, CONFLICTING_FACT from the
-- consumer. A consumer-side mismatch belongs to no reconciliation run, so run_id becomes optional.
ALTER TABLE reconciliation_mismatch DROP CONSTRAINT reconciliation_mismatch_kind_check;
ALTER TABLE reconciliation_mismatch
    ADD CONSTRAINT reconciliation_mismatch_kind_check CHECK (kind IN (
        'AMOUNT_MISMATCH', 'PARTY_MISMATCH', 'TX_NOT_FOUND', 'TX_FAILED', 'TX_NOT_FOR_AUTHORIZATION',
        'SETTLED_BUT_UNUSED', 'UNUSED_BUT_SETTLED', 'CONFLICTING_TX', 'ENCUMBRANCE_NOT_CLEARED',
        'BOOKS_OPEN', 'CONFLICTING_FACT'));
ALTER TABLE reconciliation_mismatch ALTER COLUMN run_id DROP NOT NULL;

-- Due-payment selection (ReconciliationRepository.duePaymentKeys): two partial indexes, one per share.
CREATE INDEX payment_due_with_tx ON payment (last_checked_at NULLS FIRST, created_at)
    WHERE buyer_tx_hash IS NOT NULL OR seller_tx_hash IS NOT NULL;
CREATE INDEX payment_due_without_tx ON payment (last_checked_at NULLS FIRST, created_at, valid_before)
    WHERE buyer_tx_hash IS NULL AND seller_tx_hash IS NULL;
