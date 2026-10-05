package io.github.orhanyarkin.x402.server;

import io.github.orhanyarkin.x402.core.Eip3009Authorization;
import io.github.orhanyarkin.x402.core.SettlementResponse;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.orhanyarkin.x402.core.X402Headers;
import io.github.orhanyarkin.x402.facilitator.FacilitatorClient;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Calls {@code /settle} for one verified {@link X402PaymentAttempt} and turns the result into the
 * client-facing answer: on success a minimal, server-built {@code PAYMENT-RESPONSE} header and an
 * {@link X402PaymentSettledEvent}; on any failure a fresh {@code 402} (the response is reset to the
 * pre-dispatch header snapshot first, so nothing the handler wrote leaks), the nonce claim kept
 * (the outcome is ambiguous) and an {@link X402PaymentFailedEvent}.
 *
 * <p>Shared by both flows: {@link X402SettlementFilter} calls it after a 2xx handler response (the
 * default {@code authorization} flow), {@link RequiresPaymentInterceptor} before the handler runs
 * (the {@code upfront} flow). One implementation keeps the money-relevant checks (success flag,
 * well-formed transaction hash, never echoing facilitator text) identical in both.
 *
 * <p>Package-private: an internal detail of this starter's server side.
 */
final class PaymentSettler {

    private static final Logger log = LoggerFactory.getLogger(PaymentSettler.class);

    private static final Pattern TRANSACTION_HASH_PATTERN = Pattern.compile("0x[0-9a-fA-F]{64}");

    /** A facilitator reason code is logged only in this shape; anything else is reported as unrecognised. */
    private static final Pattern REASON_CODE = Pattern.compile("[a-z0-9_]{1,64}");

    private final FacilitatorClient facilitatorClient;
    private final X402Codec codec;
    private final X402ServerProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final FacilitatorTelemetry telemetry;

    /** What the single settle-failure WARN reports about the call itself. */
    private record SettleTrace(
            int httpStatus,
            long durationMs,
            long verifyToSettleGapMs,
            @Nullable String txHash,
            boolean txHashPresent,
            @Nullable String cause) {}

    PaymentSettler(
            FacilitatorClient facilitatorClient,
            X402Codec codec,
            X402ServerProperties properties,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            ObservationRegistry observationRegistry) {
        this.telemetry = new FacilitatorTelemetry(observationRegistry);
        this.facilitatorClient = facilitatorClient;
        this.codec = codec;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * Settles {@code attempt} (which must be verified). Never throws for a facilitator or response
     * problem: every failure, including a malformed facilitator answer or an unforeseen error while
     * finishing, takes the ambiguous-failure path.
     *
     * @return {@code true} if settled (the {@code PAYMENT-RESPONSE} header is set on {@code
     *     response}); {@code false} if not ({@code response} now holds a {@code 402})
     */
    boolean settle(HttpServletRequest request, HttpServletResponse response, X402PaymentAttempt attempt)
            throws IOException {
        SettlementResponse settlement;
        Observation observation = telemetry.start(FacilitatorTelemetry.SETTLE_OBSERVATION);
        long startedAt = System.nanoTime();
        try {
            settlement =
                    facilitatorClient.settle(attempt.payload(), attempt.entry().offer());
        } catch (RuntimeException settleError) {
            FacilitatorTelemetry.Result result = FacilitatorTelemetry.ofFailure(settleError);
            FacilitatorTelemetry.finish(observation, result);
            failSettlement(
                    request,
                    response,
                    attempt,
                    null,
                    trace(
                            attempt,
                            startedAt,
                            result.httpStatus(),
                            null,
                            settleError.getClass().getSimpleName()));
            return false;
        }
        FacilitatorTelemetry.finish(observation, FacilitatorTelemetry.ofSettle(settlement));
        SettleTrace settleTrace = trace(attempt, startedAt, 200, settlement.transaction(), null);
        // Anything unexpected from here on (a null/malformed transaction hash -- the field isn't
        // @Nullable on SettlementResponse, but the tolerant facilitator-response mapper leaves it
        // null when a hostile or buggy facilitator omits it; or any other failure while finishing
        // the response) must never let a caller deliver the handler's response as paid. Route every
        // such case through failSettlement (reset, keep the nonce claim, ask again) instead of
        // letting an exception escape -- this is the money-safe default, not merely an error handler.
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
                        response,
                        attempt,
                        settlement.success() ? "ambiguous" : settlement.errorReason(),
                        settleTrace);
                return false;
            }

            Eip3009Authorization authorization = attempt.payload().payload().authorization();
            String paymentResponse = codec.encodeSettlementResponse(buildClientFacingSettlement(attempt, transaction));
            response.setHeader(X402Headers.PAYMENT_RESPONSE, paymentResponse);
            attempt.outcome("settled");
            attempt.markSettled(transaction, paymentResponse);
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
            return true;
        } catch (RuntimeException unexpected) {
            log.error(
                    "unexpected failure while finishing settlement for {}: {}",
                    request.getRequestURI(),
                    unexpected.getClass().getSimpleName(),
                    unexpected);
            failSettlement(request, response, attempt, "internal_error", settleTrace);
            return false;
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
            HttpServletResponse response,
            X402PaymentAttempt attempt,
            @Nullable String errorReason,
            SettleTrace trace)
            throws IOException {
        response.reset();
        restoreHeaders(response, attempt.headerSnapshot());
        attempt.outcome("settlement_failed");
        // The facilitator's reason is untrusted text: log and publish it only as a bounded code.
        String reasonCode =
                errorReason != null && REASON_CODE.matcher(errorReason).matches() ? errorReason : "unrecognised";
        UUID eventId = UUID.randomUUID();
        Eip3009Authorization authorization = attempt.payload().payload().authorization();
        // Only public or one-way values (ADR-0008, THREAT_MODEL): never the signature, the payload,
        // the facilitator's errorMessage or the raw nonce.
        log.warn(
                "x402 settlement failed: reason={} attemptId={} payer={} nonceRef={} validAfter={} validBefore={}"
                        + " secondsLeft={} verifyToSettleGapMs={} settleDurationMs={} facilitatorStatus={}"
                        + " txHashPresent={} txHash={} cause={}",
                reasonCode,
                eventId,
                authorization.from(),
                FacilitatorTelemetry.nonceRef(authorization.from(), authorization.nonce()),
                authorization.validAfter(),
                authorization.validBefore(),
                secondsLeft(authorization.validBefore()),
                trace.verifyToSettleGapMs(),
                trace.durationMs(),
                trace.httpStatus(),
                trace.txHashPresent(),
                trace.txHash() == null ? "-" : trace.txHash(),
                trace.cause() == null ? "-" : trace.cause());
        RequiresPaymentInterceptor.writePaymentRequired(
                response,
                codec,
                RequiresPaymentInterceptor.resourceInfo(request, attempt.entry(), properties.publicBaseUrl()),
                attempt.entry().offer(),
                "payment settlement failed");
        publishSafely(new X402PaymentFailedEvent(
                eventId,
                request.getRequestURI(),
                attempt.entry().offer(),
                authorization.from(),
                authorization.nonce(),
                authorization.value(),
                authorization.validBefore(),
                attempt.payer(),
                reasonCode,
                clock.instant()));
    }

    private SettleTrace trace(
            X402PaymentAttempt attempt,
            long startedAtNanos,
            int httpStatus,
            @Nullable String transaction,
            @Nullable String cause) {
        long now = System.nanoTime();
        boolean present = transaction != null && !transaction.isEmpty();
        String wellFormed = present
                        && FacilitatorTelemetry.TRANSACTION_HASH
                                .matcher(transaction)
                                .matches()
                ? transaction
                : null;
        return new SettleTrace(
                httpStatus,
                (now - startedAtNanos) / 1_000_000L,
                (startedAtNanos - attempt.verifiedAtNanos()) / 1_000_000L,
                wellFormed,
                present,
                cause);
    }

    /** Seconds from now until {@code validBefore} (negative once expired); exact for any uint256. */
    private String secondsLeft(String validBefore) {
        return new BigInteger(validBefore)
                .subtract(BigInteger.valueOf(clock.instant().getEpochSecond()))
                .toString();
    }

    /**
     * Publishes {@code event}, logging (never rethrowing) if a listener throws: a broken listener
     * must not corrupt a settlement outcome that has already happened, or the response that has
     * already been decided.
     */
    void publishSafely(Object event) {
        try {
            eventPublisher.publishEvent(event);
        } catch (RuntimeException listenerFailure) {
            log.error(
                    "an x402 payment event listener threw: {}",
                    listenerFailure.getClass().getSimpleName());
        }
    }

    static void restoreHeaders(HttpServletResponse response, Map<String, List<String>> snapshot) {
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
}
