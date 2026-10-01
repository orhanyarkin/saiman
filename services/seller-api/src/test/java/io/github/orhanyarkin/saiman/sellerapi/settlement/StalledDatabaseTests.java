package io.github.orhanyarkin.saiman.sellerapi.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.sellerapi.testsupport.SettlementTestBase;
import io.github.orhanyarkin.x402.core.X402Headers;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A database that accepts connections but stops answering (not refused: a stall) must not hold a paid response:
 * the recorder runs on the request thread and is bounded by the socket timeout, the statement timeout and the
 * transaction timeout. The container is really paused ({@code docker pause}), then resumed.
 */
class StalledDatabaseTests extends SettlementTestBase {

    /** Validation 1 s + insert 2 s + rollback 2 s of socket timeout, plus the settle call and margin. */
    private static final Duration BOUND = Duration.ofSeconds(9);

    @Autowired
    private PostgreSQLContainer postgres;

    @Autowired
    private MeterRegistry meters;

    private boolean paused;

    @AfterEach
    void resume() {
        if (paused) {
            postgres.getDockerClient()
                    .unpauseContainerCmd(postgres.getContainerId())
                    .exec();
            paused = false;
        }
    }

    @Test
    void aStalledDatabaseDoesNotHoldThePaidResponseAndIsCounted() {
        // Warm up: a pooled connection exists, so the stall hits a live socket, not a connect.
        getSummary(newPayload()).expectStatus().isOk();
        assertThat(settlementRows()).isEqualTo(1);
        double before =
                meters.counter("saiman.seller.settlement_record_failures").count();

        postgres.getDockerClient().pauseContainerCmd(postgres.getContainerId()).exec();
        paused = true;
        long start = System.nanoTime();
        getSummary(newPayload()).expectStatus().isOk().expectHeader().exists(X402Headers.PAYMENT_RESPONSE);
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(took).isLessThan(BOUND);
        assertThat(meters.counter("saiman.seller.settlement_record_failures").count())
                .isEqualTo(before + 1);
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(2);

        resume();
        // The pool recovers: the next paid call records again.
        getSummary(newPayload()).expectStatus().isOk();
        assertThat(settlementRows()).isEqualTo(2);
    }
}
