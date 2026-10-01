package io.github.orhanyarkin.saiman.ledger.payment;

/** What reconciliation read on chain for the authorization (set by T5; {@code UNKNOWN} until then). */
public enum ChainState {
    UNKNOWN,
    USED,
    UNUSED
}
