package io.github.orhanyarkin.x402.server;

import io.github.orhanyarkin.x402.core.SettlementResponse;
import io.github.orhanyarkin.x402.core.VerifyResponse;
import io.github.orhanyarkin.x402.facilitator.FacilitatorException;
import io.github.orhanyarkin.x402.facilitator.FacilitatorReason;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Observation helpers for the facilitator's {@code /verify} and {@code /settle} calls: one
 * {@link Observation} per call (a span plus a timer), tagged only with the closed, low-cardinality
 * keys {@link #OUTCOME_KEY} and {@link #REASON_KEY}.
 *
 * <p>Nothing here ever sees, tags or logs a signature, a payload, a nonce or the facilitator's
 * free-text message: classification reads only {@code success}/{@code isValid}, the bounded
 * reason code (mapped through {@link FacilitatorReason}) and, for a well-formed value, the public
 * transaction hash.
 *
 * <p>Package-private: an internal detail of the server side.
 */
final class FacilitatorTelemetry {

    static final String SETTLE_OBSERVATION = "x402.facilitator.settle";
    static final String VERIFY_OBSERVATION = "x402.facilitator.verify";
    static final String OUTCOME_KEY = "outcome";
    static final String REASON_KEY = "reason";

    static final Pattern TRANSACTION_HASH = Pattern.compile("0x[0-9a-fA-F]{64}");

    private static final String PENDING_CODE = FacilitatorReason.SETTLEMENT_PENDING.code();

    /** How one facilitator call ended. The tag value is {@link #tag()}. */
    enum Outcome {
        SUCCESS,
        REJECTED,
        AMBIGUOUS,
        TRANSPORT_ERROR,
        CIRCUIT_OPEN,
        MALFORMED;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The classified result of one call.
     *
     * @param httpStatus the facilitator's HTTP status; {@code 200} for a decoded answer (the HTTP
     *     client only hands back 2xx bodies), {@code 0} when no response was received
     */
    record Result(Outcome outcome, FacilitatorReason reason, int httpStatus) {}

    private final ObservationRegistry registry;

    FacilitatorTelemetry(ObservationRegistry registry) {
        this.registry = registry;
    }

    /** Starts {@code name} as a child of {@code parent}, so the span nests under the payment span. */
    Observation start(String name, @Nullable Observation parent) {
        return Observation.createNotStarted(name, registry)
                .parentObservation(parent)
                .start();
    }

    /** Tags and stops {@code observation}. Never throws into the payment path. */
    static void finish(Observation observation, Result result) {
        try {
            observation.lowCardinalityKeyValue(OUTCOME_KEY, result.outcome().tag());
            observation.lowCardinalityKeyValue(REASON_KEY, result.reason().code());
            observation.stop();
        } catch (RuntimeException ignored) {
            // A broken observation handler must not change a payment outcome.
        }
    }

    static Result ofFailure(RuntimeException error) {
        if (error instanceof FacilitatorException facilitatorError) {
            Outcome outcome = switch (facilitatorError.failure()) {
                case CIRCUIT_OPEN -> Outcome.CIRCUIT_OPEN;
                case MALFORMED -> Outcome.MALFORMED;
                case REJECTED -> Outcome.REJECTED;
                case TRANSPORT -> Outcome.TRANSPORT_ERROR;
            };
            return new Result(outcome, FacilitatorReason.NONE, facilitatorError.httpStatus());
        }
        return new Result(Outcome.TRANSPORT_ERROR, FacilitatorReason.NONE, 0);
    }

    static Result ofSettle(SettlementResponse settlement) {
        String transaction = settlement.transaction();
        boolean wellFormedHash =
                transaction != null && TRANSACTION_HASH.matcher(transaction).matches();
        if (settlement.success()) {
            return new Result(wellFormedHash ? Outcome.SUCCESS : Outcome.MALFORMED, FacilitatorReason.NONE, 200);
        }
        String code = settlement.errorReason();
        FacilitatorReason reason = FacilitatorReason.fromCode(code);
        // settlement_pending: the facilitator broadcast and does not yet know the result.
        Outcome outcome = PENDING_CODE.equals(code) ? Outcome.AMBIGUOUS : Outcome.REJECTED;
        return new Result(outcome, reason, 200);
    }

    static Result ofVerify(VerifyResponse verification) {
        if (verification.isValid()) {
            return new Result(Outcome.SUCCESS, FacilitatorReason.NONE, 200);
        }
        return new Result(Outcome.REJECTED, FacilitatorReason.fromCode(verification.invalidReason()), 200);
    }

    /**
     * A short, stable correlation id for an EIP-3009 authorization that is safe to log: the first 8
     * hex characters of {@code sha256(lowercase(from) + lowercase(nonce))}. 32 bits of a one-way hash
     * of a 256-bit random nonce reveal nothing usable about the nonce, yet let an operator match a
     * WARN line to the same authorization in the ledger or on chain (where the nonce is public) by
     * recomputing the prefix.
     */
    static String nonceRef(String from, @Nullable String nonce) {
        String input = from.toLowerCase(Locale.ROOT) + (nonce == null ? "" : nonce.toLowerCase(Locale.ROOT));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
