package io.github.orhanyarkin.saiman.sellerapi.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.orhanyarkin.saiman.sellerapi.testsupport.FakeIngestServer;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.RagTestBase;
import io.github.orhanyarkin.saiman.shared.payments.PaymentTopics;
import io.github.orhanyarkin.saiman.testsupport.KafkaContainerConfiguration;
import io.github.orhanyarkin.x402.core.X402Headers;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * End to end against a real broker (ADR-0021): a paid question whose model is unavailable is settled up front,
 * answered 503 through {@code DisclosureProblemAdvice} with {@code PAYMENT-RESPONSE}, and both facts leave the
 * process through the outbox, matching the golden fixtures' shape and keyed by payment key.
 */
@Import(KafkaContainerConfiguration.class)
@TestPropertySource(properties = "spring.kafka.admin.auto-create=true")
class CreditNoteKafkaTests extends RagTestBase {

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private JsonMapper json;

    @Test
    void aPaid503AfterSettlementReachesKafkaAsSettledAndCreditNoteIssued() throws IOException {
        INGEST.retrieves(
                List.of(
                        FakeIngestServer.chunk("kap:5:0000", "THYAO", "one"),
                        FakeIngestServer.chunk("kap:5:0001", "THYAO", "two")),
                "v-credit-kafka");
        router.failWith(new IllegalStateException("provider down"));

        postPaid("/v1/disclosures/THYAO/questions", "20000", "{\"question\":\"What did the board decide?\"}")
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .exists(X402Headers.PAYMENT_RESPONSE)
                .expectHeader()
                .contentType("application/problem+json");

        Map<String, Object> settlement =
                jdbc.sql("SELECT * FROM settlement").query().singleRow();
        assertThat(settlement.get("outcome")).isEqualTo("SETTLED");
        assertOneCreditNote(503);
        assertThat(creditNotes().get(0).get("tx_hash")).isEqualTo(settlement.get("tx_hash"));

        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(PaymentTopics.SETTLED, PaymentTopics.CREDIT_NOTE_ISSUED));
            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            String key = (String) settlement.get("payment_key");
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(received::add);
                assertThat(received.stream().filter(r -> key.equals(r.key())).map(ConsumerRecord::topic))
                        .contains(PaymentTopics.SETTLED, PaymentTopics.CREDIT_NOTE_ISSUED);
            });

            ConsumerRecord<String, String> creditNote = received.stream()
                    .filter(r -> key.equals(r.key()) && r.topic().equals(PaymentTopics.CREDIT_NOTE_ISSUED))
                    .findFirst()
                    .orElseThrow();
            JsonNode value = json.readTree(creditNote.value());
            JsonNode golden = json.readTree(
                    new ClassPathResource("fixtures/events/payments.credit-note-issued.v1.json").getInputStream());

            assertThat(creditNote.headers().lastHeader("__TypeId__")).isNull();
            assertThat(key)
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
            assertThat(value.at("/meta/producer").asString()).isEqualTo("seller-api");
            assertThat(value.at("/meta/eventId").asString())
                    .isEqualTo(SettlementRecorder.eventId(key, "CREDIT_NOTE").toString());
            assertThat(value.at("/httpStatus").asInt()).isEqualTo(503);
            assertThat(value.at("/reasonCode").asString()).isEqualTo("handler_server_error");
            assertThat(value.at("/txHash").asString()).isEqualTo(settlement.get("tx_hash"));
            assertThat(value.at("/amount/atomicUnits").asLong()).isEqualTo(20_000L);

            JsonNode settled = json.readTree(received.stream()
                    .filter(r -> key.equals(r.key()) && r.topic().equals(PaymentTopics.SETTLED))
                    .findFirst()
                    .orElseThrow()
                    .value());
            JsonNode settledGolden =
                    json.readTree(new ClassPathResource("fixtures/events/payments.settled.v1.json").getInputStream());
            assertThat(keys(settled)).isEqualTo(keys(settledGolden));
            assertThat(settled.at("/txHash").asString()).isEqualTo(settlement.get("tx_hash"));
        }
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(
                                jdbc.sql("SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                                        .query(Long.class)
                                        .single())
                        .isZero());
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
