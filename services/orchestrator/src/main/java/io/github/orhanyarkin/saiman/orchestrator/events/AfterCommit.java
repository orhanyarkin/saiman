package io.github.orhanyarkin.saiman.orchestrator.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs in-memory work after the current transaction commits (immediately when there is none): what
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)} does, without being one.
 *
 * <p>Why not that annotation: Spring Modulith's {@code CompletionRegisteringAdvisor} wraps every AFTER_COMMIT
 * transactional listener and records a state transition in a new transaction ({@code REQUIRES_NEW}) while the
 * committing thread still holds its own connection. Under concurrent appends that needs two pooled connections
 * per thread and starves Hikari. The in-process listeners ({@link RunEventBus}, the approval waiter) are plain
 * {@code @EventListener}s that use this instead; only the outbox's {@code @ApplicationModuleListener} is a
 * transactional listener (ADR-0016).
 *
 * <p>The action runs after the state change is durable, so a failure in it must not reach the committing caller
 * (it would look as if the commit failed): it is logged (exception class only, the message may carry data) and
 * swallowed.
 */
public final class AfterCommit {

    private static final Logger LOG = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() {}

    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runSafely(action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runSafely(action);
            }
        });
    }

    private static void runSafely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            LOG.error("an after-commit action failed: {}", e.getClass().getName());
        }
    }
}
