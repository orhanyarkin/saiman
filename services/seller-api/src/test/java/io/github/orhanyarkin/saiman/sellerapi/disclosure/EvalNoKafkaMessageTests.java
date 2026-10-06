package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.orhanyarkin.saiman.apisecurity.testfixtures.TestTokens;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.FakeIngestServer;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.RagTestBase;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.RawHttp;
import io.github.orhanyarkin.saiman.shared.payments.PaymentTopics;
import io.github.orhanyarkin.saiman.testsupport.KafkaContainerConfiguration;
import java.io.IOException;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Against a real broker: eval calls (answered and failing) produce no Kafka message on any payments topic, while a
 * paid call made afterwards does (the positive control proves the pipeline was live the whole time).
 */
@Import(KafkaContainerConfiguration.class)
@TestPropertySource(properties = "spring.kafka.admin.auto-create=true")
class EvalNoKafkaMessageTests extends RagTestBase {

    @Autowired
    private KafkaContainer kafka;

    @LocalServerPort
    private int port;

    @Test
    void evalCallsProduceNoMessageButAPaidCallDoes() throws IOException {
        INGEST.retrieves(
                List.of(
                        FakeIngestServer.chunk("kap:5:0000", "THYAO", "one"),
                        FakeIngestServer.chunk("kap:5:0001", "THYAO", "two")),
                "v-eval-kafka");
        router.replyWith("{\"answer\":\"x\",\"citedChunkIds\":[\"kap:5:0000\",\"kap:5:0001\"]}");
        String body = "{\"ticker\":\"THYAO\",\"question\":\"What did the board decide?\"}";
        Map<String, String> auth = Map.of("Authorization", TestTokens.bearer(TestTokens.SERVICE_EVALS));
        assertThat(RawHttp.exchange(port, "POST", "/internal/v1/eval/questions", "seller-api", auth, body)
                        .body())
                .contains("ANSWERED");
        router.failWith(new IllegalStateException("provider down"));
        assertThat(RawHttp.exchange(port, "POST", "/internal/v1/eval/questions", "seller-api", auth, body)
                        .body())
                .contains("\"outcome\":\"ERROR\"");

        // Positive control: a paid call that fails after the upfront settle publishes settled + credit note.
        postPaid("/v1/disclosures/THYAO/questions", "20000", "{\"question\":\"What did the board decide?\"}")
                .expectStatus()
                .isEqualTo(503);
        String key =
                (String) jdbc.sql("SELECT payment_key FROM settlement").query().singleValue();

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "eval-no-kafka-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class))) {
            consumer.subscribe(List.of(PaymentTopics.SETTLED, PaymentTopics.FAILED, PaymentTopics.CREDIT_NOTE_ISSUED));
            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(received::add);
                assertThat(received.stream().map(ConsumerRecord::topic))
                        .contains(PaymentTopics.SETTLED, PaymentTopics.CREDIT_NOTE_ISSUED);
            });
            consumer.poll(Duration.ofSeconds(1)).forEach(received::add);
            // Only the paid call's messages: nothing came from the two eval calls.
            assertThat(received).allSatisfy(r -> assertThat(r.key()).isEqualTo(key));
            assertThat(received).hasSize(2);
        }
    }
}
