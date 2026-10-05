package io.github.orhanyarkin.saiman.sellerapi.disclosure;

/**
 * The answer is not grounded in enough retrieved excerpts (fewer valid citations than required).
 * Mapped to 422, which the x402 starter never settles.
 *
 * <p>{@link #modelRan()} distinguishes a refusal before any model call (too few usable excerpts were
 * retrieved) from a model reply that cited too few of them; the paid endpoints answer the same 422
 * either way, the internal eval API reports them as {@code REFUSED} and {@code NO_VALID_CITATIONS}.
 */
final class InsufficientCitationsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean modelRan;

    InsufficientCitationsException() {
        this(true);
    }

    InsufficientCitationsException(boolean modelRan) {
        super("insufficient citations", null, false, false);
        this.modelRan = modelRan;
    }

    /** Whether the model was called before the refusal. */
    boolean modelRan() {
        return modelRan;
    }
}
