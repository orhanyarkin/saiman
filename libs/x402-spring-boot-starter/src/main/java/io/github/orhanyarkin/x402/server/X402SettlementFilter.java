package io.github.orhanyarkin.x402.server;

import io.github.orhanyarkin.x402.core.Eip3009Authorization;
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
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
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
 *       {@code /settle} ({@link PaymentSettler}) and builds a minimal, server-controlled {@code
 *       PAYMENT-RESPONSE} from the result (never re-serialising the facilitator's own response body
 *       verbatim); on failure it discards the handler's body ({@link
 *       ContentCachingResponseWrapper#reset()}, restoring the pre-dispatch header snapshot) and
 *       writes a fresh 402, keeping the nonce claim (the outcome is ambiguous -- see {@link
 *       X402PaymentFailedEvent}). A non-2xx (including 3xx: content is only ever delivered via a
 *       2xx) handler response means nothing is settled, the nonce claim is released, and the
 *       handler's own response is passed through untouched. Anything unexpected while finishing a
 *       verified settlement (a malformed facilitator response, an unforeseen failure) is caught
 *       and routed through the same ambiguous-failure path -- never left to escape and have the
 *       still-buffered handler body flushed unchecked.
 * </ol>
 *
 * <h2>Upfront flow</h2>
 *
 * For a handler annotated {@code paymentFlow = UPFRONT} the interceptor settles before the handler
 * runs, so this filter only finishes the answer (ADR-0021). It first checks whether a settle was
 * attempted, before any claim-release logic: a failed settle has already written its {@code 402}
 * and keeps the claim. After a successful settle:
 *
 * <ul>
 *   <li>2xx: delivered with {@code PAYMENT-RESPONSE}; outcome {@code settled}.
 *   <li>3xx/4xx/5xx (typically an {@code @ExceptionHandler} mapping, e.g. a 503): the handler's own
 *       status and body are kept, {@code PAYMENT-RESPONSE} is added, the outcome is {@code
 *       paid_not_served} and an {@link X402PaidRequestFailedEvent} is published. {@code
 *       sendError}/{@code sendRedirect} are captured (not committed) so the header always fits; a
 *       {@code sendError} without a body gets a Problem Details body that echoes nothing.
 *   <li>An exception no handler mapped: the handler's output is discarded, the buyer gets {@code
 *       500 application/problem+json} with {@code PAYMENT-RESPONSE}, the event is published with
 *       {@code handler_exception}, only the exception class is logged and the exception is
 *       <em>not</em> rethrown (the container would otherwise render its own error page without the
 *       header).
 * </ul>
 *
 * The nonce claim is never released in the upfront flow, so the paid authorization cannot buy a
 * second handler run.
 */
public final class X402SettlementFilter extends OncePerRequestFilter {

    static final String ATTEMPT_ATTRIBUTE = X402SettlementFilter.class.getName() + ".ATTEMPT";

    private static final Logger log = LoggerFactory.getLogger(X402SettlementFilter.class);

    private static final String PAID_NOT_SERVED = "paid_not_served";

    private final ObjectProvider<RequestMappingHandlerMapping> handlerMapping;
    private final RequiresPaymentRegistry registry;
    private final PaymentNonceStore nonceStore;
    private final X402Codec codec;
    private final ObservationRegistry observationRegistry;
    private final Clock clock;
    private final PaymentSettler settler;

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
        this.nonceStore = nonceStore;
        this.codec = codec;
        this.observationRegistry = observationRegistry;
        this.clock = clock;
        this.settler = new PaymentSettler(facilitatorClient, codec, properties, eventPublisher, clock);
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
                .lowCardinalityKeyValue(
                        X402ObservationKeys.PAYMENT_FLOW, entry.paymentFlow().wireValue())
                .start();
        Map<String, List<String>> headerSnapshot = snapshotHeaders(response);
        X402PaymentAttempt attempt = new X402PaymentAttempt(observation, entry, headerSnapshot);
        request.setAttribute(ATTEMPT_ATTRIBUTE, attempt);

        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        UpfrontResponseWrapper upfrontResponse = entry.upfront() ? new UpfrontResponseWrapper(wrappedResponse) : null;
        try (Observation.Scope scope = observation.openScope()) {
            filterChain.doFilter(request, upfrontResponse != null ? upfrontResponse : wrappedResponse);
            if (request.isAsyncStarted()) {
                handleUnexpectedAsync(request, attempt);
                return;
            }
            afterDispatch(request, wrappedResponse, upfrontResponse, attempt);
        } catch (RuntimeException | IOException | ServletException dispatchFailure) {
            observation.error(dispatchFailure);
            if (attempt.settleAttempted() && attempt.settled() && !attempt.paidFailureReported()) {
                // Upfront flow: the buyer has paid. Answer 500 with the settlement instead of letting
                // the container render an error page without it, and never rethrow.
                log.warn("a paid upfront @RequiresPayment handler failed: {}", rootClassName(dispatchFailure));
                discardHandlerOutput(wrappedResponse, headerSnapshot);
                reportPaidNotServed(
                        request,
                        wrappedResponse,
                        attempt,
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        X402PaidRequestFailedEvent.HANDLER_EXCEPTION,
                        true);
                return;
            }
            if (attempt.settleAttempted() && attempt.settled()) {
                // Already answered and reported; a late failure (e.g. writing the buffer) is not a
                // second paid failure.
                return;
            }
            attempt.outcome("dispatch_error");
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
                PaymentSettler.restoreHeaders(wrappedResponse, headerSnapshot);
            }
        } catch (RuntimeException ignored) {
            // the original failure is what the caller must see
        }
    }

    private void handleUnexpectedAsync(HttpServletRequest request, X402PaymentAttempt attempt) {
        if (attempt.settleAttempted() && attempt.settled()) {
            log.error("an upfront @RequiresPayment handler started asynchronous processing; this is not supported"
                    + " and should have been rejected at startup -- the payment was already settled and is"
                    + " reported as paid but not served");
            // Upfront: the money already moved. Keep the claim (no second run on the same
            // authorization) and report the paid failure; the response is out of this filter's hands.
            attempt.outcome(PAID_NOT_SERVED);
            publishPaidRequestFailed(
                    request,
                    attempt,
                    HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    X402PaidRequestFailedEvent.ASYNC_NOT_SUPPORTED);
            return;
        }
        log.error("a @RequiresPayment handler started asynchronous processing; this is not supported and should"
                + " have been rejected at startup -- the payment will not be settled");
        attempt.outcome("async_not_supported");
        if (attempt.verified() && !attempt.settleAttempted()) {
            nonceStore.release(attempt.nonceKey(), attempt.claimToken());
        }
    }

    private void afterDispatch(
            HttpServletRequest request,
            ContentCachingResponseWrapper wrappedResponse,
            @Nullable UpfrontResponseWrapper upfrontResponse,
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
        if (attempt.settleAttempted()) {
            // Upfront flow. Checked before the claim-release logic below: a settle that failed (or
            // succeeded) must never release the claim.
            afterUpfrontDispatch(request, wrappedResponse, upfrontResponse, attempt);
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
        settler.settle(request, wrappedResponse, attempt);
    }

    private void afterUpfrontDispatch(
            HttpServletRequest request,
            ContentCachingResponseWrapper wrappedResponse,
            @Nullable UpfrontResponseWrapper upfrontResponse,
            X402PaymentAttempt attempt)
            throws IOException {
        if (!attempt.settled()) {
            // The settle before the handler failed: the 402 is already written, the failed event
            // published, the handler never ran. Keep the claim (the outcome is ambiguous).
            return;
        }
        int status = wrappedResponse.getStatus();
        if (status >= 200 && status < 300) {
            setPaymentResponse(wrappedResponse, attempt);
            attempt.outcome("settled");
            return;
        }
        if (status < 300 || status > 599) {
            // 1xx or a non-standard status: not a meaningful answer to a paid request. Replace it.
            discardHandlerOutput(wrappedResponse, attempt.headerSnapshot());
            reportPaidNotServed(
                    request,
                    wrappedResponse,
                    attempt,
                    HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    X402PaidRequestFailedEvent.HANDLER_SERVER_ERROR,
                    true);
            return;
        }
        // sendError without a body (the container would have rendered an error page): give the
        // buyer a Problem Details body that echoes nothing of the request.
        boolean bareSendError = status >= 400
                && upfrontResponse != null
                && upfrontResponse.errorSent()
                && wrappedResponse.getContentSize() == 0;
        reportPaidNotServed(
                request,
                wrappedResponse,
                attempt,
                status,
                X402PaidRequestFailedEvent.reasonForStatus(status),
                bareSendError);
    }

    /**
     * Reports a paid-but-not-served request, then answers it. The report comes first and never
     * depends on the answer: the buyer has paid, so the {@link X402PaidRequestFailedEvent} (the
     * seller's record of what it owes) is published even if the response cannot be written.
     * Adding {@code PAYMENT-RESPONSE} and, if asked, a Problem Details body is best effort.
     */
    private void reportPaidNotServed(
            HttpServletRequest request,
            HttpServletResponse response,
            X402PaymentAttempt attempt,
            int status,
            String reasonCode,
            boolean writeProblemBody) {
        attempt.outcome(PAID_NOT_SERVED);
        publishPaidRequestFailed(request, attempt, status, reasonCode);
        try {
            setPaymentResponse(response, attempt);
            if (writeProblemBody) {
                writeProblem(response, status);
            }
        } catch (IOException | RuntimeException unwritable) {
            log.warn(
                    "could not write the answer to a paid but not served request: {}",
                    unwritable.getClass().getSimpleName());
        }
    }

    private void publishPaidRequestFailed(
            HttpServletRequest request, X402PaymentAttempt attempt, int status, String reasonCode) {
        attempt.markPaidFailureReported();
        Eip3009Authorization authorization = attempt.payload().payload().authorization();
        String txHash = attempt.txHash();
        if (txHash == null) {
            throw new IllegalStateException("a settled attempt has a transaction hash");
        }
        settler.publishSafely(new X402PaidRequestFailedEvent(
                UUID.randomUUID(),
                request.getRequestURI(),
                attempt.entry().offer(),
                authorization.from(),
                authorization.nonce(),
                authorization.value(),
                authorization.validBefore(),
                authorization.from(),
                txHash,
                status,
                reasonCode,
                clock.instant()));
    }

    private static void setPaymentResponse(HttpServletResponse response, X402PaymentAttempt attempt) {
        String header = attempt.paymentResponseHeader();
        if (header != null) {
            response.setHeader(X402Headers.PAYMENT_RESPONSE, header);
        }
    }

    /** A Problem Details body with only the status and its standard title: nothing from the request. */
    private void writeProblem(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        HttpStatus resolved = HttpStatus.resolve(status);
        ProblemDetail problem = ProblemDetail.forStatus(status);
        if (resolved != null) {
            problem.setTitle(resolved.getReasonPhrase());
        }
        problem.setDetail("The request was paid but could not be served");
        response.getWriter().write(codec.writeJson(problem));
    }

    private static String rootClassName(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause instanceof ServletException && cause.getCause() != null && depth < 4; depth++) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
    }

    private static Map<String, List<String>> snapshotHeaders(HttpServletResponse response) {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        Collection<String> names = response.getHeaderNames();
        for (String name : names) {
            snapshot.put(name, new ArrayList<>(response.getHeaders(name)));
        }
        return snapshot;
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

    /**
     * Upfront flow only: turns {@code sendError}/{@code sendRedirect} into a plain status (and
     * {@code Location}) on the buffered response instead of committing it, so the filter can still
     * add {@code PAYMENT-RESPONSE} afterwards. {@link ContentCachingResponseWrapper} itself flushes
     * and commits on both calls, and the container would then render its own error page.
     */
    static final class UpfrontResponseWrapper extends HttpServletResponseWrapper {

        private boolean errorSent;

        UpfrontResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void sendError(int sc) {
            sendError(sc, null);
        }

        @Override
        public void sendError(int sc, @Nullable String msg) {
            // The message is never echoed: it may carry request text.
            resetBuffer();
            setStatus(sc);
            errorSent = true;
        }

        @Override
        public void sendRedirect(String location) {
            sendRedirect(location, HttpServletResponse.SC_FOUND, true);
        }

        @Override
        public void sendRedirect(String location, int sc) {
            sendRedirect(location, sc, true);
        }

        @Override
        public void sendRedirect(String location, boolean clearBuffer) {
            sendRedirect(location, HttpServletResponse.SC_FOUND, clearBuffer);
        }

        @Override
        public void sendRedirect(String location, int sc, boolean clearBuffer) {
            if (clearBuffer) {
                resetBuffer();
            }
            setStatus(sc);
            setHeader(HttpHeaders.LOCATION, location);
        }

        boolean errorSent() {
            return errorSent;
        }
    }
}
