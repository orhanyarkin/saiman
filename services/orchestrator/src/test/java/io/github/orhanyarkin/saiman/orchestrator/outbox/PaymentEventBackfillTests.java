package io.github.orhanyarkin.saiman.orchestrator.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.orchestrator.outbox.OutboxTestAccess.Publication;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentHandle;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.SpendTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M3 history (intents signed before the outbox existed) is published exactly once at startup, with the same
 * deterministic event ids a live publication would have used.
 */
class PaymentEventBackfillTests extends SpendTestSupport {

    @Autowired
    private PaymentEventBackfill backfill;

    @Autowired
    private MeterRegistry meters;

    @Test
    void historyIsPublishedExactlyOnce() {
        UUID run = createRun(50_000);
        PaymentIntentHandle settled = newIntent(run);
        assertThat(client.send(settled, null).paid()).isTrue();
        PaymentIntentHandle held = newIntent(run);
        assertThat(client.send(held, null).paid()).isTrue();
        jdbc.sql("UPDATE payment_intent SET status = 'HELD', tx_hash = NULL, resolved_by = NULL WHERE id = :id")
                .param("id", held.id())
                .update();
        PaymentIntentHandle unsigned = newIntent(run); // PENDING: nothing to publish
        // As before V6: no publications and no log rows.
        jdbc.sql("TRUNCATE event_publication, payment_event_log").update();
        assertThat(unsigned.id()).isNotNull();

        assertThat(backfill.backfill()).isEqualTo(3); // 2 x authorized + 1 x settled
        assertThat(backfill.backfill()).isZero();

        assertThat(OutboxTestAccess.publications(jdbc, "PaymentAuthorized"))
                .extracting(p -> p.event().at("/meta/eventId").asString())
                .containsExactlyInAnyOrder(
                        PaymentEventPublisher.eventId(settled.id(), PaymentEventPublisher.Kind.AUTHORIZED)
                                .toString(),
                        PaymentEventPublisher.eventId(held.id(), PaymentEventPublisher.Kind.AUTHORIZED)
                                .toString());
        assertThat(OutboxTestAccess.publications(jdbc, "PaymentSettled"))
                .singleElement()
                .satisfies((Publication p) -> {
                    assertThat(p.event().at("/meta/eventId").asString())
                            .isEqualTo(PaymentEventPublisher.eventId(settled.id(), PaymentEventPublisher.Kind.SETTLED)
                                    .toString());
                    assertThat(p.event().at("/evidence").asString()).isEqualTo("FACILITATOR");
                });
    }

    @Test
    void aBadHistoricalRowIsSkippedAndCountedWhileTheOthersArePublished() {
        UUID run = createRun(50_000);
        PaymentIntentHandle good = newIntent(run);
        assertThat(client.send(good, null).paid()).isTrue();
        PaymentIntentHandle bad = newIntent(run);
        assertThat(client.send(bad, null).paid()).isTrue();
        jdbc.sql("UPDATE payment_intent SET auth_nonce = 'not-a-nonce' WHERE id = :id")
                .param("id", bad.id())
                .update();
        jdbc.sql("TRUNCATE event_publication, payment_event_log").update();
        double skippedBefore = skipped();

        assertThat(backfill.backfill()).isEqualTo(2); // the good intent's authorized + settled

        assertThat(skipped()).isEqualTo(skippedBefore + 2); // the bad intent's authorized + settled
        assertThat(OutboxTestAccess.publications(jdbc, "PaymentAuthorized"))
                .extracting(p -> p.event().at("/meta/eventId").asString())
                .containsExactly(PaymentEventPublisher.eventId(good.id(), PaymentEventPublisher.Kind.AUTHORIZED)
                        .toString());
    }

    private double skipped() {
        return meters.find(PaymentEventBackfill.SKIPPED_METRIC).counters().stream()
                .mapToDouble(c -> c.count())
                .sum();
    }

    @Test
    void liveEventsAreNotBackfilledAgain() {
        UUID run = createRun(50_000);
        assertThat(client.send(newIntent(run), null).paid()).isTrue();
        int before = OutboxTestAccess.publications(jdbc).size();

        assertThat(backfill.backfill()).isZero();
        assertThat(OutboxTestAccess.publications(jdbc)).hasSize(before);
    }
}
