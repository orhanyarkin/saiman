package io.github.orhanyarkin.saiman.orchestrator.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** A failing deferred action must never propagate to the committing caller. */
class AfterCommitTests {

    @Test
    void anExceptionInTheDeferredActionDoesNotReachTheCommit() {
        AtomicInteger ran = new AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();
        try {
            AfterCommit.run(() -> {
                ran.incrementAndGet();
                throw new IllegalStateException("secret detail");
            });
            AfterCommit.run(ran::incrementAndGet); // a later action still runs

            assertThat(ran).hasValue(0); // deferred until commit
            assertThatCode(() -> {
                        for (TransactionSynchronization sync :
                                TransactionSynchronizationManager.getSynchronizations()) {
                            sync.afterCommit();
                        }
                    })
                    .doesNotThrowAnyException();
            assertThat(ran).hasValue(2);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void withoutATransactionTheActionRunsAtOnceAndFailuresAreSwallowed() {
        AtomicInteger ran = new AtomicInteger();

        assertThatCode(() -> AfterCommit.run(() -> {
                    ran.incrementAndGet();
                    throw new IllegalStateException("boom");
                }))
                .doesNotThrowAnyException();

        assertThat(ran).hasValue(1);
    }
}
