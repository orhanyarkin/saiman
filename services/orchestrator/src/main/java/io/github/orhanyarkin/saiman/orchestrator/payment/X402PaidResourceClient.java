package io.github.orhanyarkin.saiman.orchestrator.payment;

import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.run.DenyReason;
import io.github.orhanyarkin.x402.client.AmbiguousPaymentException;
import io.github.orhanyarkin.x402.client.PaymentDeclinedAfterSigningException;
import io.github.orhanyarkin.x402.client.PaymentRejectedException;
import io.github.orhanyarkin.x402.client.SpendDeniedException;
import io.github.orhanyarkin.x402.client.X402PaymentInterceptor;
import io.github.orhanyarkin.x402.core.AssetAmount;
import io.github.orhanyarkin.x402.core.PaymentRequired;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * {@link PaidResourceClient} over a {@link RestClient} that carries the starter's {@link
 * X402PaymentInterceptor} (and so the {@code BudgetSpendGuard}). The request factory never follows
 * redirects (a 3xx on the paid retry would otherwise forward {@code PAYMENT-SIGNATURE}), the client
 * never retries, and a Resilience4j circuit breaker opens on I/O errors and seller 5xx only.
 *
 * <p>After every outcome the {@code payment_intent} row, not the exception type, decides what
 * happened: an exception after the signature may have left the process makes the intent HELD (it
 * keeps counting), one before makes it RELEASED or DENIED.
 */
final class X402PaidResourceClient implements PaidResourceClient {

    static final int MAX_BODY_BYTES = 256 * 1024;

    private final @Nullable RestClient restClient;
    private final PaymentIntentService intents;
    private final CircuitBreaker circuitBreaker;
    private final X402Codec codec;
    private final Set<String> allowedPayTo;
    private final long maxAmountPerRequest;

    X402PaidResourceClient(
            @Nullable RestClient restClient,
            PaymentIntentService intents,
            CircuitBreaker circuitBreaker,
            X402Codec codec,
            Set<String> allowedPayTo,
            long maxAmountPerRequest) {
        this.restClient = restClient;
        this.intents = intents;
        this.circuitBreaker = circuitBreaker;
        this.codec = codec;
        this.allowedPayTo = Set.copyOf(allowedPayTo);
        this.maxAmountPerRequest = maxAmountPerRequest;
    }

    /**
     * The failures that count against the seller's circuit breaker: I/O errors and 5xx. Not a
     * failure: a denial, a rejection, a 4xx, a 402 after signing, and a 429 on the paid retry ({@link
     * SellerRateLimitedException}, ignored by the breaker): a rate limit says the seller is up.
     */
    static boolean isSellerFailure(Throwable failure) {
        return failure instanceof ResourceAccessException
                || (failure instanceof AmbiguousPaymentException
                        && !(failure instanceof PaymentDeclinedAfterSigningException))
                || (failure instanceof SellerCallFailedException failed
                        && failed.kind() == SellerCallFailedException.Kind.HTTP_ERROR
                        && failed.status() >= 500);
    }

    CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    @Override
    public PaidResponse send(PaymentIntentHandle intent, @Nullable Object jsonBody) {
        UUID id = intent.id();
        RestClient client = restClient;
        if (client == null) {
            intents.closeUnsent(id);
            throw new SellerCallFailedException(id, SellerCallFailedException.Kind.NOT_CONFIGURED, -1, false);
        }
        OfferRecorder.Exchange exchange = new OfferRecorder.Exchange();
        try {
            return circuitBreaker.executeSupplier(() -> rateLimitAware(client, intent, jsonBody, exchange));
        } catch (PaidCallException e) {
            throw e;
        } catch (CallNotPermittedException e) {
            intents.closeUnsent(id);
            throw new SellerCallFailedException(id, SellerCallFailedException.Kind.UNAVAILABLE, -1, false);
        } catch (SpendDeniedException e) {
            throw refusedByGuard(id);
        } catch (PaymentRejectedException e) {
            throw rejectedBeforeGuard(id, exchange);
        } catch (RuntimeException e) {
            // Ambiguous payment, I/O error, or a failure while recording the settlement.
            throw unresolved(id);
        }
    }

    /**
     * {@link #exchange}, with an ambiguous outcome whose paid retry was answered 429 rethrown as
     * {@link SellerRateLimitedException}, so the circuit breaker can tell it from an outage. The
     * payment stays ambiguous either way: {@link #send} still holds the reservation.
     */
    private PaidResponse rateLimitAware(
            RestClient client, PaymentIntentHandle intent, @Nullable Object jsonBody, OfferRecorder.Exchange exchange) {
        try {
            return exchange(client, intent, jsonBody, exchange);
        } catch (AmbiguousPaymentException e) {
            if (!(e instanceof PaymentDeclinedAfterSigningException) && exchange.paidStatus() == 429) {
                throw new SellerRateLimitedException(e);
            }
            throw e;
        }
    }

    private PaidResponse exchange(
            RestClient client, PaymentIntentHandle intent, @Nullable Object jsonBody, OfferRecorder.Exchange exchange) {
        RestClient.RequestBodySpec request = client.method(intent.endpoint().method())
                .uri(intent.resource())
                .header(X402PaymentInterceptor.IDEMPOTENCY_KEY_HEADER, intent.idempotencyKey())
                .attribute(OfferRecorder.ATTRIBUTE, exchange)
                .accept(MediaType.APPLICATION_JSON);
        if (jsonBody != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).body(jsonBody);
        }
        RawResponse raw = request.exchange(
                (req, res) -> new RawResponse(res.getStatusCode().value(), readLimited(res.getBody())), true);

        UUID id = intent.id();
        PaymentIntentView view = intents.find(id).orElseThrow(() -> new IllegalStateException("intent vanished"));
        boolean success = raw.status() >= 200 && raw.status() < 300;
        return switch (view.status()) {
            case SETTLED -> {
                String body = raw.body();
                if (!success || body == null) {
                    throw new SellerCallFailedException(
                            id, SellerCallFailedException.Kind.INVALID_RESPONSE, raw.status(), true);
                }
                yield new PaidResponse(id, raw.status(), body, Money.usdc(requireAmount(view)), view.txHash());
            }
            case PENDING, APPROVED -> {
                // The interceptor returned the seller's first answer: no payment was asked for.
                intents.closeUnsent(id);
                String body = raw.body();
                if (!success) {
                    throw new SellerCallFailedException(
                            id, SellerCallFailedException.Kind.HTTP_ERROR, raw.status(), false);
                }
                if (body == null) {
                    throw new SellerCallFailedException(
                            id, SellerCallFailedException.Kind.INVALID_RESPONSE, raw.status(), false);
                }
                yield new PaidResponse(id, raw.status(), body, Money.usdc(0), null);
            }
            default -> throw unresolved(id);
        };
    }

    /** The spend guard refused: the intent says why (or the key was unknown/reused). */
    private PaidCallException refusedByGuard(UUID id) {
        PaymentIntentView view = intents.find(id).orElse(null);
        if (view == null) {
            return new PaymentDeniedException(id, DenyReason.UNKNOWN_INTENT);
        }
        return switch (view.status()) {
            case AWAITING_APPROVAL ->
                intents.findApprovalId(id)
                        .<PaidCallException>map(approvalId -> new PaymentApprovalRequiredException(id, approvalId))
                        .orElseGet(() -> new PaymentDeniedException(id, DenyReason.UNKNOWN_INTENT));
            case DENIED, REJECTED, EXPIRED ->
                new PaymentDeniedException(
                        id, view.denyReason() == null ? DenyReason.UNKNOWN_INTENT : view.denyReason());
            // The signed hook refused and the interceptor released the reservation unsent.
            case RELEASED -> new SellerCallFailedException(id, SellerCallFailedException.Kind.ABORTED, -1, false);
            default -> new PaymentDeniedException(id, DenyReason.UNKNOWN_INTENT);
        };
    }

    /**
     * The interceptor refused the seller's offer before the guard saw it: nothing was reserved or
     * signed. Records the reason on the intent.
     */
    private PaidCallException rejectedBeforeGuard(UUID id, OfferRecorder.Exchange exchange) {
        PaymentIntentView view = intents.find(id).orElse(null);
        if (view == null
                || (view.status() != PaymentIntentStatus.PENDING && view.status() != PaymentIntentStatus.APPROVED)) {
            return new PaymentDeniedException(id, DenyReason.UNKNOWN_INTENT);
        }
        DenyReason reason = classifyRejectedOffer(exchange.paymentRequired());
        intents.markDenied(id, reason, null);
        return new PaymentDeniedException(id, reason);
    }

    /**
     * Why the interceptor would not pay: no allowlisted payee, else an allowlisted payee over the
     * per-request maximum, else the offer is not payable at all (unsupported network or asset,
     * malformed header or amount): {@code OFFER_NOT_PAYABLE}.
     */
    private DenyReason classifyRejectedOffer(@Nullable String paymentRequiredHeader) {
        if (paymentRequiredHeader == null) {
            return DenyReason.OFFER_NOT_PAYABLE;
        }
        PaymentRequired offer;
        try {
            offer = codec.decodePaymentRequired(paymentRequiredHeader);
        } catch (RuntimeException e) {
            return DenyReason.OFFER_NOT_PAYABLE;
        }
        boolean anyAllowedPayee = false;
        boolean anyAllowedPayeeOverMax = false;
        for (PaymentRequirements requirements : offer.accepts()) {
            if (!allowedPayTo.contains(requirements.payTo().toLowerCase(Locale.ROOT))) {
                continue;
            }
            anyAllowedPayee = true;
            try {
                if (AssetAmount.parse(requirements.amount()).atomicUnits() > maxAmountPerRequest) {
                    anyAllowedPayeeOverMax = true;
                }
            } catch (IllegalArgumentException e) {
                // Malformed amount: not payable.
            }
        }
        if (!anyAllowedPayee) {
            return DenyReason.PAYEE_NOT_ALLOWED;
        }
        return anyAllowedPayeeOverMax ? DenyReason.OVER_PER_REQUEST_MAX : DenyReason.OFFER_NOT_PAYABLE;
    }

    /** An exception whose meaning depends on how far the payment got: the intent row decides. */
    private PaidCallException unresolved(UUID id) {
        PaymentIntentView view = intents.find(id).orElse(null);
        if (view == null) {
            return new PaymentDeniedException(id, DenyReason.UNKNOWN_INTENT);
        }
        return switch (view.status()) {
            case RESERVED, SIGNED -> {
                // A signature may have left the process: keep it counted (fail closed).
                intents.markHeld(id);
                yield new PaymentOutcomeUnknownException(id);
            }
            case HELD -> new PaymentOutcomeUnknownException(id);
            case SETTLED ->
                new SellerCallFailedException(id, SellerCallFailedException.Kind.INVALID_RESPONSE, -1, true);
            case PENDING -> {
                intents.closeUnsent(id);
                yield new SellerCallFailedException(id, SellerCallFailedException.Kind.UNAVAILABLE, -1, false);
            }
            case APPROVED -> new SellerCallFailedException(id, SellerCallFailedException.Kind.UNAVAILABLE, -1, false);
            case RELEASED -> new SellerCallFailedException(id, SellerCallFailedException.Kind.ABORTED, -1, false);
            case AWAITING_APPROVAL, DENIED, REJECTED, EXPIRED -> refusedByGuard(id);
        };
    }

    private static long requireAmount(PaymentIntentView view) {
        Long amount = view.amountAtomic();
        if (amount == null) {
            throw new IllegalStateException("settled intent without an amount");
        }
        return amount;
    }

    /** Reads at most {@link #MAX_BODY_BYTES}; a larger body yields null (unusable). */
    private static @Nullable String readLimited(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            return null;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private record RawResponse(int status, @Nullable String body) {}

    /**
     * The seller answered the signed retry with 429 (its per-payer limit). As ambiguous as any other
     * non-2xx after signing (the intent is held), but not a seller outage: the circuit breaker
     * ignores it.
     */
    static final class SellerRateLimitedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        SellerRateLimitedException(AmbiguousPaymentException cause) {
            super("seller rate-limited the paid retry; payment outcome is ambiguous", cause);
        }
    }
}
