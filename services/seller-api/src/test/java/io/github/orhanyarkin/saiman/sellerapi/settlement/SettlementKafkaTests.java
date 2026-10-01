package io.github.orhanyarkin.saiman.sellerapi.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.orhanyarkin.saiman.sellerapi.testsupport.SettlementTestBase;
import io.github.orhanyarkin.saiman.shared.payments.PaymentTopics;
import io.github.orhanyarkin.saiman.testsupport.KafkaContainerConfiguration;
import io.github.orhanyarkin.x402.core.PaymentPayload;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;

/** The outbox against a real broker: the value is String JSON without {@code __TypeId__}, keyed by payment key. */
@Import(KafkaContainerConfiguration.class)
@TestPropertySource(properties = "spring.kafka.admin.auto-create=true")
class SettlementKafkaTests extends SettlementTestBase {

    @Autowired
    private KafkaContainer kafka;

    @Test
    void aSettledPaymentReachesKafkaMatchingTheGoldenFixtureShape() throws IOException {
        PaymentPayload payload = newPayload();
        getSummary(payload).expectStatus().isOk();

        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(PaymentTopics.SETTLED));
            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(received::add);
                assertThat(received).isNotEmpty();
            });
            ConsumerRecord<String, String> record = received.get(0);
            JsonNode value = json.readTree(record.value());
            JsonNode golden =
                    json.readTree(new ClassPathResource("fixtures/events/payments.settled.v1.json").getInputStream());

            assertThat(record.headers().lastHeader("__TypeId__")).isNull();
            assertThat(record.key())
                    .isEqualTo(
                            ("eip155:84532:" + value.at("/authorization/asset").asString() + ":"
                                            + value.at("/authorization/payer").asString() + ":"
                                            + value.at("/authorization/nonce").asString())
                                    .toLowerCase(Locale.ROOT));
            assertThat(keys(value)).isEqualTo(keys(golden));
            assertThat(keys(value.get("meta"))).isEqualTo(keys(golden.get("meta")));
            assertThat(keys(value.get("authorization"))).isEqualTo(keys(golden.get("authorization")));
            assertThat(keys(value.get("amount"))).isEqualTo(keys(golden.get("amount")));
            assertThat(value.at("/book").asString()).isEqualTo("SELLER");
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(
                                    jdbc.sql("SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                                            .query(Long.class)
                                            .single())
                            .isZero());
        }
    }

    private static Set<String> keys(JsonNode node) {
        return new TreeSet<>(node.propertyNames());
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
