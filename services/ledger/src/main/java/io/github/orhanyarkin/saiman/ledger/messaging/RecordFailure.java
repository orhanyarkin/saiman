package io.github.orhanyarkin.saiman.ledger.messaging;

import io.github.orhanyarkin.saiman.ledger.payment.ConflictingFactException;
import io.github.orhanyarkin.saiman.ledger.payment.MalformedPaymentEventException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
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
 *
 * <p>Before that walk, the cause chain is searched for a {@link SQLException} whose SQLSTATE class is {@code 08}
 * (connection exception), {@code 53} (insufficient resources) or {@code 57} (operator intervention, e.g. a server
 * shutdown or a cancelled statement). Those are environment failures whatever Spring wrapped them in, including a
 * {@link org.springframework.transaction.TransactionSystemException} from a failed commit, so they are transient.
 *
 * <p>Schema and permission problems ({@link InvalidDataAccessResourceUsageException}, which covers {@link
 * org.springframework.jdbc.BadSqlGrammarException}, and {@link UncategorizedSQLException}) are deliberately
 * {@link #UNKNOWN}, not deterministic: they are usually drift of the deployment (a migration not yet applied, a
 * revoked grant) rather than a property of the record, so the record is retried for a bounded time and only then
 * quarantined. Drift stalls the partition for up to an hour instead of dead-lettering every record that arrives
 * meanwhile.
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

    /** SQLSTATE classes (first two characters) that denote a failure of the environment, not of the record. */
    private static final Set<String> TRANSIENT_SQLSTATE_CLASSES = Set.of("08", "53", "57");

    static RecordFailure classify(@Nullable Throwable failure) {
        if (hasTransientSqlState(failure)) {
            return TRANSIENT;
        }
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

    private static boolean hasTransientSqlState(@Nullable Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null
                        && state.length() >= 2
                        && TRANSIENT_SQLSTATE_CLASSES.contains(state.substring(0, 2))) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
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
        if (t instanceof InvalidDataAccessResourceUsageException || t instanceof UncategorizedSQLException) {
            return UNKNOWN;
        }
        if (t instanceof NonTransientDataAccessException || t instanceof IllegalArgumentException) {
            return DETERMINISTIC;
        }
        return null;
    }
}
