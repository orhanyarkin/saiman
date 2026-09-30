package io.github.orhanyarkin.saiman.orchestrator.events;

import io.github.orhanyarkin.saiman.shared.run.RunEvent;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * In-memory fan-out of committed run events to live subscribers (the SSE streams). Single-instance
 * by design (ADR-0014); a subscriber that misses an event (full buffer, or it subscribed while the
 * event was in flight) sees a seq gap and backfills from the database, which is the source of truth.
 *
 * <p>Bounded: at most {@code max-streams-per-run} subscribers per run and {@code max-streams} in
 * total; a listener must not block (it runs on the thread that committed the event).
 */
@Component
public class RunEventBus {

    private final Map<UUID, Set<Subscription>> subscribers = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    private final int maxPerRun;
    private final int maxTotal;

    public RunEventBus(EventStreamProperties properties) {
        this.maxPerRun = properties.maxStreamsPerRun();
        this.maxTotal = properties.maxStreams();
    }

    /**
     * Registers a listener for one run's events.
     *
     * @throws TooManyStreamsException if the per-run or the global bound is reached
     */
    public Subscription subscribe(UUID runId, Consumer<RunEvent> listener) {
        if (total.incrementAndGet() > maxTotal) {
            total.decrementAndGet();
            throw new TooManyStreamsException();
        }
        Subscription subscription = new Subscription(runId, listener);
        boolean[] added = {false};
        subscribers.compute(runId, (id, current) -> {
            Set<Subscription> set = current == null ? new CopyOnWriteArraySet<>() : current;
            if (set.size() < maxPerRun) {
                set.add(subscription);
                added[0] = true;
            }
            return set.isEmpty() ? null : set;
        });
        if (!added[0]) {
            total.decrementAndGet();
            throw new TooManyStreamsException();
        }
        return subscription;
    }

    /** Live subscribers of one run (for tests and metrics). */
    public int subscriberCount(UUID runId) {
        Set<Subscription> set = subscribers.get(runId);
        return set == null ? 0 : set.size();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onAppended(RunEventAppended appended) {
        RunEvent event = appended.event();
        Set<Subscription> set = subscribers.get(event.runId());
        if (set != null) {
            set.forEach(subscription -> subscription.listener.accept(event));
        }
    }

    private void unsubscribe(Subscription subscription) {
        boolean[] removed = {false};
        subscribers.computeIfPresent(subscription.runId, (id, set) -> {
            removed[0] = set.remove(subscription);
            return set.isEmpty() ? null : set;
        });
        if (removed[0]) {
            total.decrementAndGet();
        }
    }

    /** A registration; close it when the stream ends. */
    public final class Subscription implements AutoCloseable {
        private final UUID runId;
        private final Consumer<RunEvent> listener;

        private Subscription(UUID runId, Consumer<RunEvent> listener) {
            this.runId = runId;
            this.listener = listener;
        }

        @Override
        public void close() {
            unsubscribe(this);
        }
    }

    /** The per-run or global stream bound is reached. */
    public static final class TooManyStreamsException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TooManyStreamsException() {
            super("too many event streams");
        }
    }
}
