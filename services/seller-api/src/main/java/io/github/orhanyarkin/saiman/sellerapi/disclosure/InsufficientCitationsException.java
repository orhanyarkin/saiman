package io.github.orhanyarkin.saiman.sellerapi.disclosure;

/**
 * The answer is not grounded in enough retrieved excerpts (fewer valid citations than required).
 * Mapped to 422, which the x402 starter never settles.
 */
final class InsufficientCitationsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    InsufficientCitationsException() {
        super("insufficient citations", null, false, false);
    }
}
