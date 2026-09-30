-- V3: the deny_reason CHECK lists every io.github.orhanyarkin.saiman.shared.run.DenyReason value.
-- V2 missed APPROVAL_MISMATCH and OFFER_NOT_PAYABLE; DenyReasonParityTests keeps the two in step.

ALTER TABLE payment_intent DROP CONSTRAINT payment_intent_deny_reason_valid;

ALTER TABLE payment_intent
    ADD CONSTRAINT payment_intent_deny_reason_valid CHECK (deny_reason IS NULL OR deny_reason IN (
        'RUN_BUDGET', 'DAILY_CAP', 'PAYEE_NOT_ALLOWED', 'OVER_PER_REQUEST_MAX', 'UNKNOWN_INTENT',
        'APPROVAL_REJECTED', 'APPROVAL_EXPIRED', 'APPROVAL_MISMATCH', 'OFFER_NOT_PAYABLE', 'MAX_PAID_CALLS',
        'INVALID_ARGS'));
