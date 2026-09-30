package io.github.orhanyarkin.saiman.orchestrator.events;

import io.github.orhanyarkin.saiman.shared.run.RunEvent;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * One SSE stream of one run, driven by its own virtual thread.
 *
 * <p>Order of operations, so nothing is lost or sent twice: the bus subscription is registered
 * <em>before</em> the database replay (an event committed in between arrives on the queue and is
 * dropped by seq); then live events are sent in seq order, and a gap (a missed or out-of-order
 * delivery) is backfilled from the database. The stream completes right after a terminal event, or
 * when a heartbeat finds the run terminal without one (a run that was interrupted by a restart).
 */
final class RunEventStream implements Runnable {

    /** Bounded: a full buffer drops the event and the gap check backfills it from the database. */
    private static final int BUFFER = 256;

    private final UUID runId;
    private final SseEmitter emitter;
    private final RunEventAppender log;
    private final RunEventCodec codec;
    private final Predicate<UUID> runIsTerminal;
    private final Duration heartbeat;
    private final BlockingQueue<RunEvent> queue = new LinkedBlockingQueue<>(BUFFER);
    private volatile boolean closed;
    private int lastSent;

    RunEventStream(
            UUID runId,
            int lastEventId,
            SseEmitter emitter,
            RunEventAppender log,
            RunEventCodec codec,
            Predicate<UUID> runIsTerminal,
            Duration heartbeat) {
        this.runId = runId;
        this.lastSent = lastEventId;
        this.emitter = emitter;
        this.log = log;
        this.codec = codec;
        this.runIsTerminal = runIsTerminal;
        this.heartbeat = heartbeat;
    }

    /** The bus listener: never blocks the committing thread. */
    void offer(RunEvent event) {
        queue.offer(event);
    }

    void close() {
        closed = true;
    }

    @Override
    public void run() {
        try {
            if (backfill()) {
                emitter.complete();
                return;
            }
            while (!closed) {
                RunEvent event = queue.poll(heartbeat.toMillis(), TimeUnit.MILLISECONDS);
                if (event == null) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    // Status before backfill: the terminal status and the terminal event commit
                    // together, so a terminal status read first means the backfill sees the event.
                    boolean runTerminal = runIsTerminal.test(runId);
                    if (backfill() || runTerminal) {
                        emitter.complete();
                        return;
                    }
                    continue;
                }
                if (event.seq() <= lastSent) {
                    continue; // already sent by the replay
                }
                boolean terminal = event.seq() == lastSent + 1 ? send(event) : backfill();
                if (terminal) {
                    emitter.complete();
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (IOException | RuntimeException e) {
            // The client went away (or the emitter timed out): nothing to report to it.
            emitter.complete();
        } finally {
            closed = true;
        }
    }

    /**
     * Sends every stored event after {@link #lastSent}.
     *
     * @return true if a terminal event was sent
     */
    private boolean backfill() throws IOException {
        List<RunEvent> missed = log.readAfter(runId, lastSent);
        for (RunEvent event : missed) {
            if (send(event)) {
                return true;
            }
        }
        return false;
    }

    /** Sends one event; true if it was terminal. */
    private boolean send(RunEvent event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(Integer.toString(event.seq()))
                .name(event.type().name())
                .data(codec.encodeEnvelope(event)));
        lastSent = event.seq();
        return event.type().terminal();
    }
}
