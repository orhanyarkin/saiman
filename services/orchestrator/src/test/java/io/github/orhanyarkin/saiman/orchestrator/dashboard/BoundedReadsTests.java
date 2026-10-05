package io.github.orhanyarkin.saiman.orchestrator.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.orchestrator.spendtest.RunTestSupport;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.request.ServletWebRequest;

class BoundedReadsTests extends RunTestSupport {

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private BoundedReads defaultReads;

    @Test
    void aSlowQueryIsCancelledAndSurfacesAsAReadTimeout() {
        BoundedReads reads = new BoundedReads(transactions, jdbc, Duration.ofMillis(100), 4);
        long started = System.nanoTime();
        assertThatThrownBy(() ->
                        reads.read(() -> jdbc.sql("SELECT pg_sleep(5)").query().listOfRows()))
                .isInstanceOf(ReadTimeoutException.class)
                .hasMessage("dashboard read timed out");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    void theTimeoutIsLocalToTheTransactionAndTheDefaultIsAppliedWhenConfigured() {
        BoundedReads reads = new BoundedReads(transactions, jdbc, Duration.ofMillis(1234), 4);
        assertThat(reads.read(() ->
                        jdbc.sql("SHOW statement_timeout").query(String.class).single()))
                .isEqualTo("1234ms");
        // a pooled connection is not left with the limit
        for (int i = 0; i < 5; i++) {
            assertThat(jdbc.sql("SHOW statement_timeout").query(String.class).single())
                    .isEqualTo("0");
        }
        assertThat(defaultReads.read(() ->
                        jdbc.sql("SHOW statement_timeout").query(String.class).single()))
                .isEqualTo("3s");
    }

    @Test
    void theReadTransactionIsReadOnly() {
        assertThatThrownBy(() ->
                        defaultReads.read(() -> jdbc.sql("DELETE FROM run").update()))
                .isInstanceOf(DataAccessException.class)
                .isNotInstanceOf(ReadTimeoutException.class);
    }

    @Test
    void aTimeoutBecomesAFixedServiceUnavailableProblem() {
        ResponseEntity<Object> response = new ApiExceptionAdvice()
                .readTimedOut(
                        new ReadTimeoutException(new RuntimeException("SELECT secret FROM somewhere")),
                        new ServletWebRequest(new MockHttpServletRequest("GET", "/api/v1/spend")));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("5");
        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getDetail()).isEqualTo("the read took too long; retry");
        assertThat(problem.getInstance()).hasToString("/unmatched");
    }

    @Test
    void aSecondReadIsRefusedAtOnceWhileTheOnlyPermitIsInUse() throws Exception {
        BoundedReads reads = new BoundedReads(transactions, jdbc, Duration.ofSeconds(5), 1);
        CountDownLatch inFlight = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<Object> slow = pool.submit(() -> reads.read(() -> {
                inFlight.countDown();
                return jdbc.sql("SELECT pg_sleep(2)").query().listOfRows();
            }));
            assertThat(inFlight.await(5, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            assertThatThrownBy(() -> reads.read(() -> "never runs")).isInstanceOf(ReadSaturatedException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));

            slow.get(10, TimeUnit.SECONDS);
            assertThat(reads.read(() -> "again")).isEqualTo("again"); // released after success
        }
    }

    @Test
    void permitsAreReleasedAfterATimeoutAndAfterAnyException() {
        BoundedReads reads = new BoundedReads(transactions, jdbc, Duration.ofMillis(100), 1);
        assertThatThrownBy(() ->
                        reads.read(() -> jdbc.sql("SELECT pg_sleep(5)").query().listOfRows()))
                .isInstanceOf(ReadTimeoutException.class);
        assertThat(reads.read(() -> "after timeout")).isEqualTo("after timeout");

        assertThatThrownBy(() -> reads.read(() -> jdbc.sql("DELETE FROM run").update()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> reads.read(() -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reads.read(() -> "after failures")).isEqualTo("after failures");
    }

    // The planner is told not to scan sequentially: the point is that an index exists that serves the
    // query's shape (filter + order), which a seq scan on a few seeded rows would not prove.
    @Test
    void theDashboardQueriesCanUseTheirIndexes() throws SQLException {
        assertThat(plan("SELECT * FROM run r WHERE (r.created_at, r.id) < (now(), gen_random_uuid())"
                        + " ORDER BY r.created_at DESC, r.id DESC LIMIT 21"))
                .contains("run_created_idx");
        assertThat(plan("SELECT * FROM run ORDER BY created_at DESC, id DESC LIMIT 21"))
                .contains("run_created_idx");
        assertThat(plan("SELECT * FROM approval WHERE status = 'PENDING' ORDER BY requested_at DESC, id DESC"
                        + " LIMIT 100"))
                .contains("approval_status_requested_idx");
        assertThat(plan("SELECT tool, status, count(*) FROM payment_intent WHERE reserved_day = DATE '2026-03-04'"
                        + " OR (reserved_day IS NULL AND created_at >= TIMESTAMPTZ '2026-03-04 00:00:00+00'"
                        + " AND created_at < TIMESTAMPTZ '2026-03-05 00:00:00+00') GROUP BY tool, status"))
                .contains("payment_intent_day_idx");
    }

    private String plan(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET enable_seqscan = off");
            List<String> lines = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery("EXPLAIN " + sql)) {
                while (rs.next()) {
                    lines.add(rs.getString(1));
                }
            }
            statement.execute("RESET enable_seqscan");
            return String.join("\n", lines);
        }
    }
}
