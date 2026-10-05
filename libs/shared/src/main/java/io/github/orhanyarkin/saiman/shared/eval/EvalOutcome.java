package io.github.orhanyarkin.saiman.shared.eval;

/** How the answer service ended for an eval question. */
public enum EvalOutcome {
    ANSWERED,
    NO_VALID_CITATIONS,
    REFUSED,
    /** The router's daily USD cap was used up. */
    LLM_CAP,
    ERROR
}
