package io.github.orhanyarkin.saiman.ledger.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.orhanyarkin.saiman.ledger.payment.MalformedPaymentEventException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

/** The quarantine step without a broker: counted when published, counted and skipped when even that fails. */
class QuarantineRecovererTests {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ConsumerRecord<String, String> record =
            new ConsumerRecord<>("payments.authorized.v1", 0, 42L, "k", "{junk");
    private final Exception malformed = new MalformedPaymentEventException("invalid payload");

    @Test
    void publishedRecordIsCounted() {
        new QuarantineRecoverer((r, c, e) -> {}, meters).accept(record, null, malformed);

        assertThat(count("published")).isEqualTo(1.0);
    }

    @Test
    void failedDeadLetterSendIsCountedAndSkippedInsteadOfThrown() {
        var recoverer = new QuarantineRecoverer(
                (r, c, e) -> {
                    throw new IllegalStateException("record too large");
                },
                meters);

        assertThatCode(() -> recoverer.accept(record, null, malformed)).doesNotThrowAnyException();
        assertThat(count("failed")).isEqualTo(1.0);
    }

    @Test
    void deadLetterHeadersKeepOnlyBoundedRecovererHeaders() {
        var headers = new RecordHeaders();
        headers.add("junk", new byte[100_000]);
        headers.add("kafka_dlt-exception-fqcn", "x".repeat(1000).getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 40; i++) {
            headers.add("kafka_dlt-extra-" + i, new byte[1]);
        }

        var bounded = LedgerMessagingConfiguration.boundedHeaders(headers);

        assertThat(bounded.toArray()).hasSize(LedgerMessagingConfiguration.MAX_DLT_HEADERS);
        assertThat(bounded.lastHeader("junk")).isNull();
        assertThat(bounded.lastHeader("kafka_dlt-exception-fqcn").value())
                .hasSize(LedgerMessagingConfiguration.MAX_DLT_HEADER_BYTES);
    }

    private double count(String outcome) {
        return meters.counter(QuarantineRecoverer.METRIC, "topic", "payments.authorized.v1", "outcome", outcome)
                .count();
    }
}
