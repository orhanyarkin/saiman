package io.github.orhanyarkin.saiman.orchestrator.outbox;

import io.github.orhanyarkin.saiman.orchestrator.events.RunStepExternalized;
import io.github.orhanyarkin.saiman.shared.payments.PaymentAuthorized;
import io.github.orhanyarkin.saiman.shared.payments.PaymentFailed;
import io.github.orhanyarkin.saiman.shared.payments.PaymentSettled;
import io.github.orhanyarkin.saiman.shared.payments.PaymentTopics;
import java.util.Set;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJacksonJsonMessageConverter;
import org.springframework.messaging.Message;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.json.JsonMapper;

/**
 * Which events leave the process, to which topic, with which key and payload (ADR-0016).
 *
 * <p>The event records live in {@code libs/shared}, which does not depend on Spring Modulith, so routing is
 * declared here instead of with {@code @Externalized} on the records: the same effect ({@code topic::key}), kept
 * in one place. Routing reads the original event; the record converter below writes it as a JSON string with Boot's
 * {@link JsonMapper} (so {@code Money} is {@code {atomicUnits, asset, decimals}}) and no type header. A run step's
 * value is its stored SSE envelope, unchanged.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
class OutboxConfiguration {

    /** The run-step topic (docs/events/agent.run-step.v1.md). */
    static final String RUN_STEPS = "agent.run-step.v1";

    private static final Set<Class<?>> EXTERNALIZED =
            Set.of(PaymentAuthorized.class, PaymentSettled.class, PaymentFailed.class, RunStepExternalized.class);

    /**
     * Static and with a lazy mapper: Modulith's event-listener factory needs this bean while listeners are being
     * registered, before Boot's Jackson configuration can be created; the mapper is resolved on first use.
     */
    @Bean
    static EventExternalizationConfiguration eventExternalizationConfiguration(ObjectProvider<JsonMapper> mapper) {
        return EventExternalizationConfiguration.externalizing()
                .selectByType(EXTERNALIZED::contains)
                .route(
                        PaymentAuthorized.class,
                        event -> RoutingTarget.forTarget(PaymentTopics.AUTHORIZED)
                                .andKey(event.authorization().paymentKey()))
                .route(
                        PaymentSettled.class,
                        event -> RoutingTarget.forTarget(PaymentTopics.SETTLED)
                                .andKey(event.authorization().paymentKey()))
                .route(
                        PaymentFailed.class,
                        event -> RoutingTarget.forTarget(PaymentTopics.FAILED)
                                .andKey(event.authorization().paymentKey()))
                .route(
                        RunStepExternalized.class,
                        event -> RoutingTarget.forTarget(RUN_STEPS)
                                .andKey(event.runId().toString()))
                // The run step's value is its stored SSE envelope; as a tree, the converter writes it unchanged.
                .mapping(RunStepExternalized.class, step -> mapper.getObject().readTree(step.envelope()))
                .build();
    }

    /**
     * Kafka values are the event's JSON as a String, written by Boot's {@link JsonMapper}; the default converter
     * would add a {@code __TypeId__} header naming the Java class, but the contract is the JSON schema in
     * docs/events, so no header is added (the same setup as the ledger).
     */
    @Bean
    RecordMessageConverter kafkaMessageConverter(JsonMapper jsonMapper) {
        return new StringJacksonJsonMessageConverter(jsonMapper) {
            @Override
            protected Headers initialRecordHeaders(Message<?> message) {
                return new RecordHeaders();
            }
        };
    }

    /** The topics this service produces: one partition each (one broker, a few events per run). */
    @Bean
    KafkaAdmin.NewTopics orchestratorTopics() {
        return new KafkaAdmin.NewTopics(
                topic(PaymentTopics.AUTHORIZED),
                topic(PaymentTopics.SETTLED),
                topic(PaymentTopics.FAILED),
                topic(RUN_STEPS));
    }

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(1).replicas(1).build();
    }
}
