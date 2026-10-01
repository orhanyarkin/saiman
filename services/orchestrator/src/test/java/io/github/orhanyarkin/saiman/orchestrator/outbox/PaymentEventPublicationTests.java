package io.github.orhanyarkin.saiman.orchestrator.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.orchestrator.approval.ApprovalDecision;
import io.github.orhanyarkin.saiman.orchestrator.approval.ApprovalService;
import io.github.orhanyarkin.saiman.orchestrator.events.RunEventAppender;
import io.github.orhanyarkin.saiman.orchestrator.events.RunEventCodec;
import io.github.orhanyarkin.saiman.orchestrator.events.RunStepExternalized;
import io.github.orhanyarkin.saiman.orchestrator.outbox.OutboxTestAccess.Publication;
import io.github.orhanyarkin.saiman.orchestrator.payment.IntentAuthorization;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentApprovalRequiredException;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentHandle;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentStatus;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.FakeSeller;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.SpendTestSupport;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.payments.PaymentAuthorized;
import io.github.orhanyarkin.saiman.shared.payments.PaymentSettled;
import io.github.orhanyarkin.saiman.shared.payments.PaymentTopics;
import io.github.orhanyarkin.saiman.shared.run.AgentStep;
import io.github.orhanyarkin.saiman.shared.run.RunEvent;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import io.github.orhanyarkin.saiman.shared.run.RunEventType;
import io.github.orhanyarkin.x402.client.PaymentIntent;
import io.github.orhanyarkin.x402.client.SpendGuard;
import io.github.orhanyarkin.x402.client.SpendReservation;
import io.github.orhanyarkin.x402.core.Eip3009Authorization;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Payment events and run steps go through the outbox in the transaction that changes state (ADR-0016). No
 * broker in these tests: every publication stays in {@code event_publication}, where the tests read it.
 */
class PaymentEventPublicationTests extends SpendTestSupport {

    private static final String NONCE = "0x" + "ab".repeat(32);
    private static final Set<String> EXTERNALIZED_TYPES =
            Set.of("PaymentAuthorized", "PaymentSettled", "PaymentFailed", "RunStepExternalized");

    @Autowired
    private PaymentEventPublisher events;

    @Autowired
    private SpendGuard guard;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private RunEventAppender runEvents;

    @Autowired
    private RunEventCodec codec;

    @Autowired
    private EventExternalizationConfiguration externalization;

    @Autowired
    private JsonMapper json;

    @Autowired
    private RecordMessageConverter converter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void aSettledCallPublishesAuthorizedAndSettledOnceEachWithStableIds() {
        UUID run = createRun(50_000);
        PaymentIntentHandle handle = newIntent(run);

        assertThat(client.send(handle, null).paid()).isTrue();

        List<Publication> authorized = OutboxTestAccess.publications(jdbc, "PaymentAuthorized");
        List<Publication> settled = OutboxTestAccess.publications(jdbc, "PaymentSettled");
        assertThat(authorized).hasSize(1);
        assertThat(settled).hasSize(1);
        Map<String, Object> row = jdbc.sql("SELECT payer, auth_nonce, tx_hash, resolved_by, valid_before"
                        + " FROM payment_intent WHERE id = :id")
                .param("id", handle.id())
                .query()
                .singleRow();
        assertThat(row.get("resolved_by")).isEqualTo("FACILITATOR");

        JsonNode event = settled.get(0).event();
        assertThat(event.at("/meta/eventId").asString())
                .isEqualTo(PaymentEventPublisher.eventId(handle.id(), PaymentEventPublisher.Kind.SETTLED)
                        .toString());
        assertThat(event.at("/meta/producer").asString()).isEqualTo("orchestrator");
        assertThat(event.at("/meta/correlationId").asString()).isEqualTo(run.toString());
        assertThat(event.at("/book").asString()).isEqualTo("BUYER");
        assertThat(event.at("/evidence").asString()).isEqualTo("FACILITATOR");
        assertThat(event.at("/txHash").asString()).isEqualTo(row.get("tx_hash"));
        assertThat(event.at("/authorization/payer").asString()).isEqualTo(row.get("payer"));
        assertThat(event.at("/authorization/nonce").asString()).isEqualTo(row.get("auth_nonce"));
        assertThat(event.at("/authorization/validBefore").asLong()).isEqualTo(row.get("valid_before"));
        assertThat(event.at("/amount/atomicUnits").asLong()).isEqualTo(10_000);
        assertThat(authorized.get(0).event().at("/meta/eventId").asString())
                .isEqualTo(PaymentEventPublisher.eventId(handle.id(), PaymentEventPublisher.Kind.AUTHORIZED)
                        .toString());
        assertThat(OutboxTestAccess.eventLog(jdbc))
                .extracting(r -> r.get("kind"))
                .containsExactly("AUTHORIZED", "SETTLED");
    }

    @Test
    void theKafkaValueIsTheSharedRecordAsJsonKeyedByThePaymentKey() {
        UUID run = createRun(50_000);
        PaymentIntentHandle handle = newIntent(run);
        client.send(handle, null);
        JsonNode stored =
                OutboxTestAccess.publications(jdbc, "PaymentSettled").get(0).event();
        PaymentSettled event = json.treeToValue(stored, PaymentSettled.class);

        RoutingTarget target = externalization.determineTarget(event);
        String value = kafkaValue(externalization.map(event));

        assertThat(target.getTarget()).isEqualTo(PaymentTopics.SETTLED);
        assertThat(target.getKey()).isEqualTo(event.authorization().paymentKey());
        JsonNode wire = json.readTree(value);
        assertThat(wire.propertyNames())
                .containsExactlyInAnyOrder(
                        "meta",
                        "authorization",
                        "amount",
                        "payTo",
                        "resource",
                        "book",
                        "txHash",
                        "evidence",
                        "paymentIntentId",
                        "runId");
        assertThat(wire.get("amount").propertyNames()).containsExactlyInAnyOrder("atomicUnits", "asset", "decimals");
        assertThat(wire.get("amount").get("atomicUnits").isIntegralNumber()).isTrue();
        assertThat(wire.propertyNames())
                .containsExactlyInAnyOrderElementsOf(
                        fixture("payments.settled.v1").propertyNames());
        assertThat(value).doesNotContainIgnoringCase("signature").doesNotContain("idempotency");
        assertThat(json.readValue(value, PaymentSettled.class)).isEqualTo(event);
        assertThat(event.amount()).isEqualTo(Money.usdc(10_000));
    }

    @Test
    void aRunStepIsExternalizedAsItsSseEnvelopeKeyedByRunId() {
        UUID run = createRun(50_000);
        RunEvent appended =
                runEvents.append(run, RunEventType.STEP_STARTED, new RunEventData.StepChanged(AgentStep.PLANNER));

        List<Publication> steps = OutboxTestAccess.publications(jdbc, "RunStepExternalized");
        assertThat(steps).hasSize(1);
        RunStepExternalized step = json.treeToValue(steps.get(0).event(), RunStepExternalized.class);
        assertThat(externalization.determineTarget(step).getTarget()).isEqualTo(OutboxConfiguration.RUN_STEPS);
        assertThat(externalization.determineTarget(step).getKey()).isEqualTo(run.toString());
        assertThat(json.readTree(kafkaValue(externalization.map(step))))
                .isEqualTo(json.readTree(codec.encodeEnvelope(appended)));
    }

    @Test
    void aPaymentAuthorizedValueMatchesTheGoldenFixtureShapeWithoutATypeHeader() {
        UUID run = createRun(50_000);
        client.send(newIntent(run), null);
        PaymentAuthorized event = json.treeToValue(
                OutboxTestAccess.publications(jdbc, "PaymentAuthorized").get(0).event(), PaymentAuthorized.class);

        ProducerRecord<?, ?> record = producerRecord(externalization.map(event));

        assertThat(record.value()).isInstanceOf(String.class);
        assertThat(record.headers().lastHeader("__TypeId__")).isNull();
        JsonNode wire = json.readTree((String) record.value());
        JsonNode golden = fixture("payments.authorized.v1");
        assertThat(wire.propertyNames()).containsExactlyInAnyOrderElementsOf(golden.propertyNames());
        for (String nested : List.of("meta", "authorization", "amount")) {
            assertThat(wire.get(nested).propertyNames())
                    .as(nested)
                    .containsExactlyInAnyOrderElementsOf(golden.get(nested).propertyNames());
        }
    }

    private String kafkaValue(Object payload) {
        Object value = producerRecord(payload).value();
        assertThat(value).isInstanceOf(String.class);
        return (String) value;
    }

    /** What KafkaTemplate.send(Message) hands to the producer, built by the application's converter. */
    private ProducerRecord<?, ?> producerRecord(Object payload) {
        return converter.fromMessage(
                MessageBuilder.withPayload(payload)
                        .setHeader(KafkaHeaders.TOPIC, "t")
                        .build(),
                "t");
    }

    private JsonNode fixture(String topic) {
        try {
            return json.readTree(Files.readString(
                    Path.of("../../libs/shared/src/test/resources/fixtures/events/" + topic + ".json")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void onlyPaymentEventsAndRunStepsArePersistedNotTheInProcessListeners() {
        UUID run = createRun(50_000);
        seller.price(18_000); // above the 15000 threshold: an approval, decided below
        PaymentIntentHandle handle = newIntent(run);
        UUID approvalId;
        try {
            client.send(handle, null);
            throw new AssertionError("expected an approval request");
        } catch (PaymentApprovalRequiredException e) {
            approvalId = e.approvalId();
        }
        approvals.decide(run, approvalId, ApprovalDecision.APPROVE); // ApprovalDecidedEvent -> ApprovalWaiter
        runEvents.append(run, RunEventType.STEP_STARTED, new RunEventData.StepChanged(AgentStep.RESEARCHER)); // -> bus
        assertThat(client.send(handle, null).paid()).isTrue();

        assertThat(OutboxTestAccess.publications(jdbc))
                .extracting(Publication::type)
                .isNotEmpty()
                .allMatch(EXTERNALIZED_TYPES::contains)
                .contains("PaymentAuthorized", "PaymentSettled", "RunStepExternalized");
        assertThat(jdbc.sql("SELECT count(DISTINCT listener_id) FROM event_publication")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1); // the Kafka externalizer, nothing else
    }

    @Test
    void aRolledBackTransactionPublishesNothing() {
        UUID run = createRun(50_000);
        PaymentIntentHandle handle = newIntent(run);
        IntentAuthorization authorization = authorization(handle, run);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(events.authorized(authorization)).isTrue();
            status.setRollbackOnly();
        });

        assertThat(OutboxTestAccess.publications(jdbc)).isEmpty();
        assertThat(OutboxTestAccess.eventLog(jdbc)).isEmpty();
    }

    @Test
    void anIntentKindIsPublishedOnlyOnce() {
        UUID run = createRun(50_000);
        PaymentIntentHandle handle = newIntent(run);
        IntentAuthorization authorization = authorization(handle, run);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(
                status -> assertThat(events.authorized(authorization)).isTrue());
        tx.executeWithoutResult(
                status -> assertThat(events.authorized(authorization)).isFalse());

        assertThat(OutboxTestAccess.publications(jdbc, "PaymentAuthorized")).hasSize(1);
    }

    @Test
    void publishingOutsideATransactionIsRefused() {
        UUID run = createRun(50_000);
        IntentAuthorization authorization = authorization(newIntent(run), run);

        assertThatThrownBy(() -> events.authorized(authorization))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(OutboxTestAccess.publications(jdbc)).isEmpty();
    }

    @Test
    void whenThePublicationFailsTheIntentDoesNotBecomeSigned() {
        UUID run = createRun(50_000);
        PaymentIntentHandle handle = newIntent(run);
        // A reserved intent whose recorded network is not Base Sepolia: the event record refuses it.
        jdbc.sql("""
                        UPDATE payment_intent SET status = 'RESERVED', amount_atomic = 10000, pay_to = :payTo,
                               network = 'eip155:1', asset = :asset, reserved_day = current_date
                         WHERE id = :id
                        """)
                .param("id", handle.id())
                .param("payTo", FakeSeller.PAY_TO.toLowerCase(java.util.Locale.ROOT))
                .param("asset", TestnetAssets.USDC_ADDRESS)
                .update();
        String key = jdbc.sql("SELECT idempotency_key FROM payment_intent WHERE id = :id")
                .param("id", handle.id())
                .query(String.class)
                .single();
        SpendReservation reservation = new SpendReservation(
                key,
                new PaymentIntent(
                        key,
                        URI.create("http://seller.invalid/x"),
                        new PaymentRequirements(
                                "exact",
                                "eip155:1",
                                "10000",
                                TestnetAssets.USDC_ADDRESS,
                                FakeSeller.PAY_TO,
                                60,
                                null)));
        Eip3009Authorization signed =
                new Eip3009Authorization("0x" + "12".repeat(20), FakeSeller.PAY_TO, "10000", "0", "1999999999", NONCE);

        assertThatThrownBy(() -> guard.signed(reservation, signed)).isInstanceOf(IllegalArgumentException.class);

        assertThat(intents.find(handle.id()).orElseThrow().status()).isEqualTo(PaymentIntentStatus.RESERVED);
        assertThat(jdbc.sql("SELECT auth_nonce FROM payment_intent WHERE id = :id")
                        .param("id", handle.id())
                        .query(String.class)
                        .optional())
                .isEmpty();
        assertThat(OutboxTestAccess.publications(jdbc)).isEmpty();
    }

    /** A synthetic signed authorization for an existing intent (for the publisher alone). */
    private static IntentAuthorization authorization(PaymentIntentHandle handle, UUID run) {
        return new IntentAuthorization(
                handle.id(),
                run,
                PaymentIntentStatus.SIGNED,
                handle.resource().toString(),
                TestnetAssets.NETWORK,
                TestnetAssets.USDC_ADDRESS,
                FakeSeller.PAY_TO,
                10_000,
                "0x" + "12".repeat(20),
                NONCE,
                1_999_999_999L,
                java.time.LocalDate.now(java.time.ZoneOffset.UTC),
                null);
    }
}
