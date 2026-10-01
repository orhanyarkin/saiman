package io.github.orhanyarkin.saiman.ledger.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.orhanyarkin.saiman.ledger.LedgerIntegrationTest;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentProjection;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentRepository;
import io.github.orhanyarkin.saiman.ledger.payment.TestPayment;
import io.github.orhanyarkin.saiman.shared.ledger.EntryPosted;
import io.github.orhanyarkin.saiman.shared.ledger.LedgerTopics;
import io.github.orhanyarkin.saiman.shared.payments.PaymentAuthorized;
import io.github.orhanyarkin.saiman.shared.payments.PaymentSettled;
import io.github.orhanyarkin.saiman.shared.payments.PaymentTopics;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * End to end through a real Redpanda: raw JSON on the {@code payments.*} topics (as the producers' Modulith
 * externalization writes it, no type headers), the ledger books each entry once whatever the order and however
 * often a record is redelivered, publishes {@code ledger.entry-posted.v1} through the outbox, and dead-letters
 * poison records instead of retrying them forever.
 */
@LedgerIntegrationTest
class PaymentEventsKafkaTests {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private ConsumerFactory<String, String> consumers;

    @Autowired
    private JsonMapper json;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void reorderedAndDuplicatedDeliveryPostsEachEntryOnceAndPublishesEntryPosted() throws Exception {
        TestPayment payment = TestPayment.random(new SplittableRandom(), 20_000);
        PaymentSettled buyerSettled = payment.buyerSettled();
        PaymentSettled sellerSettled = payment.sellerSettled();
        PaymentAuthorized authorized = payment.authorized();
        String key = payment.key();

        // settled (buyer) before authorized, the same settled record twice, and the seller's report last on the topic.
        send(PaymentTopics.SETTLED, key, json.writeValueAsString(buyerSettled));
        send(PaymentTopics.SETTLED, key, json.writeValueAsString(buyerSettled));
        send(PaymentTopics.SETTLED, key, json.writeValueAsString(sellerSettled));
        send(PaymentTopics.AUTHORIZED, key, json.writeValueAsString(authorized));
        send(PaymentTopics.AUTHORIZED, key, json.writeValueAsString(authorized));

        // Records of one partition are processed in order, so once the last record of each topic is in the inbox,
        // the earlier duplicates have been consumed too.
        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(inboxed(sellerSettled.meta().eventId())).isTrue();
            assertThat(inboxed(authorized.meta().eventId())).isTrue();
        });
        await().pollDelay(Duration.ofSeconds(1))
                .atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(kinds(key)).containsExactlyInAnyOrder("ENCUMBER", "SETTLE", "SALE"));
        assertThat(jdbc.sql("SELECT count(*) FROM posting p JOIN journal_entry e ON e.id = p.entry_id"
                                + " WHERE e.payment_key = :key")
                        .param("key", key)
                        .query(Long.class)
                        .single())
                .isEqualTo(6L);
        PaymentProjection projection = payments.findByKey(key).orElseThrow();
        assertThat(projection.buyerState().name()).isEqualTo("SETTLED");
        assertThat(projection.sellerState().name()).isEqualTo("SETTLED");
        assertThat(projection.paymentIntentId()).isEqualTo(payment.intentId());

        List<ConsumerRecord<String, String>> posted =
                drain(LedgerTopics.ENTRY_POSTED, projection.id().toString(), 3);
        assertThat(posted).hasSize(3);
        for (ConsumerRecord<String, String> record : posted) {
            assertThat(record.headers().lastHeader("__TypeId__"))
                    .as("no Kafka type headers")
                    .isNull();
            assertThat(record.value())
                    .doesNotContain(payment.authorization().nonce().substring(2).toLowerCase(Locale.ROOT));
            EntryPosted event = json.readValue(record.value(), EntryPosted.class);
            assertThat(event.paymentId()).isEqualTo(projection.id());
            assertThat(event.meta().producer()).isEqualTo("ledger");
            assertThat(event.meta().eventId()).isEqualTo(event.entryId().toString());
        }
        assertThat(posted.stream()
                        .map(r -> json.readValue(r.value(), EntryPosted.class).kind()))
                .containsExactlyInAnyOrder("ENCUMBER", "SETTLE", "SALE");
    }

    @Test
    void goldenFixtureIsBooked() throws Exception {
        String nonce = "0x" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        String fixture = fixture("payments.settled.v1.json")
                .replace("0x5f1c8a2b9d3e4f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8", nonce)
                .replace(
                        "0b5c6a8e-3c1d-4b1e-9f0a-2f6d7c1e9a01",
                        UUID.randomUUID().toString());
        String key = ("eip155:84532:0x036CbD53842c5426634e7929541eC2318f3dCF7e:"
                        + "0xdD542d774e0c0E546d76721396C69e113A644795:" + nonce)
                .toLowerCase(Locale.ROOT);

        send(PaymentTopics.SETTLED, key, fixture);

        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(kinds(key)).containsExactlyInAnyOrder("ENCUMBER", "SETTLE"));
    }

    @Test
    void malformedRecordGoesToTheDeadLetterTopic() throws Exception {
        String poison = "{\"not\": \"an event\", \"marker\": \"" + UUID.randomUUID() + "\"";

        send(PaymentTopics.AUTHORIZED, "poison", poison);

        assertThat(drainValues(PaymentTopics.AUTHORIZED + LedgerMessagingConfiguration.DLT_SUFFIX, poison))
                .containsExactly(poison);
    }

    @Test
    void unknownFieldIsRejectedToTheDeadLetterTopicAndBooksNothing() throws Exception {
        TestPayment payment = TestPayment.random(new SplittableRandom(), 20_000);
        String valid = json.writeValueAsString(payment.authorized());
        String withExtra = valid.substring(0, valid.length() - 1) + ",\"surprise\":1}";

        send(PaymentTopics.AUTHORIZED, payment.key(), withExtra);

        assertThat(drainValues(PaymentTopics.AUTHORIZED + LedgerMessagingConfiguration.DLT_SUFFIX, withExtra))
                .containsExactly(withExtra);
        assertThat(payments.findByKey(payment.key())).isEmpty();
    }

    private void send(String topic, String key, String value) throws Exception {
        kafka.send(topic, key, value).get();
    }

    private boolean inboxed(String eventId) {
        return jdbc.sql("SELECT count(*) FROM inbox WHERE event_id = :id")
                        .param("id", eventId)
                        .query(Long.class)
                        .single()
                == 1L;
    }

    private List<String> kinds(String key) {
        return jdbc.sql("SELECT kind FROM journal_entry WHERE payment_key = :key")
                .param("key", key)
                .query(String.class)
                .list();
    }

    /** Reads {@code topic} from the beginning until {@code expected} records with {@code key} arrived. */
    private List<ConsumerRecord<String, String>> drain(String topic, String key, int expected) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (Consumer<String, String> consumer = consumers.createConsumer("test-" + UUID.randomUUID(), "test")) {
            consumer.subscribe(List.of(topic));
            await().atMost(TIMEOUT).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (key.equals(r.key())) {
                        found.add(r);
                    }
                });
                return found.size() >= expected;
            });
        }
        return found;
    }

    private List<String> drainValues(String topic, String value) {
        List<String> found = new ArrayList<>();
        try (Consumer<String, String> consumer = consumers.createConsumer("test-" + UUID.randomUUID(), "test")) {
            consumer.subscribe(List.of(topic));
            await().atMost(TIMEOUT).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (value.equals(r.value())) {
                        found.add(r.value());
                    }
                });
                return !found.isEmpty();
            });
        }
        return found;
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = PaymentEventsKafkaTests.class.getResourceAsStream("/fixtures/events/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }
}
