-- V5: the hourly paid-call limit (saiman.orchestrator.spend.max-paid-calls-per-hour).
-- The deny_reason CHECK gains HOURLY_PAID_CALLS (DenyReasonParityTests keeps it in step with the enum),
-- and BudgetSpendGuard counts the intents that may move money in the last hour, across all runs.

ALTER TABLE payment_intent DROP CONSTRAINT payment_intent_deny_reason_valid;

ALTER TABLE payment_intent
    ADD CONSTRAINT payment_intent_deny_reason_valid CHECK (deny_reason IS NULL OR deny_reason IN (
        'RUN_BUDGET', 'DAILY_CAP', 'PAYEE_NOT_ALLOWED', 'OVER_PER_REQUEST_MAX', 'UNKNOWN_INTENT',
        'APPROVAL_REJECTED', 'APPROVAL_EXPIRED', 'APPROVAL_MISMATCH', 'OFFER_NOT_PAYABLE', 'MAX_PAID_CALLS',
        'HOURLY_PAID_CALLS', 'INVALID_ARGS'));

CREATE INDEX payment_intent_paid_created_idx ON payment_intent (created_at)
    WHERE status IN ('RESERVED', 'SIGNED', 'SETTLED', 'HELD');
