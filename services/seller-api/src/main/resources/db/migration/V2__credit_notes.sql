-- V2: credit notes for upfront-flow requests that were paid but not served (ADR-0021, docs/design/m4b-settle-first.md).

-- One row per paid authorization whose handler answered 3xx/4xx/5xx or threw after /settle succeeded. The seller has
-- no key to refund on chain, so it owes the buyer the full amount. The payment key is network:asset:payer:nonce,
-- lower-case (AuthorizationRef.paymentKey()); a repeated report of the same authorization inserts nothing.
CREATE TABLE credit_note
(
    payment_key   text PRIMARY KEY,
    tx_hash       text        NOT NULL CHECK (tx_hash ~ '^0x[0-9a-fA-F]{64}$'),
    amount_atomic bigint      NOT NULL CHECK (amount_atomic > 0),
    pay_to        text        NOT NULL,
    payer         text        NOT NULL,
    http_status   integer     NOT NULL CHECK (http_status BETWEEN 300 AND 599),
    reason_code   text        NOT NULL CHECK (reason_code ~ '^[a-z0-9_]{1,64}$'),
    created_at    timestamptz NOT NULL DEFAULT now()
);
