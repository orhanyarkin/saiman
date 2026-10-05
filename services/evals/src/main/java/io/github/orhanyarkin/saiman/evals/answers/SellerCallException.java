package io.github.orhanyarkin.saiman.evals.answers;

/** One question failed at the HTTP level (not a 401/403/429/503): recorded for that item, the tier goes on. */
public class SellerCallException extends RuntimeException {

    private final int status;

    public SellerCallException(int status, String reason) {
        super("seller-api call failed: " + reason + (status > 0 ? " (HTTP " + status + ")" : ""));
        this.status = status;
    }

    /** HTTP status, or 0 for a transport failure. */
    public int status() {
        return status;
    }
}
