package io.github.orhanyarkin.saiman.orchestrator.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentDeniedException;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentHandle;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.SpendTestSupport;
import io.github.orhanyarkin.saiman.shared.run.DenyReason;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** The global daily cap (20000 here) binds across runs, each with a budget far above it. */
@TestPropertySource(properties = "saiman.orchestrator.spend.daily-cap-atomic=20000")
class DailyCapTests extends SpendTestSupport {

    @Test
    void theDailyCapPaysTwiceThenDeniesTheThirdCallBeforeSigning() {
        client.send(newIntent(createRun(50_000)), null);
        client.send(newIntent(createRun(50_000)), null);
        PaymentIntentHandle third = newIntent(createRun(50_000));

        assertThatThrownBy(() -> client.send(third, null))
                .isInstanceOfSatisfying(
                        PaymentDeniedException.class,
                        e -> assertThat(e.reason()).isEqualTo(DenyReason.DAILY_CAP));

        assertThat(signer.calls()).isEqualTo(2);
        assertThat(seller.paidRequests()).isEqualTo(2);
        assertThat(today()).isEqualTo(new RunCounters(0, 0, 20_000));
        assertThat(run(third.runId())).isEqualTo(new RunCounters(50_000, 0, 0));
    }

    @Test
    void sixteenConcurrentRunsCannotOverspendTheDay() throws Exception {
        List<PaymentIntentHandle> handles = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            handles.add(newIntent(createRun(50_000)));
        }
        CountDownLatch start = new CountDownLatch(1);
        Map<String, Long> outcomes = new HashMap<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            List<Future<String>> results = new ArrayList<>();
            for (PaymentIntentHandle handle : handles) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        client.send(handle, null);
                        return "SETTLED";
                    } catch (PaymentDeniedException e) {
                        return e.reason().name();
                    }
                }));
            }
            start.countDown();
            for (Future<String> result : results) {
                outcomes.merge(result.get(), 1L, Long::sum);
            }
        }

        assertThat(outcomes).containsExactlyInAnyOrderEntriesOf(Map.of("SETTLED", 2L, "DAILY_CAP", 14L));
        assertThat(signer.calls()).isEqualTo(2);
        assertThat(today()).isEqualTo(new RunCounters(0, 0, 20_000));
        assertThat(handles.stream()
                        .map(PaymentIntentHandle::runId)
                        .map(this::run)
                        .mapToLong(RunCounters::committed))
                .containsOnly(0L, 10_000L);
        UUID any = handles.getFirst().runId();
        assertThat(run(any).budget()).isEqualTo(50_000);
    }
}
