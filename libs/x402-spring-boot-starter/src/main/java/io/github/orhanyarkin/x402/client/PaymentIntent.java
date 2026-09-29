package io.github.orhanyarkin.x402.client;

import io.github.orhanyarkin.x402.core.PaymentRequirements;
import java.net.URI;
import java.util.Objects;

/**
 * A single "I am about to pay for this" request, checked by {@link SpendGuard} before any signing
 * happens.
 *
 * @param idempotencyKey the caller-supplied {@code Idempotency-Key} request header value
 * @param resource the URI of the resource being paid for
 * @param requirements the {@link PaymentRequirements} entry {@link X402PaymentInterceptor} selected
 *     from the server's {@code accepts} offer
 */
public record PaymentIntent(String idempotencyKey, URI resource, PaymentRequirements requirements) {

    public PaymentIntent {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(resource, "resource must not be null");
        Objects.requireNonNull(requirements, "requirements must not be null");
    }
}
