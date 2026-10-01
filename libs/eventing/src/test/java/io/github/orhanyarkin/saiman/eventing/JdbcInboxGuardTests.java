package io.github.orhanyarkin.saiman.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = TestApplication.class)
@Import(TestcontainersConfiguration.class)
class JdbcInboxGuardTests {

    @Autowired
    InboxGuard guard;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    Environment environment;

    private boolean first(String id, String consumer, String topic) {
        return Boolean.TRUE.equals(tx.execute(s -> guard.firstDelivery(id, consumer, topic)));
    }

    @BeforeEach
    void table() {
        jdbc.sql("""
                CREATE TABLE IF NOT EXISTS inbox (
                    event_id text NOT NULL, consumer text NOT NULL, topic text NOT NULL,
                    received_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (event_id, consumer))
                """).update();
        jdbc.sql("DELETE FROM inbox").update();
    }

    @Test
    void firstDeliveryIsTrueAndRedeliveryIsFalse() {
        assertThat(guard).isInstanceOf(JdbcInboxGuard.class);
        assertThat(first("e-1", "ledger", "payments.settled.v1")).isTrue();
        assertThat(first("e-1", "ledger", "payments.settled.v1")).isFalse();
        // Another consumer of the same event is independent.
        assertThat(first("e-1", "audit", "payments.settled.v1")).isTrue();
    }

    @Test
    void rolledBackTransactionLeavesNoInboxRow() {
        tx.execute(s -> {
            guard.firstDelivery("e-2", "ledger", "t");
            s.setRollbackOnly();
            return null;
        });
        assertThat(first("e-2", "ledger", "t")).isTrue();
    }

    @Test
    void concurrentDeliveriesOfOneEventYieldExactlyOneFirst() throws Exception {
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return first("e-3", "ledger", "t");
                }));
            }
            start.countDown();
            int firsts = 0;
            for (Future<Boolean> f : results) {
                if (f.get()) {
                    firsts++;
                }
            }
            assertThat(firsts).isEqualTo(1);
        }
    }

    @Test
    void refusesToRunWithoutATransaction() {
        assertThatThrownBy(() -> guard.firstDelivery("e-4", "ledger", "t"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM inbox").query(Long.class).single())
                .isZero();
    }

    @Test
    void modulithDefaultsAreApplied() {
        assertThat(environment.getProperty("spring.modulith.events.registry-trigger-annotation"))
                .isEqualTo("org.springframework.modulith.events.ApplicationModuleListener");
        assertThat(environment.getProperty("spring.modulith.events.republish-outstanding-events-on-restart"))
                .isEqualTo("true");
        assertThat(environment.getProperty("spring.modulith.events.completion-mode"))
                .isEqualTo("DELETE");
    }
}
