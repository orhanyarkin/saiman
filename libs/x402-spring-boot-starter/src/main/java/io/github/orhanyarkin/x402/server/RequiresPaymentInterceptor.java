package io.github.orhanyarkin.x402.server;

import io.github.orhanyarkin.x402.core.AssetAmount;
import io.github.orhanyarkin.x402.core.Eip3009Authorization;
import io.github.orhanyarkin.x402.core.PaymentPayload;
import io.github.orhanyarkin.x402.core.PaymentRequired;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.ResourceInfo;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import io.github.orhanyarkin.x402.core.UnsupportedPaymentException;
import io.github.orhanyarkin.x402.core.VerifyResponse;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.orhanyarkin.x402.core.X402CodecException;
import io.github.orhanyarkin.x402.core.X402Headers;
import io.github.orhanyarkin.x402.evm.Eip3009TypedData;
import io.github.orhanyarkin.x402.facilitator.FacilitatorClient;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Rejects requests to a {@link RequiresPayment} handler that do not carry a valid, unused payment
 * authorization for exactly the required amount.
 *
 * <p>Runs as a {@code preHandle} on every request; it is a no-op (returns {@code true} immediately)
 * for any handler not annotated with {@link RequiresPayment} -- see {@link RequiresPaymentRegistry}
 * -- and for an {@link DispatcherType#ASYNC} dispatch (async handler return types are rejected at
 * startup, so this only matters if something else on the request triggers async processing; either
 * way, payment was already checked on the original {@link DispatcherType#REQUEST} dispatch).
 *
 * <p>For a paid handler, this class alone decides whether the handler runs at all. In the default
 * {@link io.github.orhanyarkin.x402.core.PaymentFlow#AUTHORIZATION authorization} flow it never
 * settles a payment itself (that only happens after the handler returns a 2xx, in {@link
 * X402SettlementFilter}); for an {@link io.github.orhanyarkin.x402.core.PaymentFlow#UPFRONT upfront}
 * handler it settles right after a successful {@code /verify}, and the handler runs only if that
 * settle succeeded. Before that settle it checks the window once more: a slow {@code /verify} that
 * left less than the facilitator's settle margin is refused with {@code 402} and the nonce claim
 * released, since nothing was settled. Every check before that (offer, amount, window, signature, nonce claim, verify)
 * is the same in both flows, so a refused request never reaches {@code /settle}. It communicates
 * its outcome to that filter through the {@link
 * X402PaymentAttempt} request attribute the filter created before dispatching. The very first thing
 * this does once it finds that attribute is mark {@link X402PaymentAttempt#markInterceptorRan()} --
 * the filter's runtime backstop for the case this interceptor is somehow never invoked at all.
 *
 * <p>Every rejection here writes a {@code 402} response directly (never a {@code 500}, even for
 * malformed client input -- see the design's "Server flow"): a missing header gets the full {@code
 * PAYMENT-REQUIRED} offer; every other rejection gets the same, so a client can always recover by
 * reading the current offer and retrying. The one exception is the nonce store itself failing (e.g.
 * Redis unreachable): that is infrastructure unavailability, not "please pay", so it gets a plain
 * {@code 503}. None of the Problem Details {@code detail} messages below echo any part of the
 * request (ADR-0006 amendment).
 */
public final class RequiresPaymentInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RequiresPaymentInterceptor.class);

    /** Extra allowance, on top of a handler's {@code maxTimeoutSeconds}, for clock drift between payer and server. */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(5);

    private final RequiresPaymentRegistry registry;
    private final X402Codec codec;
    private final FacilitatorClient facilitatorClient;
    private final PaymentNonceStore nonceStore;
    private final X402ServerProperties properties;
    private final Clock clock;
    private final PaymentSettler settler;

    /**
     * @param eventPublisher receives the {@link X402PaymentSettledEvent}/{@link
     *     X402PaymentFailedEvent} of an {@link io.github.orhanyarkin.x402.core.PaymentFlow#UPFRONT
     *     upfront} handler, which this interceptor settles itself before the handler runs
     */
    public RequiresPaymentInterceptor(
            RequiresPaymentRegistry registry,
            X402Codec codec,
            FacilitatorClient facilitatorClient,
            PaymentNonceStore nonceStore,
            X402ServerProperties properties,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.registry = registry;
        this.codec = codec;
        this.facilitatorClient = facilitatorClient;
        this.nonceStore = nonceStore;
        this.properties = properties;
        this.clock = clock;
        this.settler = new PaymentSettler(facilitatorClient, codec, properties, eventPublisher, clock);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            // Payment was already checked on the original REQUEST dispatch; async handler return
            // types are rejected at startup (see RequiresPaymentRegistry), so this should be
            // unreachable for a @RequiresPayment handler, but is harmless and correct regardless.
            return true;
        }
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequiresPaymentRegistry.Entry entry = registry.entryFor(handlerMethod.getMethod());
        if (entry == null) {
            return true;
        }

        Object attemptAttribute = request.getAttribute(X402SettlementFilter.ATTEMPT_ATTRIBUTE);
        if (!(attemptAttribute instanceof X402PaymentAttempt attempt)) {
            // X402SettlementFilter decides, ahead of dispatch, whether a request targets a paid
            // handler and only then creates this attribute; if it is missing here the filter is
            // simply not registered -- a deployment/wiring error, not malformed client input, so
            // this is allowed to surface as a 500.
            throw new IllegalStateException(
                    "X402SettlementFilter must be registered for @RequiresPayment to be enforced");
        }
        attempt.markInterceptorRan();

        String headerValue = request.getHeader(X402Headers.PAYMENT_SIGNATURE);
        if (headerValue == null || headerValue.isBlank()) {
            reject(request, response, attempt, "missing_payment", "payment is required to access this resource");
            return false;
        }

        PaymentPayload clientPayload;
        try {
            clientPayload = codec.decodePaymentPayload(headerValue);
        } catch (X402CodecException malformed) {
            reject(
                    request,
                    response,
                    attempt,
                    "malformed_payload",
                    "the payment signature header could not be decoded");
            return false;
        }

        try {
            TestnetAssets.requireSupported(clientPayload.accepted());
        } catch (UnsupportedPaymentException unsupported) {
            reject(request, response, attempt, "unsupported", "the submitted payment method is not supported");
            return false;
        }
        if (!entry.offer().equals(clientPayload.accepted())) {
            reject(
                    request,
                    response,
                    attempt,
                    "requirements_mismatch",
                    "the submitted payment does not match the required payment");
            return false;
        }

        Eip3009Authorization authorization = clientPayload.payload().authorization();
        String rejectionOutcome;
        try {
            rejectionOutcome = validateAuthorization(entry, authorization);
        } catch (RuntimeException malformedAuthorization) {
            reject(request, response, attempt, "malformed_payload", "the payment authorization is invalid");
            return false;
        }
        if (rejectionOutcome != null) {
            reject(
                    request,
                    response,
                    attempt,
                    rejectionOutcome,
                    "the submitted payment does not match the required payment");
            return false;
        }

        if (!Eip3009TypedData.verify(authorization, clientPayload.payload().signature())) {
            reject(request, response, attempt, "invalid_signature", "the payment signature is invalid");
            return false;
        }

        long validBefore = Long.parseLong(authorization.validBefore());
        long window = validBefore - clock.instant().getEpochSecond();
        String nonceKey = "x402:nonce:" + entry.offer().network() + ":"
                + entry.offer().asset().toLowerCase(Locale.ROOT) + ":" + authorization.canonicalNonceKey();

        String claimToken;
        try {
            claimToken = nonceStore.claim(
                    nonceKey, Duration.ofSeconds(Math.max(1, window)).plus(CLOCK_SKEW));
        } catch (RuntimeException nonceStoreError) {
            log.error(
                    "x402 payment nonce store is unavailable: {}",
                    nonceStoreError.getClass().getSimpleName());
            attempt.outcome("nonce_store_unavailable");
            writeServiceUnavailable(response);
            return false;
        }
        if (claimToken == null) {
            reject(request, response, attempt, "replayed", "this payment authorization has already been used");
            return false;
        }

        // Server-controlled resource info and offer, the client's signed payload: never forward a
        // client-supplied resource/extensions to the facilitator (they are not authenticated).
        PaymentPayload serverPayload = new PaymentPayload(
                2,
                resourceInfo(request, entry, properties.publicBaseUrl()),
                entry.offer(),
                clientPayload.payload(),
                null);

        VerifyResponse verifyResponse;
        try {
            verifyResponse = facilitatorClient.verify(serverPayload, entry.offer());
        } catch (RuntimeException facilitatorError) {
            log.warn(
                    "x402 facilitator /verify call failed: {}",
                    facilitatorError.getClass().getSimpleName());
            // Transport/decode failure: the facilitator never definitively rejected this
            // authorization, and this server will never settle it under this claim -- release so a
            // client retry is not needlessly treated as a replay.
            nonceStore.release(nonceKey, claimToken);
            reject(
                    request,
                    response,
                    attempt,
                    "facilitator_unavailable",
                    "payment verification is temporarily unavailable");
            return false;
        }
        if (!verifyResponse.isValid()) {
            // The facilitator saw and rejected this authorization: keep the claim so the same
            // rejected payload cannot be retried indefinitely.
            reject(request, response, attempt, "invalid", "the payment could not be verified");
            return false;
        }

        if (entry.upfront() && !leavesTimeToSettle(validBefore)) {
            // /verify (with its retries) used up so much of the window that an upfront /settle
            // could now lose the race against validBefore. Nothing was settled and nothing will be
            // under this claim, so -- unlike every later upfront branch -- releasing it is safe: the
            // same authorization may be retried. Checked before markVerified, so
            // X402SettlementFilter treats this like any other refusal written here.
            nonceStore.release(nonceKey, claimToken);
            reject(
                    request,
                    response,
                    attempt,
                    "window_too_short",
                    "the submitted payment does not match the required payment");
            return false;
        }

        attempt.markVerified(serverPayload, nonceKey, claimToken, authorization.from());
        if (!entry.upfront()) {
            return true;
        }
        // Upfront flow (ADR-0021): settle now, before the handler can spend anything. From here on
        // the claim is never released (X402SettlementFilter checks settleAttempted() first): either
        // the money moved, or the settle outcome is ambiguous. A failed settle has already written
        // its 402 and published X402PaymentFailedEvent; the handler never runs.
        attempt.markSettleAttempted();
        return settler.settle(request, response, attempt);
    }

    /**
     * Whether an authorization valid before {@code validBefore} (epoch seconds) still has at least
     * the facilitator's {@linkplain X402ServerProperties.Facilitator#settleMargin() settle margin}
     * left now. Whole seconds, exactly like the window check before the claim in {@link
     * #validateAuthorization}, so an authorization that passed that check is refused here only if
     * time actually passed in between.
     */
    private boolean leavesTimeToSettle(long validBefore) {
        long left = validBefore - clock.instant().getEpochSecond();
        return left >= properties.facilitator().settleMargin().toSeconds();
    }

    /**
     * Validates {@code authorization} against {@code offer}: recipient, amount and time window
     * (both the upper bound -- {@code offer.maxTimeoutSeconds()} -- and a lower bound: enough
     * margin over the facilitator's read timeout that {@code /settle} cannot lose a race against
     * the authorization's own expiry).
     *
     * @return a rejection outcome tag, or {@code null} if {@code authorization} passes every check
     */
    private @Nullable String validateAuthorization(
            RequiresPaymentRegistry.Entry entry, Eip3009Authorization authorization) {
        PaymentRequirements offer = entry.offer();
        if (!authorization.to().equalsIgnoreCase(offer.payTo())) {
            return "requirements_mismatch";
        }
        AssetAmount required = AssetAmount.parse(offer.amount());
        AssetAmount provided = AssetAmount.parse(authorization.value());
        if (provided.atomicUnits() != required.atomicUnits()) {
            return "requirements_mismatch";
        }
        long now = clock.instant().getEpochSecond();
        long validAfter = Long.parseLong(authorization.validAfter());
        long validBefore = Long.parseLong(authorization.validBefore());
        if (now < validAfter || now >= validBefore) {
            return "expired";
        }
        long window = validBefore - now;
        if (window > offer.maxTimeoutSeconds() + CLOCK_SKEW.toSeconds()) {
            return "window_too_large";
        }
        long minimumWindow = Math.max(properties.facilitator().settleMargin().toSeconds(), entry.minWindowSeconds());
        if (window < minimumWindow) {
            return "window_too_short";
        }
        return null;
    }

    private void reject(
            HttpServletRequest request,
            HttpServletResponse response,
            X402PaymentAttempt attempt,
            String outcome,
            String detail)
            throws IOException {
        attempt.outcome(outcome);
        writePaymentRequired(
                response,
                codec,
                resourceInfo(request, attempt.entry(), properties.publicBaseUrl()),
                attempt.entry().offer(),
                detail);
    }

    /** A plain {@code 503} Problem Details body: infrastructure unavailability, not "payment required". */
    private void writeServiceUnavailable(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "payment verification is temporarily unavailable");
        response.getWriter().write(codec.writeJson(problem));
    }

    /**
     * Server-controlled resource info: never derived from the {@code Host} header or {@code
     * X-Forwarded-*} (both client-controlled, and {@link HttpServletRequest#getRequestURL()} folds
     * {@code Host} in). Uses {@code x402.server.public-base-url} if configured, else the request
     * path alone.
     */
    static ResourceInfo resourceInfo(
            HttpServletRequest request, RequiresPaymentRegistry.Entry entry, @Nullable String publicBaseUrl) {
        String description = entry.description();
        String url = publicBaseUrl == null || publicBaseUrl.isBlank()
                ? request.getRequestURI()
                : publicBaseUrl + request.getRequestURI();
        return new ResourceInfo(
                url,
                description == null || description.isBlank() ? null : description,
                MediaType.APPLICATION_JSON_VALUE,
                null,
                null,
                null);
    }

    /** Writes a {@code 402} response: the {@code PAYMENT-REQUIRED} header plus a Problem Details body. */
    static void writePaymentRequired(
            HttpServletResponse response,
            X402Codec codec,
            ResourceInfo resource,
            PaymentRequirements offer,
            String detail)
            throws IOException {
        PaymentRequired paymentRequired = new PaymentRequired(2, detail, resource, List.of(offer), null);
        response.setStatus(HttpStatus.PAYMENT_REQUIRED.value());
        response.setHeader(X402Headers.PAYMENT_REQUIRED, codec.encodePaymentRequired(paymentRequired));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.PAYMENT_REQUIRED, detail);
        response.getWriter().write(codec.writeJson(problem));
    }
}
