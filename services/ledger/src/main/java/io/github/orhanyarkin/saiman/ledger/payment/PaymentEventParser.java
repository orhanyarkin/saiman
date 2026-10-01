package io.github.orhanyarkin.saiman.ledger.payment;

import io.github.orhanyarkin.saiman.shared.payments.PaymentAuthorized;
import io.github.orhanyarkin.saiman.shared.payments.PaymentFailed;
import io.github.orhanyarkin.saiman.shared.payments.PaymentSettled;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses {@code payments.*} payloads (plain JSON strings, no Kafka type headers) into the shared records with
 * Boot's {@link JsonMapper}, made strict: unknown fields, floats for integers and nulls for primitives fail, and
 * every field the schema requires must be present (the records validate formats). Failures become
 * {@link MalformedPaymentEventException} with a fixed message.
 */
@Component
public class PaymentEventParser {

    private final JsonMapper mapper;

    public PaymentEventParser(JsonMapper bootMapper) {
        this.mapper = bootMapper
                .rebuild()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .build();
    }

    public PaymentAuthorized authorized(@Nullable String json) {
        PaymentAuthorized event = read(json, PaymentAuthorized.class);
        require(event.meta(), event.authorization(), event.paymentIntentId(), event.runId());
        return event;
    }

    public PaymentSettled settled(@Nullable String json) {
        PaymentSettled event = read(json, PaymentSettled.class);
        require(event.meta(), event.authorization(), event.book(), event.evidence());
        return event;
    }

    public PaymentFailed failed(@Nullable String json) {
        PaymentFailed event = read(json, PaymentFailed.class);
        require(event.meta(), event.authorization(), event.book(), event.finality());
        return event;
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
            // fields; Jackson wraps them. The cause stays for the dead-letter headers, the message is fixed.
            throw new MalformedPaymentEventException("invalid " + type.getSimpleName() + " payload", e);
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
