package io.github.orhanyarkin.saiman.ledger.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.orhanyarkin.saiman.ledger.LedgerIntegrationTest;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentProjection;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentRepository;
import io.github.orhanyarkin.saiman.ledger.payment.TestPayment;
import io.github.orhanyarkin.saiman.ledger.pbt.Pbt;
import io.github.orhanyarkin.saiman.shared.ledger.EntryPosted;
import io.github.orhanyarkin.saiman.shared.ledger.LedgerTopics;
import io.github.orhanyarkin.saiman.shared.payments.PaymentAuthorized;
import io.github.orhanyarkin.saiman.shared.payments.PaymentSettled;
import io.github.orhanyarkin.saiman.shared.payments.PaymentTopics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * End to end through a real Redpanda: raw JSON on the {@code payments.*} topics (as the producers' Modulith
 * externalization writes it, no type headers), the ledger books each entry once when a fact arrives late and however
 * often a record is redelivered, publishes {@code ledger.entry-posted.v1} through the outbox, and dead-letters
 * poison records instead of retrying them forever.
 */
@LedgerIntegrationTest
class PaymentEventsKafkaTests {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    /**
     * Payments are generated from the run's base seed ({@code -Dsaiman.pbt.seed}), split per payment so tests
     * sharing the database never generate the same payment.
     */
    private static final SplittableRandom SEEDS = new SplittableRandom(Pbt.seedOf(1_000_000));

    private static synchronized SplittableRandom random() {
        return SEEDS.split();
    }

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

    @Autowired
    private MeterRegistry meters;

    @Test
    void reorderedAndDuplicatedDeliveryPostsEachEntryOnceAndPublishesEntryPosted() throws Exception {
        TestPayment payment = TestPayment.random(random(), 20_000);
        PaymentSettled buyerSettled = payment.buyerSettled();
        PaymentSettled sellerSettled = payment.sellerSettled();
        PaymentAuthorized authorized = payment.authorized();
        String key = payment.key();

        // Deterministically out of order: both settled reports (the buyer's twice) are booked before the
        // authorization is even sent. Records of one partition are processed in order, so once the last record on
        // a topic is in the inbox, the duplicates before it have been consumed too.
        send(PaymentTopics.SETTLED, key, json.writeValueAsString(buyerSettled));
        send(PaymentTopics.SETTLED, key, json.writeValueAsString(buyerSettled));
        send(PaymentTopics.SETTLED, key, json.writeValueAsString(sellerSettled));
        await().atMost(TIMEOUT)
                .untilAsserted(() ->
                        assertThat(inboxed(sellerSettled.meta().eventId())).isTrue());
        assertThat(kinds(key)).containsExactlyInAnyOrder("ENCUMBER", "SETTLE", "SALE");

        // The late authorization twice, then a sentinel record behind it on the same topic.
        PaymentAuthorized sentinel = TestPayment.random(random(), 20_000).authorized();
        send(PaymentTopics.AUTHORIZED, key, json.writeValueAsString(authorized));
        send(PaymentTopics.AUTHORIZED, key, json.writeValueAsString(authorized));
        send(PaymentTopics.AUTHORIZED, "sentinel", json.writeValueAsString(sentinel));
        await().atMost(TIMEOUT)
                .untilAsserted(
                        () -> assertThat(inboxed(sentinel.meta().eventId())).isTrue());

        assertThat(inboxed(buyerSettled.meta().eventId())).isTrue();
        assertThat(inboxed(authorized.meta().eventId())).isTrue();
        assertThat(kinds(key)).containsExactlyInAnyOrder("ENCUMBER", "SETTLE", "SALE");
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
        TestPayment payment = TestPayment.random(random(), 20_000);
        String valid = json.writeValueAsString(payment.authorized());
        String withExtra = valid.substring(0, valid.length() - 1) + ",\"surprise\":1}";

        send(PaymentTopics.AUTHORIZED, payment.key(), withExtra);

        assertThat(drainValues(PaymentTopics.AUTHORIZED + LedgerMessagingConfiguration.DLT_SUFFIX, withExtra))
                .containsExactly(withExtra);
        assertThat(payments.findByKey(payment.key())).isEmpty();
    }

    @Test
    void forgedNonUsdcTwinOfABookedPaymentIsDeadLetteredAndBooksNothing() throws Exception {
        TestPayment payment = TestPayment.random(random(), 20_000);
        String real = json.writeValueAsString(payment.buyerSettled());
        send(PaymentTopics.SETTLED, payment.key(), real);
        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(kinds(payment.key())).containsExactlyInAnyOrder("ENCUMBER", "SETTLE"));

        // Same payer and nonce, another token, a thousand times the amount, a fresh event id.
        String twin = json.writeValueAsString(payment.buyerSettled())
                .replace(TestPayment.USDC_ADDRESS, "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238")
                .replace("\"atomicUnits\":20000", "\"atomicUnits\":20000000");
        send(PaymentTopics.SETTLED, "twin", twin);

        assertThat(drainValues(PaymentTopics.SETTLED + LedgerMessagingConfiguration.DLT_SUFFIX, twin))
                .containsExactly(twin);
        String payer = payment.authorization().payer().toLowerCase(Locale.ROOT);
        String nonce = payment.authorization().nonce().toLowerCase(Locale.ROOT);
        assertThat(jdbc.sql("SELECT count(*) FROM payment WHERE payer = :payer AND nonce = :nonce")
                        .param("payer", payer)
                        .param("nonce", nonce)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(kinds(payment.key())).containsExactlyInAnyOrder("ENCUMBER", "SETTLE");

        // Even written straight to the table, a twin of (payer, nonce) is refused by the schema.
        assertThatThrownBy(() -> jdbc.sql("""
                                INSERT INTO payment (id, payment_key, network, asset_address, payer, nonce, pay_to,
                                                     amount_atomic, asset, decimals, valid_before, buyer_state,
                                                     seller_state)
                                SELECT gen_random_uuid(), 'twin:' || payment_key, network, asset_address, upper(payer),
                                       nonce, pay_to, amount_atomic, asset, decimals, valid_before, 'NONE', 'NONE'
                                  FROM payment WHERE payment_key = :key
                                """).param("key", payment.key()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void conflictingFactIsDeadLetteredAndLeavesAConflictingFactMismatch() throws Exception {
        TestPayment payment = TestPayment.random(random(), 20_000);
        send(PaymentTopics.AUTHORIZED, payment.key(), json.writeValueAsString(payment.authorized()));
        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(kinds(payment.key())).containsExactly("ENCUMBER"));

        String conflicting = json.writeValueAsString(payment.buyerSettled())
                .replace("\"atomicUnits\":20000", "\"atomicUnits\":20001");
        send(PaymentTopics.SETTLED, payment.key(), conflicting);

        assertThat(drainValues(PaymentTopics.SETTLED + LedgerMessagingConfiguration.DLT_SUFFIX, conflicting))
                .containsExactly(conflicting);
        assertThat(jdbc.sql("SELECT kind FROM reconciliation_mismatch WHERE payment_id = :id AND run_id IS NULL")
                        .param("id", PaymentProjection.paymentId(payment.key()))
                        .query(String.class)
                        .list())
                .containsExactly("CONFLICTING_FACT");
        assertThat(kinds(payment.key())).containsExactly("ENCUMBER");
    }

    /**
     * A record whose event id carries a NUL (Postgres rejects it in a text column on every attempt) is malformed:
     * quarantined on its first delivery, so the valid record behind it on the same partition is booked at once.
     */
    @Test
    void nulInEventIdIsQuarantinedAndTheNextRecordIsBookedRightAway() throws Exception {
        TestPayment forged = TestPayment.random(random(), 20_000);
        PaymentAuthorized authorized = forged.authorized();
        String eventId = authorized.meta().eventId();
        String poison = json.writeValueAsString(authorized)
                .replace("\"eventId\":\"" + eventId + "\"", "\"eventId\":\"\\u0000" + eventId.substring(1) + "\"");
        assertThat(poison).contains("\"eventId\":\"\\u0000" + eventId.substring(1) + "\"");
        double quarantinedBefore = dltCount(PaymentTopics.AUTHORIZED, "published");
        TestPayment next = TestPayment.random(random(), 20_000);

        send(PaymentTopics.AUTHORIZED, forged.key(), poison);
        send(PaymentTopics.AUTHORIZED, next.key(), json.writeValueAsString(next.authorized()));

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(kinds(next.key())).containsExactly("ENCUMBER"));
        assertThat(drainValues(PaymentTopics.AUTHORIZED + LedgerMessagingConfiguration.DLT_SUFFIX, poison))
                .containsExactly(poison);
        assertThat(dltCount(PaymentTopics.AUTHORIZED, "published")).isEqualTo(quarantinedBefore + 1);
        assertThat(payments.findByKey(forged.key())).isEmpty();
    }

    /**
     * A constraint violation raised by the database fails the same way on every delivery: it is quarantined on the
     * first attempt (the trigger runs exactly once), not retried.
     */
    @Test
    void dataIntegrityViolationIsQuarantinedWithoutRetry() throws Exception {
        TestPayment payment = TestPayment.random(random(), 20_000);
        PaymentAuthorized authorized = payment.authorized();
        String value = json.writeValueAsString(authorized);
        String trigger = failingInboxTrigger(authorized.meta().eventId(), "23514", "simulated check violation");
        try {
            send(PaymentTopics.AUTHORIZED, payment.key(), value);

            assertThat(drainValues(PaymentTopics.AUTHORIZED + LedgerMessagingConfiguration.DLT_SUFFIX, value))
                    .containsExactly(value);
            assertThat(attempts(trigger)).isEqualTo(1L);
        } finally {
            dropFailingInboxTrigger(trigger);
        }
        assertThat(inboxed(authorized.meta().eventId())).isFalse();
        assertThat(kinds(payment.key())).isEmpty();
    }

    /**
     * A lost connection (SQLSTATE 08006, which Spring translates to DataAccessResourceFailureException, a
     * NonTransientDataAccessException subtype) is retried with back-off, never dead-lettered; once the database
     * recovers, the same record is booked.
     */
    @Test
    void connectionFailureIsRetriedNotDeadLetteredAndBooksAfterRecovery() throws Exception {
        TestPayment payment = TestPayment.random(random(), 20_000);
        PaymentAuthorized authorized = payment.authorized();
        String value = json.writeValueAsString(authorized);
        double dltBefore = quarantined(PaymentTopics.AUTHORIZED);
        String trigger = failingInboxTrigger(authorized.meta().eventId(), "08006", "simulated outage");
        try {
            send(PaymentTopics.AUTHORIZED, payment.key(), value);
            await().atMost(TIMEOUT)
                    .untilAsserted(() -> assertThat(attempts(trigger)).isGreaterThanOrEqualTo(3L));
            assertThat(kinds(payment.key())).isEmpty();
        } finally {
            dropFailingInboxTrigger(trigger);
        }

        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(kinds(payment.key())).containsExactly("ENCUMBER"));
        assertThat(quarantined(PaymentTopics.AUTHORIZED)).isEqualTo(dltBefore);
        assertThat(valuesOn(PaymentTopics.AUTHORIZED + LedgerMessagingConfiguration.DLT_SUFFIX, Duration.ofSeconds(3)))
                .doesNotContain(value);
    }

    /**
     * Installs a BEFORE INSERT trigger on the inbox that raises {@code sqlState} for {@code eventId}. Every attempt
     * first bumps a sequence, which a rollback does not undo, so the test can count deliveries. Returns the suffix
     * of the created objects.
     */
    private String failingInboxTrigger(String eventId, String sqlState, String message) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        jdbc.sql("CREATE SEQUENCE attempts_%s".formatted(suffix)).update();
        jdbc.sql("""
                        CREATE FUNCTION fail_%1$s() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN
                            IF NEW.event_id = '%2$s' THEN
                                PERFORM nextval('attempts_%1$s');
                                RAISE EXCEPTION '%4$s' USING ERRCODE = '%3$s';
                            END IF;
                            RETURN NEW;
                        END $$
                        """.formatted(suffix, eventId, sqlState, message)).update();
        jdbc.sql("CREATE TRIGGER fail_%1$s BEFORE INSERT ON inbox FOR EACH ROW EXECUTE FUNCTION fail_%1$s()"
                        .formatted(suffix))
                .update();
        return suffix;
    }

    private void dropFailingInboxTrigger(String suffix) {
        jdbc.sql("DROP TRIGGER fail_%1$s ON inbox".formatted(suffix)).update();
        jdbc.sql("DROP FUNCTION fail_%1$s()".formatted(suffix)).update();
        jdbc.sql("DROP SEQUENCE attempts_%s".formatted(suffix)).update();
    }

    /** Delivery attempts that reached the failing trigger (0 before the first one). */
    private long attempts(String suffix) {
        return jdbc.sql("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM attempts_%s".formatted(suffix))
                .query(Long.class)
                .single();
    }

    /** Records of {@code topic} the recoverer dead-lettered, deterministic or exhausted. */
    private double quarantined(String topic) {
        return dltCount(topic, "published") + dltCount(topic, "exhausted");
    }

    private double dltCount(String topic, String outcome) {
        Counter counter = meters.find(QuarantineRecoverer.METRIC)
                .tag("topic", topic)
                .tag("outcome", outcome)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    /**
     * A huge junk record with junk headers is quarantined with bounded headers (no source headers, no stack trace)
     * and the consumer moves on to the next record.
     */
    @Test
    void oversizedJunkRecordIsQuarantinedWithBoundedHeadersAndTheConsumerAdvances() throws Exception {
        String junk = "{\"junk\":\"" + UUID.randomUUID() + "x".repeat(600_000) + "\"}";
        var record = new ProducerRecord<String, String>(PaymentTopics.AUTHORIZED, "junk", junk);
        for (int i = 0; i < 64; i++) {
            record.headers().add("junk-" + i, new byte[4096]);
        }
        kafka.send(record).get();
        TestPayment next = TestPayment.random(random(), 20_000);
        send(PaymentTopics.AUTHORIZED, next.key(), json.writeValueAsString(next.authorized()));

        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(kinds(next.key())).containsExactly("ENCUMBER"));
        ConsumerRecord<String, String> quarantined =
                drainRecord(PaymentTopics.AUTHORIZED + LedgerMessagingConfiguration.DLT_SUFFIX, junk);
        List<String> headerNames = new ArrayList<>();
        quarantined.headers().forEach(h -> headerNames.add(h.key()));
        assertThat(headerNames)
                .isNotEmpty()
                .hasSizeLessThanOrEqualTo(16)
                // traceparent: added by the observed template when it sends the DLT record (ours, bounded).
                .allMatch(name -> name.startsWith("kafka_dlt-") || name.equals("traceparent"))
                .noneMatch(name -> name.startsWith("junk-"))
                .contains("kafka_dlt-exception-fqcn", "kafka_dlt-original-topic")
                .doesNotContain("kafka_dlt-exception-stacktrace", "kafka_dlt-exception-message");
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

    private ConsumerRecord<String, String> drainRecord(String topic, String value) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (Consumer<String, String> consumer = consumers.createConsumer("test-" + UUID.randomUUID(), "test")) {
            consumer.subscribe(List.of(topic));
            await().atMost(TIMEOUT).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (value.equals(r.value())) {
                        found.add(r);
                    }
                });
                return !found.isEmpty();
            });
        }
        return found.getFirst();
    }

    /** Every value on {@code topic} from the beginning, read for {@code window}. */
    private List<String> valuesOn(String topic, Duration window) {
        List<String> values = new ArrayList<>();
        try (Consumer<String, String> consumer = consumers.createConsumer("test-" + UUID.randomUUID(), "test")) {
            consumer.subscribe(List.of(topic));
            long until = System.nanoTime() + window.toNanos();
            while (System.nanoTime() < until) {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> values.add(r.value()));
            }
        }
        return values;
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
