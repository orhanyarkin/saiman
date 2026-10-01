package io.github.orhanyarkin.saiman.sellerapi.settlement;

import io.github.orhanyarkin.saiman.shared.payments.PaymentFailed;
import io.github.orhanyarkin.saiman.shared.payments.PaymentSettled;
import io.github.orhanyarkin.saiman.shared.payments.PaymentTopics;
import java.util.Set;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
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
 * Which events leave the process, to which topic and with which key (ADR-0016). The same wiring as the
 * orchestrator's: the records live in {@code libs/shared}, which does not depend on Spring Modulith, so routing is
 * declared here rather than with {@code @Externalized}; values are String JSON from Boot's {@link JsonMapper}
 * without a {@code __TypeId__} header, because the contract is the JSON schema in {@code docs/events}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(SettlementOutboxProperties.class)
class SettlementOutboxConfiguration {

    private static final Set<Class<?>> EXTERNALIZED = Set.of(PaymentSettled.class, PaymentFailed.class);

    /** Static: Modulith's listener factory needs this bean before Boot's Jackson configuration exists. */
    @Bean
    static EventExternalizationConfiguration eventExternalizationConfiguration() {
        return EventExternalizationConfiguration.externalizing()
                .selectByType(EXTERNALIZED::contains)
                .route(
                        PaymentSettled.class,
                        event -> RoutingTarget.forTarget(PaymentTopics.SETTLED)
                                .andKey(event.authorization().paymentKey()))
                .route(
                        PaymentFailed.class,
                        event -> RoutingTarget.forTarget(PaymentTopics.FAILED)
                                .andKey(event.authorization().paymentKey()))
                .build();
    }

    @Bean
    RecordMessageConverter kafkaMessageConverter(JsonMapper jsonMapper) {
        return new StringJacksonJsonMessageConverter(jsonMapper) {
            @Override
            protected Headers initialRecordHeaders(Message<?> message) {
                return new RecordHeaders();
            }
        };
    }

    /** Same names and partition count as the orchestrator declares, so the two do not conflict. */
    @Bean
    KafkaAdmin.NewTopics sellerTopics() {
        return new KafkaAdmin.NewTopics(topic(PaymentTopics.SETTLED), topic(PaymentTopics.FAILED));
    }

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(1).replicas(1).build();
    }
}
