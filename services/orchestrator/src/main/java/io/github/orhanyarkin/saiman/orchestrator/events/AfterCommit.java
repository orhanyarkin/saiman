package io.github.orhanyarkin.saiman.orchestrator.events;

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
 */
public final class AfterCommit {

    private AfterCommit() {}

    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
