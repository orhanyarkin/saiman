package io.github.orhanyarkin.saiman.shared.payments;

/** Whether a failure is the last word on the authorization. */
public enum Finality {
    /** The authorization can no longer be used (expired unused, verified on chain). */
    FINAL,
    /** The producer did not settle, but the authorization may still be used until {@code validBefore}. */
    AMBIGUOUS
}
