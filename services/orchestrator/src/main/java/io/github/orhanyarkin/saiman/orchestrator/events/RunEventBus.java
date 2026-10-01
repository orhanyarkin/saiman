package io.github.orhanyarkin.saiman.orchestrator.events;

import io.github.orhanyarkin.saiman.shared.run.RunEvent;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * In-memory fan-out of committed run events to live subscribers (the SSE streams). Single-instance
 * by design (ADR-0014); a subscriber that misses an event (full buffer, or it subscribed while the
 * event was in flight) sees a seq gap and backfills from the database, which is the source of truth.
 *
 * <p>Bounded: at most {@code max-streams-per-run} subscribers per run and {@code max-streams} in
 * total; a listener must not block (it runs on the thread that committed the event). A new
 * subscriber of a run whose per-run slots are full <em>replaces</em> the run's oldest subscriber
 * (that one is evicted and its stream ends): a client that reconnects without the old connection
 * having been noticed as closed is not locked out of its own run. The global bound is never
 * exceeded by a replacement, since one slot is freed for the one taken.
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
     * @param onEvicted called (outside any lock) if a newer subscriber of the same run replaces this
     *     one; it should end the stream
     * @throws TooManyStreamsException if the global bound is reached
     */
    public Subscription subscribe(UUID runId, Consumer<RunEvent> listener, Runnable onEvicted) {
        Subscription subscription = new Subscription(runId, listener, onEvicted);
        Subscription[] evicted = {null};
        boolean[] overGlobal = {false};
        subscribers.compute(runId, (id, current) -> {
            Set<Subscription> set = current == null ? new CopyOnWriteArraySet<>() : current;
            if (set.size() >= maxPerRun) {
                // Replace the oldest (insertion order): one slot freed for the one taken.
                Subscription oldest = set.iterator().next();
                set.remove(oldest);
                evicted[0] = oldest;
            } else if (total.incrementAndGet() > maxTotal) {
                total.decrementAndGet();
                overGlobal[0] = true;
                return set.isEmpty() ? null : set;
            }
            set.add(subscription);
            return set;
        });
        if (overGlobal[0]) {
            throw new TooManyStreamsException();
        }
        if (evicted[0] != null) {
            evicted[0].onEvicted.run();
        }
        return subscription;
    }

    /** All live subscribers (for tests and metrics). */
    public int totalSubscribers() {
        return total.get();
    }

    /** Live subscribers of one run (for tests and metrics). */
    public int subscriberCount(UUID runId) {
        Set<Subscription> set = subscribers.get(runId);
        return set == null ? 0 : set.size();
    }

    /** Fans the event out once the appending transaction has committed (see {@link AfterCommit} for why). */
    @EventListener
    void onAppended(RunEventAppended appended) {
        AfterCommit.run(() -> {
            RunEvent event = appended.event();
            Set<Subscription> set = subscribers.get(event.runId());
            if (set != null) {
                set.forEach(subscription -> subscription.listener.accept(event));
            }
        });
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
        private final Runnable onEvicted;

        private Subscription(UUID runId, Consumer<RunEvent> listener, Runnable onEvicted) {
            this.runId = runId;
            this.listener = listener;
            this.onEvicted = onEvicted;
        }

        @Override
        public void close() {
            unsubscribe(this);
        }
    }

    /** The global stream bound is reached. */
    public static final class TooManyStreamsException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TooManyStreamsException() {
            super("too many event streams");
        }
    }
}
