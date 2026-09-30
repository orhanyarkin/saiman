package io.github.orhanyarkin.saiman.shared.run;

/** Why the spend-control plane refused a payment (ADR-0013). Never carries model text. */
public enum DenyReason {
    RUN_BUDGET,
    DAILY_CAP,
    PAYEE_NOT_ALLOWED,
    OVER_PER_REQUEST_MAX,
    UNKNOWN_INTENT,
    APPROVAL_REJECTED,
    APPROVAL_EXPIRED,
    MAX_PAID_CALLS,
    INVALID_ARGS
}
