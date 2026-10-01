package io.github.orhanyarkin.saiman.ledger.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.ledger.payment.ConflictingFactException;
import io.github.orhanyarkin.saiman.ledger.payment.MalformedPaymentEventException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

/** {@link RecordFailure}: which failures are quarantined at once, retried forever, or retried for a bounded time. */
class RecordFailureTests {

    @Test
    void deterministicFailuresAreQuarantinedAtOnce() {
        assertThat(RecordFailure.classify(new MalformedPaymentEventException("x")))
                .isEqualTo(RecordFailure.DETERMINISTIC);
        assertThat(RecordFailure.classify(new ConflictingFactException("x", null)))
                .isEqualTo(RecordFailure.DETERMINISTIC);
        assertThat(RecordFailure.classify(new DataIntegrityViolationException("check")))
                .isEqualTo(RecordFailure.DETERMINISTIC);
        assertThat(RecordFailure.classify(new DuplicateKeyException("dup"))).isEqualTo(RecordFailure.DETERMINISTIC);
        assertThat(RecordFailure.classify(
                        new DataIntegrityViolationException("encoding", new SQLException("bad byte", "22021"))))
                .as("an invalid encoding is a property of the record")
                .isEqualTo(RecordFailure.DETERMINISTIC);
        assertThat(RecordFailure.classify(
                        new DataIntegrityViolationException("check", new SQLException("check violation", "23514"))))
                .isEqualTo(RecordFailure.DETERMINISTIC);
        assertThat(RecordFailure.classify(new IllegalArgumentException("record constructor")))
                .isEqualTo(RecordFailure.DETERMINISTIC);
    }

    @Test
    void connectionTimeoutAndLockFailuresAreTransient() {
        assertThat(RecordFailure.classify(new CannotGetJdbcConnectionException("down")))
                .as("a NonTransientDataAccessException subtype in Spring, still a connection failure")
                .isEqualTo(RecordFailure.TRANSIENT);
        assertThat(RecordFailure.classify(new CannotCreateTransactionException("no connection")))
                .isEqualTo(RecordFailure.TRANSIENT);
        assertThat(RecordFailure.classify(new QueryTimeoutException("slow"))).isEqualTo(RecordFailure.TRANSIENT);
        assertThat(RecordFailure.classify(new CannotAcquireLockException("lock")))
                .isEqualTo(RecordFailure.TRANSIENT);
        assertThat(RecordFailure.classify(new PessimisticLockingFailureException("deadlock")))
                .isEqualTo(RecordFailure.TRANSIENT);
        assertThat(RecordFailure.classify(new SQLTransientConnectionException("pool timeout")))
                .isEqualTo(RecordFailure.TRANSIENT);
    }

    @Test
    void causesAreInspectedThroughTheListenerWrapper() {
        assertThat(RecordFailure.classify(
                        new ListenerExecutionFailedException("listener", new DataIntegrityViolationException("check"))))
                .isEqualTo(RecordFailure.DETERMINISTIC);
        assertThat(RecordFailure.classify(
                        new ListenerExecutionFailedException("listener", new CannotGetJdbcConnectionException("down"))))
                .isEqualTo(RecordFailure.TRANSIENT);
    }

    @Test
    void schemaAndPermissionDriftIsRetriedForABoundedTimeNotQuarantined() {
        assertThat(RecordFailure.classify(new BadSqlGrammarException("t", "sql", new SQLException("x", "42601"))))
                .isEqualTo(RecordFailure.UNKNOWN);
        assertThat(RecordFailure.classify(new BadSqlGrammarException("t", "sql", new SQLException("x", "42P01"))))
                .as("missing table: a migration not yet applied")
                .isEqualTo(RecordFailure.UNKNOWN);
        assertThat(RecordFailure.classify(new InvalidDataAccessResourceUsageException("permission denied")))
                .isEqualTo(RecordFailure.UNKNOWN);
        assertThat(RecordFailure.classify(new UncategorizedSQLException("t", "sql", new SQLException("x", "XX000"))))
                .isEqualTo(RecordFailure.UNKNOWN);
        assertThat(RecordFailure.classify(new ListenerExecutionFailedException(
                        "listener", new BadSqlGrammarException("t", "sql", new SQLException("x", "42501")))))
                .isEqualTo(RecordFailure.UNKNOWN);
    }

    @Test
    void connectionResourceAndOperatorSqlStatesAreTransientWhateverTheWrapper() {
        assertThat(RecordFailure.classify(new UncategorizedSQLException("t", "sql", new SQLException("x", "08006"))))
                .as("connection failure")
                .isEqualTo(RecordFailure.TRANSIENT);
        assertThat(RecordFailure.classify(new UncategorizedSQLException("t", "sql", new SQLException("x", "53300"))))
                .as("too many connections")
                .isEqualTo(RecordFailure.TRANSIENT);
        assertThat(RecordFailure.classify(new BadSqlGrammarException("t", "sql", new SQLException("x", "57P01"))))
                .as("admin shutdown")
                .isEqualTo(RecordFailure.TRANSIENT);
        assertThat(RecordFailure.classify(new UncategorizedSQLException("t", "sql", new SQLException("x", "57014"))))
                .as("statement cancelled")
                .isEqualTo(RecordFailure.TRANSIENT);
        assertThat(RecordFailure.classify(
                        new TransactionSystemException("commit failed", new SQLException("connection reset", "08003"))))
                .as("a failed commit wraps the SQLException in TransactionSystemException")
                .isEqualTo(RecordFailure.TRANSIENT);
        assertThat(RecordFailure.classify(new ListenerExecutionFailedException(
                        "listener",
                        new TransactionSystemException("commit failed", new SQLException("disk full", "53100")))))
                .isEqualTo(RecordFailure.TRANSIENT);
        assertThat(RecordFailure.classify(
                        new TransactionSystemException("commit failed", new SQLException("x", "XX000"))))
                .as("other SQLSTATEs under TransactionSystemException fall back to UNKNOWN")
                .isEqualTo(RecordFailure.UNKNOWN);
    }

    @Test
    void anythingElseIsUnknown() {
        assertThat(RecordFailure.classify(new IllegalStateException("bug"))).isEqualTo(RecordFailure.UNKNOWN);
        assertThat(RecordFailure.classify(new ListenerExecutionFailedException("listener", new NullPointerException())))
                .isEqualTo(RecordFailure.UNKNOWN);
        assertThat(RecordFailure.classify(null)).isEqualTo(RecordFailure.UNKNOWN);
    }

    @Test
    void cyclicCauseChainTerminates() {
        var a = new RuntimeException("a");
        var b = new RuntimeException("b", a);
        a.initCause(b);

        assertThat(RecordFailure.classify(a)).isEqualTo(RecordFailure.UNKNOWN);
    }
}
