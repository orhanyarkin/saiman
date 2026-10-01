-- M4b upfront flow (ADR-0021): a seller that settled up front and then did not serve the request issues a credit
-- note. The seller's book gets a fourth state, CREDITED (= SALE + CREDIT_NOTE posted). V1 declared the check
-- inline, so Postgres named it <table>_<column>_check. The CREDIT_NOTE entry kind, its one-per-payment index and
-- the LIABILITY account type already exist since V1.
ALTER TABLE payment DROP CONSTRAINT payment_seller_state_check;
ALTER TABLE payment
    ADD CONSTRAINT payment_seller_state_check CHECK (seller_state IN ('NONE', 'SETTLE_FAILED', 'SETTLED', 'CREDITED'));
