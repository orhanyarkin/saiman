package io.github.orhanyarkin.x402.core;

import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The client's signed payment authorization for a resource.
 *
 * <p>Mirrors the x402 v2 {@code PaymentPayload} schema (specs/x402-specification-v2.md, section
 * 5.2). On HTTP this object is base64-encoded into the {@code PAYMENT-SIGNATURE} request header;
 * see {@link X402Codec} and {@link X402Headers}.
 *
 * @param x402Version protocol version identifier; must be {@code 2}
 * @param resource the resource being accessed
 * @param accepted the {@link PaymentRequirements} entry the client chose to satisfy, copied
 *     verbatim from the server's {@code accepts} offer
 * @param payload the {@code exact}-on-EVM signed authorization
 * @param extensions protocol extensions data, keyed by extension identifier
 */
public record PaymentPayload(
        int x402Version,
        @Nullable ResourceInfo resource,
        PaymentRequirements accepted,
        ExactEvmPayload payload,
        @Nullable Map<String, Object> extensions) {

    public PaymentPayload {
        Objects.requireNonNull(accepted, "accepted must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        if (x402Version != 2) {
            throw new IllegalArgumentException("x402Version must be 2, was " + x402Version);
        }
    }
}
