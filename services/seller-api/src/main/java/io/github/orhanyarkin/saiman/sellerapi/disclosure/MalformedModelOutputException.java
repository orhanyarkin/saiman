package io.github.orhanyarkin.saiman.sellerapi.disclosure;

/** The model answered, but not with the required JSON shape. Mapped to 502; carries no model text. */
final class MalformedModelOutputException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    MalformedModelOutputException() {
        super("malformed model output", null, false, false);
    }
}
