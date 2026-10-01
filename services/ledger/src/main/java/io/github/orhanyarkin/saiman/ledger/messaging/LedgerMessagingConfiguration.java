package io.github.orhanyarkin.saiman.ledger.messaging;

import io.github.orhanyarkin.saiman.ledger.payment.ConflictingFactException;
import io.github.orhanyarkin.saiman.ledger.payment.MalformedPaymentEventException;
import io.github.orhanyarkin.saiman.shared.ledger.EntryPosted;
import io.github.orhanyarkin.saiman.shared.ledger.LedgerTopics;
import io.github.orhanyarkin.saiman.shared.ledger.ReconciliationMismatch;
import io.github.orhanyarkin.saiman.shared.payments.PaymentTopics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJacksonJsonMessageConverter;
import org.springframework.messaging.Message;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kafka wiring of the ledger (ADR-0016).
 *
 * <ul>
 *   <li><b>Outbox:</b> {@link EntryPosted} is externalized by Spring Modulith to {@code ledger.entry-posted.v1}
 *       and {@link ReconciliationMismatch} to {@code ledger.reconciliation-mismatch.v1}, key = payment id. The records live in {@code libs/shared}, which does not depend on Modulith, so the
 *       routing is configured here instead of with {@code @Externalized} on the type.
 *   <li><b>Converter:</b> a String JSON converter replaces Modulith's default byte-array one, so the template keeps
 *       String serializers (shared with the dead-letter publisher), and it writes no {@code __TypeId__} header.
 *   <li><b>Errors</b> ({@link RecordFailure}): a record that fails the same way on every delivery (malformed,
 *       conflicting, a constraint or data error from the database) is quarantined at once to {@code
 *       <topic>.ledger-dlt} (bounded headers, no stack trace; counter {@code saiman.ledger.dlt}); if even that send
 *       fails the record is counted and skipped, so poison never blocks the partition. Connection, timeout and lock
 *       errors are retried with exponential back-off (1 s doubling to 30 s, jittered) until they succeed: an outage
 *       never dead-letters a valid fact. Anything unclassified (a bug, schema or permission drift) is retried the same way for at most
 *       {@link #UNKNOWN_RETRY_LIMIT}, then quarantined with {@code outcome=exhausted}.
 *   <li><b>Topics:</b> single-partition topics for local and compose use; creating one that exists is a no-op.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class LedgerMessagingConfiguration {

    /** Suffix of the ledger's dead-letter topics: {@code <topic>.ledger-dlt}. */
    public static final String DLT_SUFFIX = ".ledger-dlt";

    /** First retry delay of a transient failure; doubles up to {@link #MAX_BACKOFF}, never gives up. */
    static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);

    /** Well below {@code max.poll.interval.ms} (5 min), so a retry sleep never gets the consumer evicted. */
    static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    /** How long a failure that is neither deterministic nor transient is retried before it is quarantined. */
    static final Duration UNKNOWN_RETRY_LIMIT = Duration.ofHours(1);

    static final int MAX_DLT_HEADERS = 16;
    static final int MAX_DLT_HEADER_BYTES = 256;

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    EventExternalizationConfiguration eventExternalizationConfiguration() {
        return EventExternalizationConfiguration.externalizing()
                .select(event -> event instanceof EntryPosted || event instanceof ReconciliationMismatch)
                .route(
                        EntryPosted.class,
                        event -> RoutingTarget.forTarget(LedgerTopics.ENTRY_POSTED)
                                .andKey(event.paymentId().toString()))
                .route(
                        ReconciliationMismatch.class,
                        event -> RoutingTarget.forTarget(LedgerTopics.RECONCILIATION_MISMATCH)
                                .andKey(event.paymentId().toString()))
                .build();
    }

    @Bean
    RecordMessageConverter kafkaMessageConverter(JsonMapper jsonMapper) {
        return new StringJacksonJsonMessageConverter(jsonMapper) {
            @Override
            protected Headers initialRecordHeaders(Message<?> message) {
                // The default adds a __TypeId__ header naming the Java class; the contract is the JSON schema in
                // docs/events, not a class name, so nothing is added.
                return new RecordHeaders();
            }
        };
    }

    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> template, MeterRegistry meters) {
        var publisher =
                new DeadLetterPublishingRecoverer(
                        template,
                        // Partition -1: let the broker choose, so a DLT with fewer partitions than its source still
                        // works.
                        (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1)) {
                    @Override
                    protected ProducerRecord<Object, Object> createProducerRecord(
                            ConsumerRecord<?, ?> record,
                            TopicPartition topicPartition,
                            Headers headers,
                            byte @Nullable [] key,
                            byte @Nullable [] value) {
                        return super.createProducerRecord(record, topicPartition, boundedHeaders(headers), key, value);
                    }
                };
        // No exception message, cause or stack trace: they may echo payload text and inflate the record.
        publisher.excludeHeader(
                DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd.EX_MSG,
                DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd.EX_CAUSE,
                DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd.EX_STACKTRACE);
        BackOff transientBackOff = backOff(null);
        BackOff unknownBackOff = backOff(UNKNOWN_RETRY_LIMIT);
        var handler = new DefaultErrorHandler(new QuarantineRecoverer(publisher, meters), transientBackOff);
        handler.addNotRetryableExceptions(MalformedPaymentEventException.class, ConflictingFactException.class);
        // The handler's own classification only knows exception types; RecordFailure also walks the causes and
        // separates a lost connection (a NonTransientDataAccessException subtype in Spring) from a constraint
        // violation. A deterministic failure gets a back-off that stops at once, so it is quarantined on the first
        // delivery.
        handler.setBackOffFunction((record, exception) -> switch (RecordFailure.classify(exception)) {
            case DETERMINISTIC -> new FixedBackOff(0, 0);
            case TRANSIENT -> transientBackOff;
            case UNKNOWN -> unknownBackOff;
        });
        return handler;
    }

    /** Exponential back-off, 1 s doubling to 30 s, jittered; unlimited unless {@code maxElapsed} is given. */
    private static BackOff backOff(@Nullable Duration maxElapsed) {
        var backOff = new ExponentialBackOff(INITIAL_BACKOFF.toMillis(), 2.0);
        backOff.setMaxInterval(MAX_BACKOFF.toMillis());
        backOff.setJitter(INITIAL_BACKOFF.toMillis() / 2);
        if (maxElapsed != null) {
            backOff.setMaxElapsedTime(maxElapsed.toMillis());
        }
        return backOff;
    }

    /**
     * The dead-letter record keeps only the recoverer's own {@code kafka_dlt-*} headers, at most
     * {@value #MAX_DLT_HEADERS} of them and each value cut to {@value #MAX_DLT_HEADER_BYTES} bytes. The source
     * record's headers are attacker-controlled (Redpanda is unauthenticated until M6) and are dropped, so a forged
     * record cannot grow past the broker's size limit on its way to quarantine.
     */
    static Headers boundedHeaders(Headers headers) {
        var bounded = new RecordHeaders();
        int count = 0;
        for (Header header : headers) {
            if (count == MAX_DLT_HEADERS) {
                break;
            }
            if (!header.key().startsWith(KafkaHeaders.PREFIX + "dlt-")) {
                continue;
            }
            byte[] value = header.value();
            if (value != null && value.length > MAX_DLT_HEADER_BYTES) {
                value = Arrays.copyOf(value, MAX_DLT_HEADER_BYTES);
            }
            bounded.add(header.key(), value);
            count++;
        }
        return bounded;
    }

    @Bean
    KafkaAdmin.NewTopics ledgerTopics() {
        List<String> payments = List.of(PaymentTopics.AUTHORIZED, PaymentTopics.SETTLED, PaymentTopics.FAILED);
        var topics = new java.util.ArrayList<NewTopic>();
        for (String topic : payments) {
            topics.add(topic(topic));
            topics.add(topic(topic + DLT_SUFFIX));
        }
        topics.add(topic(LedgerTopics.ENTRY_POSTED));
        topics.add(topic(LedgerTopics.RECONCILIATION_MISMATCH));
        return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(1).replicas(1).build();
    }
}
