package io.github.orhanyarkin.saiman.ingest.pipeline;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Opens the connection that holds the ingest advisory lock. It must not come from the pool: a
 * session-level lock outlives a pooled connection's return to the pool if the unlock fails, and
 * the next borrower would inherit it.
 */
@FunctionalInterface
public interface LockConnectionFactory {

    Connection open() throws SQLException;
}
