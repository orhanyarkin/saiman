package io.github.orhanyarkin.saiman.orchestrator.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentDeniedException;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentHandle;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentStatus;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentView;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.SpendTestSupport;
import io.github.orhanyarkin.saiman.shared.run.DenyReason;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The wallet-wide hourly paid-call limit (default 25, below the seller's 30 per payer): counted
 * across all runs, enforced by the spend guard before anything is signed.
 */
class HourlyPaidCallLimitTests extends SpendTestSupport {

    @Test
    void theTwentySixthPaidCallInAnHourIsDeniedBeforeSigning() {
        // 24 earlier paid calls this hour, spread over two other runs (the per-run limit here is 20)
        // and over every status that may move money.
        UUID earlierA = createRun(200_000);
        UUID earlierB = createRun(200_000);
        seed(earlierA, "SETTLED", 18, "10 minutes");
        seed(earlierA, "HELD", 1, "50 minutes");
        seed(earlierB, "SIGNED", 1, "5 minutes");
        seed(earlierB, "RESERVED", 1, "1 minute");
        seed(earlierB, "SETTLED", 3, "59 minutes");
        // Not paid calls: never counted.
        seed(earlierB, "DENIED", 5, "1 minute");
        seed(earlierB, "RELEASED", 5, "1 minute");
        UUID run = createRun(50_000);

        assertThat(client.send(newIntent(run), null).paid()).isTrue(); // the 25th
        PaymentIntentHandle twentySixth = newIntent(run);

        assertThatThrownBy(() -> client.send(twentySixth, null))
                .isInstanceOfSatisfying(
                        PaymentDeniedException.class,
                        e -> assertThat(e.reason()).isEqualTo(DenyReason.HOURLY_PAID_CALLS));

        assertThat(signer.calls()).isEqualTo(1);
        assertThat(seller.paidRequests()).isEqualTo(1);
        assertThat(intents.find(twentySixth.id()).orElseThrow())
                .extracting(PaymentIntentView::status, PaymentIntentView::denyReason)
                .containsExactly(PaymentIntentStatus.DENIED, DenyReason.HOURLY_PAID_CALLS);
        assertThat(run(run)).isEqualTo(new RunCounters(50_000, 0, 10_000));
    }

    @Test
    void paidCallsOlderThanAnHourDoNotCount() {
        UUID earlier = createRun(200_000);
        seed(earlier, "SETTLED", 20, "61 minutes");
        seed(earlier, "HELD", 5, "2 hours");
        seed(earlier, "SETTLED", 24, "1 minute");
        UUID run = createRun(50_000);

        assertThat(client.send(newIntent(run), null).paid()).isTrue();

        assertThat(signer.calls()).isEqualTo(1);
        assertThat(intentsWithStatus(run, "SETTLED")).isEqualTo(1);
    }

    /** {@code count} intents of {@code runId} in {@code status}, created {@code age} ago. */
    private void seed(UUID runId, String status, int count, String age) {
        for (int i = 0; i < count; i++) {
            jdbc.sql("""
                            INSERT INTO payment_intent
                                (id, run_id, idempotency_key, tool, args_hash, resource, status, created_at)
                            VALUES (:id, :run, :key, 'disclosureSummary', 'seeded', 'http://seller/x', :status,
                                    now() - CAST(:age AS interval))
                            """)
                    .param("id", UUID.randomUUID())
                    .param("run", runId)
                    .param("key", UUID.randomUUID().toString())
                    .param("status", status)
                    .param("age", age)
                    .update();
        }
    }
}
