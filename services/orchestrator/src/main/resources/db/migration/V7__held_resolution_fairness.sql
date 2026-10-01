-- V7: HELD resolution hardening (M4 audit fixes).

-- A HELD intent that never recorded an authorization (RESERVED -> HELD after a failure: the signature provably
-- never left the process) is released by the resolver itself, without a chain read and without a payment event.
ALTER TABLE payment_intent DROP CONSTRAINT payment_intent_resolved_by_valid;
ALTER TABLE payment_intent
    ADD CONSTRAINT payment_intent_resolved_by_valid
        CHECK (resolved_by IS NULL OR resolved_by IN ('FACILITATOR', 'CHAIN', 'LOCAL'));

-- Fairness: the resolver orders its work list by the last attempt (never attempted first), so intents that keep
-- failing (chain errors) cannot starve the ones behind them.
ALTER TABLE payment_intent ADD COLUMN resolution_attempted_at timestamptz;
