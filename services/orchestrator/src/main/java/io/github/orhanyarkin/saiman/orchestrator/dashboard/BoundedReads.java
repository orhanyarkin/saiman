package io.github.orhanyarkin.saiman.orchestrator.dashboard;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a dashboard read in a read-only transaction with a local {@code statement_timeout}, so an
 * unauthenticated, frequently polled endpoint can never hold a connection for long. The timeout is
 * transaction-local (set through {@code set_config(..., true)}), so it cannot leak to the pooled
 * connection's next user. A timeout surfaces as {@link ReadTimeoutException}, never raw SQL text.
 *
 * <p>A bulkhead bounds how many reads run at once ({@code saiman.orchestrator.dashboard.max-concurrent-reads}):
 * the endpoints are unauthenticated, and unbounded parallel reads could hold every pooled connection
 * and starve the spend guard. The permit is taken without waiting; when none is free the read fails
 * at once with {@link ReadSaturatedException}.
 */
@Component
public class BoundedReads {

    private static final String QUERY_CANCELED = "57014";

    private final TransactionTemplate transaction;
    private final JdbcClient jdbc;
    private final long timeoutMillis;
    private final Semaphore permits;

    BoundedReads(
            PlatformTransactionManager transactionManager,
            JdbcClient jdbc,
            @Value("${saiman.orchestrator.dashboard.read-timeout:3s}") Duration timeout,
            @Value("${saiman.orchestrator.dashboard.max-concurrent-reads:4}") int maxConcurrentReads) {
        this.permits = new Semaphore(Math.max(1, maxConcurrentReads));
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setReadOnly(true);
        this.jdbc = jdbc;
        this.timeoutMillis = Math.max(1, timeout.toMillis());
    }

    public <T> T read(Supplier<T> query) {
        if (!permits.tryAcquire()) {
            throw new ReadSaturatedException();
        }
        try {
            return bounded(query);
        } finally {
            permits.release();
        }
    }

    private <T> T bounded(Supplier<T> query) {
        try {
            return transaction.execute(status -> {
                jdbc.sql("SELECT set_config('statement_timeout', :ms, true)")
                        .param("ms", Long.toString(timeoutMillis))
                        .query(String.class)
                        .single();
                return query.get();
            });
        } catch (DataAccessException e) {
            if (e.getMostSpecificCause() instanceof SQLException sql && QUERY_CANCELED.equals(sql.getSQLState())) {
                throw new ReadTimeoutException(e);
            }
            throw e;
        }
    }
}
