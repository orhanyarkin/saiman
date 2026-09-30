package io.github.orhanyarkin.saiman.sellerapi.llm;

import java.time.Duration;

/** A monotonic end-to-end time budget for one request. */
public final class Deadline {

    private final long expiresAtNanos;

    private Deadline(long expiresAtNanos) {
        this.expiresAtNanos = expiresAtNanos;
    }

    /** A deadline {@code budget} from now. */
    public static Deadline after(Duration budget) {
        return new Deadline(System.nanoTime() + budget.toNanos());
    }

    public boolean expired() {
        return remaining().isZero();
    }

    /** The time left, never negative. */
    public Duration remaining() {
        long left = expiresAtNanos - System.nanoTime();
        return left <= 0 ? Duration.ZERO : Duration.ofNanos(left);
    }
}
