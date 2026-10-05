package io.github.orhanyarkin.saiman.orchestrator.dashboard;

import java.sql.SQLException;
import java.time.Duration;
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
 */
@Component
public class BoundedReads {

    private static final String QUERY_CANCELED = "57014";

    private final TransactionTemplate transaction;
    private final JdbcClient jdbc;
    private final long timeoutMillis;

    BoundedReads(
            PlatformTransactionManager transactionManager,
            JdbcClient jdbc,
            @Value("${saiman.orchestrator.dashboard.read-timeout:3s}") Duration timeout) {
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setReadOnly(true);
        this.jdbc = jdbc;
        this.timeoutMillis = Math.max(1, timeout.toMillis());
    }

    public <T> T read(Supplier<T> query) {
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
