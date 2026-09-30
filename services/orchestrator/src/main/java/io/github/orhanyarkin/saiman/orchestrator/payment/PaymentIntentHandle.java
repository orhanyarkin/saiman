package io.github.orhanyarkin.saiman.orchestrator.payment;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

/**
 * A payment intent as created by {@link PaymentIntentService#create}: what {@link
 * PaidResourceClient#send} needs to send the request. Only this package can construct one, so a
 * caller can't point the paying client at a URI of its own or pick an idempotency key.
 *
 * <p>The idempotency key is package-private and never part of {@link #toString()}: it must not
 * appear in a log, span or event (standing rule 5).
 */
public final class PaymentIntentHandle {

    private final UUID id;
    private final UUID runId;
    private final SellerEndpoint endpoint;
    private final URI resource;
    private final String idempotencyKey;

    PaymentIntentHandle(UUID id, UUID runId, SellerEndpoint endpoint, URI resource, String idempotencyKey) {
        this.id = Objects.requireNonNull(id);
        this.runId = Objects.requireNonNull(runId);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.resource = Objects.requireNonNull(resource);
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
    }

    public UUID id() {
        return id;
    }

    public UUID runId() {
        return runId;
    }

    public SellerEndpoint endpoint() {
        return endpoint;
    }

    /** The full request URI: the configured seller base URL plus the endpoint's expanded template. */
    public URI resource() {
        return resource;
    }

    String idempotencyKey() {
        return idempotencyKey;
    }

    @Override
    public String toString() {
        return "PaymentIntentHandle[id=" + id + ", runId=" + runId + ", endpoint=" + endpoint + "]";
    }
}
