package io.github.orhanyarkin.x402.server;

import io.github.orhanyarkin.x402.core.Eip3009Authorization;
import io.github.orhanyarkin.x402.core.SettlementResponse;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.orhanyarkin.x402.core.X402Headers;
import io.github.orhanyarkin.x402.facilitator.FacilitatorClient;
import io.github.orhanyarkin.x402.observation.X402ObservationKeys;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Buffers the response for {@link RequiresPayment} handlers and settles the payment after the
 * handler runs, per the design's "Server flow":
 *
 * <ol>
 *   <li>Resolves whether the request targets a {@code @RequiresPayment} handler <em>before</em>
 *       dispatching (so non-paid requests are never buffered at all). A handler carrying the
 *       annotation but with no {@link RequiresPaymentRegistry} entry (e.g. registered dynamically
 *       after startup, so {@link RequiresPaymentRegistry}'s startup scan never saw it) is refused
 *       outright with an empty {@code 500} -- fail closed, never run an unchecked paid handler.
 *   <li>Otherwise starts the {@code x402.server.payment} {@link Observation} (with an open {@link
 *       Observation.Scope}, so the facilitator client's own HTTP observations nest under it),
 *       snapshots the response headers already present (set by filters earlier in the chain, e.g.
 *       CORS), and stores an {@link X402PaymentAttempt} request attribute that {@link
 *       RequiresPaymentInterceptor} fills in during {@code preHandle}.
 *   <li>Wraps the response in a {@link ContentCachingResponseWrapper} and dispatches. Nothing
 *       written during dispatch reaches the real response yet -- {@code
 *       ContentCachingResponseWrapper} only buffers (verified against the Spring Framework 7.0.9
 *       jar: its output stream writes to an in-memory buffer, never to the underlying stream, and
 *       {@code flushBuffer()} is a deliberate no-op).
 *   <li>If the request went async ({@link HttpServletRequest#isAsyncStarted()}), this filter never
 *       settles it (the handler has not actually finished): the claim is released and an error is
 *       logged. Async handler return types are rejected at startup, so this is a backstop only.
 *   <li>After dispatch: if the interceptor never ran at all (a wiring bug this starter cannot
 *       prevent, only detect -- see {@link X402PaymentAttempt#interceptorRan()}), or never verified
 *       the payment, the buffered response is discarded and replaced with a plain {@code 500} (the
 *       former) or simply flushed as-is (the latter -- {@link RequiresPaymentInterceptor} already
 *       wrote its own {@code 402}). If it did verify and the handler answered 2xx, this class calls
 *       {@code /settle} and builds a minimal, server-controlled {@code PAYMENT-RESPONSE} from the
 *       result (never re-serialising the facilitator's own response body verbatim -- see {@link
 *       #buildClientFacingSettlement}); on failure it discards the handler's body ({@link
 *       ContentCachingResponseWrapper#reset()}, restoring the pre-dispatch header snapshot) and
 *       writes a fresh 402, keeping the nonce claim (the outcome is ambiguous -- see {@link
 *       X402PaymentFailedEvent}). A non-2xx (including 3xx: content is only ever delivered via a
 *       2xx) handler response means nothing is settled, the nonce claim is released, and the
 *       handler's own response is passed through untouched. Anything unexpected while finishing a
 *       verified settlement (a malformed facilitator response, an unforeseen failure) is caught
 *       and routed through the same ambiguous-failure path -- never left to escape and have the
 *       still-buffered handler body flushed unchecked.
 * </ol>
 */
public final class X402SettlementFilter extends OncePerRequestFilter {

    static final String ATTEMPT_ATTRIBUTE = X402SettlementFilter.class.getName() + ".ATTEMPT";

    private static final Logger log = LoggerFactory.getLogger(X402SettlementFilter.class);

    private static final Pattern TRANSACTION_HASH_PATTERN = Pattern.compile("0x[0-9a-fA-F]{64}");

    /** A facilitator reason code is logged only in this shape; anything else is reported as unrecognised. */
    private static final Pattern REASON_CODE = Pattern.compile("[a-z0-9_]{1,64}");

    private final ObjectProvider<RequestMappingHandlerMapping> handlerMapping;
    private final RequiresPaymentRegistry registry;
    private final FacilitatorClient facilitatorClient;
    private final PaymentNonceStore nonceStore;
    private final X402Codec codec;
    private final X402ServerProperties properties;
    private final ObservationRegistry observationRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public X402SettlementFilter(
            ObjectProvider<RequestMappingHandlerMapping> handlerMapping,
            RequiresPaymentRegistry registry,
            FacilitatorClient facilitatorClient,
            PaymentNonceStore nonceStore,
            X402Codec codec,
            X402ServerProperties properties,
            ObservationRegistry observationRegistry,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.handlerMapping = handlerMapping;
        this.registry = registry;
        this.facilitatorClient = facilitatorClient;
        this.nonceStore = nonceStore;
        this.codec = codec;
        this.properties = properties;
        this.observationRegistry = observationRegistry;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Resolution resolution = resolveHandler(request);
        if (resolution == null) {
            filterChain.doFilter(request, response);
            return;
        }
        if (resolution.misconfigured()) {
            log.error("a @RequiresPayment handler has no RequiresPaymentRegistry entry (registered after"
                    + " startup?); refusing to run it");
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }
        RequiresPaymentRegistry.Entry entry = resolution.entry();
        if (entry == null) {
            // Unreachable: misconfigured() is exactly the case where entry() is null (see
            // Resolution's Javadoc), and that was handled above. Guards the field for NullAway.
            throw new IllegalStateException("unreachable: a non-misconfigured resolution has no entry");
        }

        Observation observation = Observation.createNotStarted(
                        X402ObservationKeys.OBSERVATION_NAME, observationRegistry)
                .lowCardinalityKeyValue(
                        X402ObservationKeys.NETWORK, entry.offer().network())
                .lowCardinalityKeyValue(
                        X402ObservationKeys.SCHEME, entry.offer().scheme())
                .lowCardinalityKeyValue(X402ObservationKeys.ASSET, entry.offer().asset())
                .start();
        X402PaymentAttempt attempt = new X402PaymentAttempt(observation, entry);
        request.setAttribute(ATTEMPT_ATTRIBUTE, attempt);

        Map<String, List<String>> headerSnapshot = snapshotHeaders(response);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        try (Observation.Scope scope = observation.openScope()) {
            filterChain.doFilter(request, wrappedResponse);
            if (request.isAsyncStarted()) {
                handleUnexpectedAsync(attempt);
                return;
            }
            afterDispatch(request, wrappedResponse, headerSnapshot, attempt);
        } catch (RuntimeException | IOException | ServletException dispatchFailure) {
            attempt.outcome("dispatch_error");
            observation.error(dispatchFailure);
            discardHandlerOutput(wrappedResponse, headerSnapshot);
            throw dispatchFailure;
        } finally {
            observation.lowCardinalityKeyValue(X402ObservationKeys.OUTCOME, attempt.outcome());
            String payer = attempt.payer();
            if (payer != null) {
                observation.highCardinalityKeyValue(X402ObservationKeys.PAYER, payer);
            }
            String txHash = attempt.txHash();
            if (txHash != null) {
                observation.highCardinalityKeyValue(X402ObservationKeys.TX_HASH, txHash);
            }
            observation.stop();
            if (!request.isAsyncStarted()) {
                wrappedResponse.copyBodyToResponse();
            }
        }
    }

    /**
     * A handler that fails with an exception no advice maps must not leak what it set before
     * failing (headers, cookies, buffered body) into the container's error response: headers set on
     * the wrapper reach the real response immediately. Best effort -- a committed response cannot
     * be reset, and the nonce claim stays held either way.
     */
    private static void discardHandlerOutput(
            ContentCachingResponseWrapper wrappedResponse, Map<String, List<String>> headerSnapshot) {
        try {
            if (!wrappedResponse.isCommitted()) {
                wrappedResponse.reset();
                restoreHeaders(wrappedResponse, headerSnapshot);
            }
        } catch (RuntimeException ignored) {
            // the original failure is what the caller must see
        }
    }

    private void handleUnexpectedAsync(X402PaymentAttempt attempt) {
        log.error("a @RequiresPayment handler started asynchronous processing; this is not supported and should"
                + " have been rejected at startup -- the payment will not be settled");
        attempt.outcome("async_not_supported");
        if (attempt.verified()) {
            nonceStore.release(attempt.nonceKey(), attempt.claimToken());
        }
    }

    private void afterDispatch(
            HttpServletRequest request,
            ContentCachingResponseWrapper wrappedResponse,
            Map<String, List<String>> headerSnapshot,
            X402PaymentAttempt attempt)
            throws IOException {
        if (!attempt.interceptorRan()) {
            // RequiresPaymentInterceptor never ran at all (see its Javadoc for how this can
            // happen): the handler may have run completely unchecked. Never trust or settle it.
            log.error("RequiresPaymentInterceptor did not run for a @RequiresPayment handler; discarding its"
                    + " response and refusing to settle");
            attempt.outcome("interceptor_did_not_run");
            wrappedResponse.reset();
            wrappedResponse.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }
        if (!attempt.verified()) {
            // RequiresPaymentInterceptor already wrote the 402 response into the buffer.
            return;
        }
        int status = wrappedResponse.getStatus();
        if (status < 200 || status >= 300) {
            // The handler itself rejected the request (e.g. 404 unknown ticker) or redirected
            // (3xx): content is only ever delivered via a 2xx, so neither is charged for.
            if (attempt.workDone()) {
                // The handler reported (X402PaymentContext#markWorkDone) that it already spent
                // non-refundable resources for this request: keep the claim so the same
                // authorization cannot be replayed to buy another free run. Nothing is settled.
                attempt.outcome("not_charged_work_done");
                return;
            }
            nonceStore.release(attempt.nonceKey(), attempt.claimToken());
            attempt.outcome("not_charged");
            return;
        }

        SettlementResponse settlement;
        try {
            settlement =
                    facilitatorClient.settle(attempt.payload(), attempt.entry().offer());
        } catch (RuntimeException settleError) {
            log.warn(
                    "x402 facilitator /settle call failed: {}",
                    settleError.getClass().getSimpleName());
            failSettlement(request, wrappedResponse, headerSnapshot, attempt, null);
            return;
        }
        // Anything unexpected from here on (a null/malformed transaction hash -- the field isn't
        // @Nullable on SettlementResponse, but the tolerant facilitator-response mapper leaves it
        // null when a hostile or buggy facilitator omits it; or any other failure while finishing
        // the response) must never let doFilterInternal's finally block flush the handler's
        // already-buffered 2xx body. Route every such case through failSettlement (reset, keep the
        // nonce claim, ask again) instead of letting an exception escape -- this is the money-safe
        // default, not merely an error handler.
        try {
            String transaction = settlement.transaction();
            if (!settlement.success()
                    || transaction == null
                    || !TRANSACTION_HASH_PATTERN.matcher(transaction).matches()) {
                // A "successful" settlement without a well-formed transaction hash is treated as
                // ambiguous, not as success: never echo an unvalidated facilitator-supplied value
                // into the client-facing PAYMENT-RESPONSE.
                failSettlement(
                        request,
                        wrappedResponse,
                        headerSnapshot,
                        attempt,
                        settlement.success() ? "ambiguous" : settlement.errorReason());
                return;
            }

            Eip3009Authorization authorization = attempt.payload().payload().authorization();
            SettlementResponse clientFacing = buildClientFacingSettlement(attempt, transaction);
            wrappedResponse.setHeader(X402Headers.PAYMENT_RESPONSE, codec.encodeSettlementResponse(clientFacing));
            attempt.outcome("settled");
            attempt.txHash(transaction);
            publishSafely(new X402PaymentSettledEvent(
                    UUID.randomUUID(),
                    request.getRequestURI(),
                    attempt.entry().offer(),
                    authorization.from(),
                    authorization.nonce(),
                    authorization.value(),
                    authorization.validBefore(),
                    authorization.from(),
                    transaction,
                    clock.instant()));
        } catch (RuntimeException unexpected) {
            log.error(
                    "unexpected failure while finishing settlement for {}: {}",
                    request.getRequestURI(),
                    unexpected.getClass().getSimpleName(),
                    unexpected);
            failSettlement(request, wrappedResponse, headerSnapshot, attempt, "internal_error");
        }
    }

    /**
     * Builds the {@code PAYMENT-RESPONSE} the client actually sees, entirely from
     * server-controlled/locally-verified fields -- never the facilitator's raw response body
     * (which could carry an oversized {@code extensions}/{@code extra} map; a facilitator is
     * trusted to move money correctly, not to bound the size of what it echoes back).
     */
    private static SettlementResponse buildClientFacingSettlement(X402PaymentAttempt attempt, String transaction) {
        return new SettlementResponse(
                true,
                null,
                null,
                attempt.payer(),
                transaction,
                attempt.entry().offer().network(),
                attempt.entry().offer().amount(),
                null,
                null,
                null);
    }

    private void failSettlement(
            HttpServletRequest request,
            ContentCachingResponseWrapper wrappedResponse,
            Map<String, List<String>> headerSnapshot,
            X402PaymentAttempt attempt,
            @Nullable String errorReason)
            throws IOException {
        wrappedResponse.reset();
        restoreHeaders(wrappedResponse, headerSnapshot);
        attempt.outcome("settlement_failed");
        // The facilitator's reason is untrusted text: log it only as a bounded code, never echo it.
        log.warn(
                "x402 settlement failed: reason={}",
                errorReason != null && REASON_CODE.matcher(errorReason).matches() ? errorReason : "unrecognised");
        RequiresPaymentInterceptor.writePaymentRequired(
                wrappedResponse,
                codec,
                RequiresPaymentInterceptor.resourceInfo(request, attempt.entry(), properties.publicBaseUrl()),
                attempt.entry().offer(),
                "payment settlement failed");
        Eip3009Authorization authorization = attempt.payload().payload().authorization();
        publishSafely(new X402PaymentFailedEvent(
                UUID.randomUUID(),
                request.getRequestURI(),
                attempt.entry().offer(),
                authorization.from(),
                authorization.nonce(),
                authorization.value(),
                authorization.validBefore(),
                attempt.payer(),
                errorReason,
                clock.instant()));
    }

    /**
     * Publishes {@code event}, logging (never rethrowing) if a listener throws: a broken listener
     * must not corrupt a settlement outcome that has already happened, or the response that has
     * already been decided.
     */
    private void publishSafely(Object event) {
        try {
            eventPublisher.publishEvent(event);
        } catch (RuntimeException listenerFailure) {
            log.error(
                    "an x402 payment event listener threw: {}",
                    listenerFailure.getClass().getSimpleName());
        }
    }

    private static Map<String, List<String>> snapshotHeaders(HttpServletResponse response) {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        Collection<String> names = response.getHeaderNames();
        for (String name : names) {
            snapshot.put(name, new ArrayList<>(response.getHeaders(name)));
        }
        return snapshot;
    }

    private static void restoreHeaders(HttpServletResponse response, Map<String, List<String>> snapshot) {
        for (Map.Entry<String, List<String>> entry : snapshot.entrySet()) {
            boolean first = true;
            for (String value : entry.getValue()) {
                if (first) {
                    response.setHeader(entry.getKey(), value);
                    first = false;
                } else {
                    response.addHeader(entry.getKey(), value);
                }
            }
        }
    }

    private @Nullable Resolution resolveHandler(HttpServletRequest request) {
        try {
            HandlerExecutionChain chain = handlerMapping.getObject().getHandler(request);
            if (chain == null || !(chain.getHandler() instanceof HandlerMethod handlerMethod)) {
                return null;
            }
            RequiresPaymentRegistry.Entry entry = registry.entryFor(handlerMethod.getMethod());
            if (entry != null) {
                return new Resolution(entry, false);
            }
            if (AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), RequiresPayment.class)) {
                return new Resolution(null, true);
            }
            return null;
        } catch (Exception unresolved) {
            // Let the real dispatch (unbuffered) produce whatever error response is appropriate;
            // this filter only needs to know "is this definitely a paid handler", and the answer
            // here is "not resolvable", which is treated the same as "no".
            return null;
        }
    }

    /**
     * @param entry non-null iff this is a known, registered paid handler
     * @param misconfigured {@code true} iff the handler carries {@code @RequiresPayment} but has
     *     no {@link RequiresPaymentRegistry} entry (fail closed: never run it)
     */
    private record Resolution(RequiresPaymentRegistry.@Nullable Entry entry, boolean misconfigured) {}
}
