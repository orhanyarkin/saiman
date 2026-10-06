package io.github.orhanyarkin.saiman.dbmigrate;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Set by the migrate strategy once Flyway's {@code migrate} succeeded. The exit code depends on it, not on the
 * context merely starting: an excluded auto-configuration or lazy initialisation would otherwise exit 0 untouched.
 */
final class MigrationOutcome {

    private final AtomicBoolean completed = new AtomicBoolean();

    void markCompleted() {
        completed.set(true);
    }

    boolean completed() {
        return completed.get();
    }
}
