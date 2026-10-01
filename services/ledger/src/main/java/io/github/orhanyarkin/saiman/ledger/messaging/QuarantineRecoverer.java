package io.github.orhanyarkin.saiman.ledger.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;

/**
 * The last step for a quarantined record (malformed or conflicting): publish it to the dead-letter topic and count
 * it in {@code saiman.ledger.dlt}. If even the dead-letter send fails (an oversized forged record, a missing DLT),
 * the record is logged by coordinates only, counted with {@code outcome=failed} and skipped: a poison record must
 * advance the consumer, not loop forever. Transient failures never get here (the error handler retries them).
 */
final class QuarantineRecoverer implements ConsumerAwareRecordRecoverer {

    /** The dead-letter counter: tags {@code topic} (the source topic) and {@code outcome}. */
    static final String METRIC = "saiman.ledger.dlt";

    private static final Logger log = LoggerFactory.getLogger(QuarantineRecoverer.class);

    private final ConsumerAwareRecordRecoverer delegate;
    private final MeterRegistry meters;

    QuarantineRecoverer(ConsumerAwareRecordRecoverer delegate, MeterRegistry meters) {
        this.delegate = delegate;
        this.meters = meters;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, @Nullable Consumer<?, ?> consumer, @Nullable Exception exception) {
        String outcome;
        try {
            delegate.accept(record, consumer, exception);
            outcome = "published";
            log.warn(
                    "Quarantined {}-{}@{} to the dead-letter topic ({})",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    exception == null
                            ? "unknown"
                            : rootCause(exception).getClass().getSimpleName());
        } catch (RuntimeException e) {
            outcome = "failed";
            log.error(
                    "Could not dead-letter {}-{}@{} ({}); the record is skipped",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    e.getClass().getName());
        }
        meters.counter(METRIC, "topic", record.topic(), "outcome", outcome).increment();
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cause = t;
        // Bounded walk: a cyclic cause chain cannot loop.
        for (int depth = 0; depth < 16 && cause.getCause() != null; depth++) {
            cause = cause.getCause();
        }
        return cause;
    }
}
