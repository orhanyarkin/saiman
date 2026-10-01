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
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.transaction.CannotCreateTransactionException;

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
        assertThat(RecordFailure.classify(new BadSqlGrammarException("t", "sql", new SQLException("x", "42601"))))
                .isEqualTo(RecordFailure.DETERMINISTIC);
        assertThat(RecordFailure.classify(new UncategorizedSQLException("t", "sql", new SQLException("x", "XX000"))))
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
