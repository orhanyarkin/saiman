package io.github.orhanyarkin.saiman.ledger.api;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a dashboard read so that the unauthenticated, frequently polled read endpoints can neither hold a connection
 * for long nor take the whole pool away from the Kafka inbox and posting path:
 *
 * <ul>
 *   <li><b>Time:</b> a read-only transaction with a transaction-local {@code statement_timeout} ({@code set_config(...,
 *       true)}), so the limit ends with the transaction and never leaks to the pooled connection's next user. A
 *       cancelled statement (SQLSTATE 57014) becomes {@link ReadTimeoutException}.
 *   <li><b>Concurrency:</b> a bulkhead of {@code max-concurrent-reads} permits, taken without waiting; when none is
 *       free the read is refused with {@link ReadsBusyException} instead of queueing for a connection.
 * </ul>
 *
 * {@link ApiExceptionHandler} renders both as a fixed 503 with {@code Retry-After}.
 */
@Component
class BoundedReads {

    private static final String QUERY_CANCELED = "57014";

    private final TransactionTemplate transaction;
    private final JdbcClient jdbc;
    private final long timeoutMillis;
    private final Semaphore permits;

    @Autowired
    BoundedReads(
            PlatformTransactionManager transactionManager,
            JdbcClient jdbc,
            @Value("${saiman.ledger.dashboard.read-timeout:3s}") Duration timeout,
            @Value("${saiman.ledger.dashboard.max-concurrent-reads:4}") int maxConcurrentReads) {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("saiman.ledger.dashboard.read-timeout must be positive");
        }
        if (maxConcurrentReads < 1) {
            throw new IllegalArgumentException("saiman.ledger.dashboard.max-concurrent-reads must be at least 1");
        }
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setReadOnly(true);
        this.jdbc = jdbc;
        this.timeoutMillis = Math.max(1, timeout.toMillis());
        this.permits = new Semaphore(maxConcurrentReads);
    }

    /** A 503 the caller may retry after a few seconds: the read took longer than {@code read-timeout}. */
    static final class ReadTimeoutException extends RuntimeException {
        ReadTimeoutException() {
            super("dashboard read timed out", null, false, false);
        }
    }

    /** A 503 the caller may retry after a few seconds: every read permit is in use. */
    static final class ReadsBusyException extends RuntimeException {
        ReadsBusyException() {
            super("too many concurrent dashboard reads", null, false, false);
        }
    }

    /**
     * Runs {@code query} under the bulkhead and the statement timeout. A {@code @Transactional} method called inside
     * joins this transaction, so the timeout covers it.
     *
     * @throws ReadsBusyException when no permit is free
     * @throws ReadTimeoutException when Postgres cancelled a statement for the timeout
     */
    <T> T read(Supplier<T> query) {
        if (!permits.tryAcquire()) {
            throw new ReadsBusyException();
        }
        try {
            T result = transaction.execute(status -> {
                jdbc.sql("SELECT set_config('statement_timeout', :ms, true)")
                        .param("ms", Long.toString(timeoutMillis))
                        .query(String.class)
                        .single();
                return query.get();
            });
            if (result == null) {
                throw new IllegalStateException("a dashboard read returned null");
            }
            return result;
        } catch (DataAccessException e) {
            if (e.getMostSpecificCause() instanceof SQLException sql && QUERY_CANCELED.equals(sql.getSQLState())) {
                throw new ReadTimeoutException();
            }
            throw e;
        } finally {
            permits.release();
        }
    }
}
