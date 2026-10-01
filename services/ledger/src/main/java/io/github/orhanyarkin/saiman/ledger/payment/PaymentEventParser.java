package io.github.orhanyarkin.saiman.ledger.payment;

import io.github.orhanyarkin.saiman.shared.events.EventMetadata;
import io.github.orhanyarkin.saiman.shared.payments.Book;
import io.github.orhanyarkin.saiman.shared.payments.PaymentAuthorized;
import io.github.orhanyarkin.saiman.shared.payments.PaymentFailed;
import io.github.orhanyarkin.saiman.shared.payments.PaymentSettled;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses {@code payments.*} payloads (plain JSON strings, no Kafka type headers) into the shared records with
 * Boot's {@link JsonMapper}, made strict: unknown fields, duplicate keys, floats for integers, strings for numbers
 * or booleans (scalar coercion) and nulls for primitives fail, and every field the schema requires must be present
 * (the records validate formats and ranges). On top of the records, because Redpanda is unauthenticated until M6:
 *
 * <ul>
 *   <li>{@code meta.occurredAt} is required and within {@code [2025-01-01, now + 1 day]};
 *   <li>the producer is bound to the book: buyer facts come from {@code orchestrator}, seller facts from
 *       {@code seller-api}.
 * </ul>
 *
 * Failures become {@link MalformedPaymentEventException} with a fixed message (quarantine, never retried).
 */
@Component
public class PaymentEventParser {

    /** No payment event predates the project. */
    static final Instant EARLIEST = Instant.parse("2025-01-01T00:00:00Z");

    /** Clock skew tolerated between a producer and the ledger. */
    static final Duration MAX_SKEW = Duration.ofDays(1);

    static final String BUYER_PRODUCER = "orchestrator";
    static final String SELLER_PRODUCER = "seller-api";

    private final JsonMapper mapper;
    private final Clock clock;

    public PaymentEventParser(JsonMapper bootMapper, Clock clock) {
        this.mapper = bootMapper
                .rebuild()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        this.clock = clock;
    }

    public PaymentAuthorized authorized(@Nullable String json) {
        PaymentAuthorized event = read(json, PaymentAuthorized.class);
        require(event.meta(), event.authorization(), event.paymentIntentId(), event.runId());
        checkMeta(event.meta(), Book.BUYER);
        return event;
    }

    public PaymentSettled settled(@Nullable String json) {
        PaymentSettled event = read(json, PaymentSettled.class);
        require(event.meta(), event.authorization(), event.book(), event.evidence());
        checkMeta(event.meta(), event.book());
        return event;
    }

    public PaymentFailed failed(@Nullable String json) {
        PaymentFailed event = read(json, PaymentFailed.class);
        require(event.meta(), event.authorization(), event.book(), event.finality());
        checkMeta(event.meta(), event.book());
        return event;
    }

    private void checkMeta(EventMetadata meta, Book book) {
        Instant occurredAt = meta.occurredAt();
        if (Objects.isNull(occurredAt)
                || occurredAt.isBefore(EARLIEST)
                || occurredAt.isAfter(clock.instant().plus(MAX_SKEW))) {
            throw new MalformedPaymentEventException("occurredAt is missing or out of range");
        }
        String expected = book == Book.BUYER ? BUYER_PRODUCER : SELLER_PRODUCER;
        if (!expected.equals(meta.producer())) {
            throw new MalformedPaymentEventException("producer does not report this book");
        }
    }

    private <T> T read(@Nullable String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            throw new MalformedPaymentEventException("empty " + type.getSimpleName() + " payload");
        }
        try {
            T value = mapper.readValue(json, type);
            if (value == null) {
                throw new MalformedPaymentEventException("null " + type.getSimpleName() + " payload");
            }
            return value;
        } catch (JacksonException | IllegalArgumentException | NullPointerException e) {
            // Record constructors throw IllegalArgumentException / NullPointerException for invalid or missing
            // fields; Jackson wraps them. The cause is not kept: dead-letter records carry no stack trace, and
            // Jackson messages may echo payload text.
            throw new MalformedPaymentEventException("invalid " + type.getSimpleName() + " payload");
        }
    }

    /** Reference fields whose absence the records' constructors do not catch on their own. */
    private static void require(@Nullable Object... fields) {
        for (Object field : fields) {
            if (Objects.isNull(field)) {
                throw new MalformedPaymentEventException("a required field is missing");
            }
        }
    }
}
