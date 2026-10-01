package io.github.orhanyarkin.saiman.ledger.messaging;

import io.github.orhanyarkin.saiman.ledger.payment.ConflictingFactException;
import io.github.orhanyarkin.saiman.ledger.payment.MalformedPaymentEventException;
import io.github.orhanyarkin.saiman.shared.ledger.EntryPosted;
import io.github.orhanyarkin.saiman.shared.ledger.LedgerTopics;
import io.github.orhanyarkin.saiman.shared.ledger.ReconciliationMismatch;
import io.github.orhanyarkin.saiman.shared.payments.PaymentTopics;
import java.time.Clock;
import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJacksonJsonMessageConverter;
import org.springframework.messaging.Message;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kafka wiring of the ledger (ADR-0016).
 *
 * <ul>
 *   <li><b>Outbox:</b> {@link EntryPosted} is externalized by Spring Modulith to {@code ledger.entry-posted.v1}
 *       and {@link ReconciliationMismatch} to {@code ledger.reconciliation-mismatch.v1}, key = payment id. The record lives in {@code libs/shared}, which does not depend on Modulith, so the
 *       routing is configured here instead of with {@code @Externalized} on the type.
 *   <li><b>Converter:</b> a String JSON converter replaces Modulith's default byte-array one, so the template keeps
 *       String serializers (shared with the dead-letter publisher), and it writes no {@code __TypeId__} header.
 *   <li><b>Errors:</b> a malformed or conflicting record goes straight to {@code <topic>.ledger-dlt}; anything else
 *       (database down, lock timeout) is retried {@value #RETRIES} times, then dead-lettered. Never an endless loop.
 *   <li><b>Topics:</b> single-partition topics for local and compose use; creating one that exists is a no-op.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class LedgerMessagingConfiguration {

    /** Suffix of the ledger's dead-letter topics: {@code <topic>.ledger-dlt}. */
    public static final String DLT_SUFFIX = ".ledger-dlt";

    static final int RETRIES = 3;

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
    CommonErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> template) {
        var recoverer = new DeadLetterPublishingRecoverer(
                template,
                // Partition -1: let the broker choose, so a DLT with fewer partitions than its source still works.
                (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(500L, RETRIES));
        handler.addNotRetryableExceptions(MalformedPaymentEventException.class, ConflictingFactException.class);
        return handler;
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
