package io.github.orhanyarkin.saiman.orchestrator.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentHandle;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentStatus;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.SpendTestSupport;
import io.github.orhanyarkin.saiman.shared.payments.PaymentTopics;
import io.github.orhanyarkin.saiman.testsupport.KafkaContainerConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The outbox against a real broker (Kafka): published events reach their topics keyed by the payment key, and
 * with the broker unreachable a paid call still completes, its publications stay incomplete and are delivered
 * once the broker is back and they are resubmitted.
 */
@Import(KafkaContainerConfiguration.class)
@TestPropertySource(
        properties = {
            "spring.kafka.admin.auto-create=true",
            "spring.kafka.producer.properties.max.block.ms=3000",
            "spring.kafka.producer.properties.request.timeout.ms=2000",
            "spring.kafka.producer.properties.delivery.timeout.ms=3000"
        })
class KafkaExternalizationTests extends SpendTestSupport {

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private IncompleteEventPublications incomplete;

    @Autowired
    private JsonMapper json;

    @Test
    void paymentEventsReachKafkaAndSurviveABrokerOutage() {
        UUID run = createRun(50_000);
        PaymentIntentHandle first = newIntent(run);
        assertThat(client.send(first, null).paid()).isTrue();

        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(PaymentTopics.AUTHORIZED, PaymentTopics.SETTLED, OutboxConfiguration.RUN_STEPS));
            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(received::add);
                assertThat(received)
                        .extracting(ConsumerRecord::topic)
                        .contains(PaymentTopics.AUTHORIZED, PaymentTopics.SETTLED);
            });
            ConsumerRecord<String, String> settled = received.stream()
                    .filter(r -> r.topic().equals(PaymentTopics.SETTLED))
                    .findFirst()
                    .orElseThrow();
            JsonNode value = json.readTree(settled.value());
            assertThat(settled.key())
                    .isEqualTo(
                            ("eip155:84532:" + value.at("/authorization/asset").asString() + ":"
                                            + value.at("/authorization/payer").asString() + ":"
                                            + value.at("/authorization/nonce").asString())
                                    .toLowerCase(java.util.Locale.ROOT));
            assertThat(value.at("/paymentIntentId").asString())
                    .isEqualTo(first.id().toString());
            assertThat(settled.headers().lastHeader("__TypeId__")).isNull();
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(incompleteCount()).isZero());

            // Broker unreachable: the paid call is not affected, the publications wait in the registry.
            kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
            PaymentIntentHandle second;
            try {
                second = newIntent(run);
                assertThat(client.send(second, null).paid()).isTrue();
                assertThat(intents.find(second.id()).orElseThrow().status()).isEqualTo(PaymentIntentStatus.SETTLED);
                await().pollDelay(Duration.ofSeconds(4))
                        .atMost(Duration.ofSeconds(20))
                        .untilAsserted(() -> assertThat(incompleteCount()).isGreaterThanOrEqualTo(2));
            } finally {
                kafka.getDockerClient()
                        .unpauseContainerCmd(kafka.getContainerId())
                        .exec();
            }

            incomplete.resubmitIncompletePublicationsOlderThan(Duration.ZERO);

            String secondId = second.id().toString();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(received::add);
                assertThat(received.stream()
                                .filter(r -> r.topic().equals(PaymentTopics.SETTLED))
                                .map(r -> json.readTree(r.value())
                                        .at("/paymentIntentId")
                                        .asString()))
                        .contains(secondId);
            });
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(incompleteCount()).isZero());
        }
    }

    private long incompleteCount() {
        return jdbc.sql("SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                .query(Long.class)
                .single();
    }

    private KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class));
    }
}
