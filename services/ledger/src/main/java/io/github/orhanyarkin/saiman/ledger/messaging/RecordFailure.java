package io.github.orhanyarkin.saiman.ledger.messaging;

import io.github.orhanyarkin.saiman.ledger.payment.ConflictingFactException;
import io.github.orhanyarkin.saiman.ledger.payment.MalformedPaymentEventException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * How the ledger's Kafka error handler treats a failed {@code payments.*} record. The point is that no single
 * record can stall a partition: a failure that would repeat the same way on every redelivery is quarantined at
 * once, and only failures of the environment (connection, timeout, lock) are retried without limit.
 *
 * <p>The exception and its causes are inspected outermost first; the first one that falls into a class decides.
 * {@link DataAccessResourceFailureException} (what Spring makes of SQLSTATE class {@code 08}, a lost connection, and
 * of {@link org.springframework.jdbc.CannotGetJdbcConnectionException}) is a {@link
 * NonTransientDataAccessException} in Spring's hierarchy, so it is checked before that type and treated as
 * transient.
 */
enum RecordFailure {

    /** Fails the same way on every redelivery: malformed or conflicting facts, constraint and data errors. */
    DETERMINISTIC,

    /** The database is unreachable, slow or contended: retried with back-off, never dead-lettered. */
    TRANSIENT,

    /** Neither (a bug, an unexpected runtime error): retried for a bounded time, then quarantined as exhausted. */
    UNKNOWN;

    /** Bounded walk: a cyclic cause chain cannot loop. */
    private static final int MAX_DEPTH = 16;

    static RecordFailure classify(@Nullable Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            RecordFailure decided = decide(current);
            if (decided != null) {
                return decided;
            }
            current = current.getCause();
        }
        return UNKNOWN;
    }

    private static @Nullable RecordFailure decide(Throwable t) {
        if (t instanceof MalformedPaymentEventException || t instanceof ConflictingFactException) {
            return DETERMINISTIC;
        }
        if (t instanceof TransientDataAccessException
                || t instanceof RecoverableDataAccessException
                || t instanceof DataAccessResourceFailureException
                || t instanceof CannotCreateTransactionException
                || t instanceof SQLTransientException
                || t instanceof SQLRecoverableException
                || t instanceof SQLNonTransientConnectionException) {
            return TRANSIENT;
        }
        if (t instanceof NonTransientDataAccessException || t instanceof IllegalArgumentException) {
            return DETERMINISTIC;
        }
        return null;
    }
}
