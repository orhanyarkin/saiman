package io.github.orhanyarkin.saiman.shared.payments;

/** How the producer learned that a payment settled. */
public enum SettlementEvidence {
    /** The facilitator's {@code /settle} answered success with a transaction hash. */
    FACILITATOR,
    /** The producer read {@code authorizationState(from, nonce) == true} on chain (HELD resolution, ADR-0018). */
    CHAIN
}
