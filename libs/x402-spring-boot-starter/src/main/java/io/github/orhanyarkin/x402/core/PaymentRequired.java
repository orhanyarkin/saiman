package io.github.orhanyarkin.x402.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The body of the {@code PAYMENT-REQUIRED} signal a server sends when a request has no valid
 * payment attached.
 *
 * <p>Mirrors the x402 v2 {@code PaymentRequired} schema (specs/x402-specification-v2.md, section
 * 5.1). On HTTP this object is base64-encoded into the {@code PAYMENT-REQUIRED} response header;
 * see {@link X402Codec} and {@link X402Headers}.
 *
 * @param x402Version protocol version identifier; must be {@code 2}
 * @param error human-readable error message explaining why payment is required
 * @param resource the protected resource being described
 * @param accepts acceptable payment methods for this resource, in preference order; must not be
 *     empty
 * @param extensions protocol extensions data, keyed by extension identifier
 */
public record PaymentRequired(
        int x402Version,
        @Nullable String error,
        ResourceInfo resource,
        List<PaymentRequirements> accepts,
        @Nullable Map<String, Object> extensions) {

    public PaymentRequired {
        Objects.requireNonNull(resource, "resource must not be null");
        Objects.requireNonNull(accepts, "accepts must not be null");
        if (x402Version != 2) {
            throw new IllegalArgumentException("x402Version must be 2, was " + x402Version);
        }
        if (accepts.isEmpty()) {
            throw new IllegalArgumentException("accepts must not be empty");
        }
    }
}
