package io.github.orhanyarkin.saiman.orchestrator.payment;

/**
 * Lifecycle of a {@code payment_intent} row (ADR-0013).
 *
 * <pre>
 * PENDING -> RESERVED | AWAITING_APPROVAL | DENIED | RELEASED (closed before any payment was asked)
 * AWAITING_APPROVAL -> APPROVED | REJECTED | EXPIRED
 * APPROVED -> RESERVED | DENIED
 * RESERVED -> SIGNED | RELEASED (only before anything was sent) | HELD
 * SIGNED -> SETTLED | HELD (signed, outcome unknown: keeps counting against the budgets)
 * </pre>
 */
public enum PaymentIntentStatus {
    PENDING,
    AWAITING_APPROVAL,
    APPROVED,
    RESERVED,
    SIGNED,
    SETTLED,
    HELD,
    RELEASED,
    DENIED,
    REJECTED,
    EXPIRED
}
