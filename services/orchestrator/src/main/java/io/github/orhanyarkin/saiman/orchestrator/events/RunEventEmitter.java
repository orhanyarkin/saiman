package io.github.orhanyarkin.saiman.orchestrator.events;

import io.github.orhanyarkin.saiman.shared.run.RunEvent;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import io.github.orhanyarkin.saiman.shared.run.RunEventType;

/** Appends events to one run's log (bound to the run by {@link RunEventAppender#emitterFor}). */
@FunctionalInterface
public interface RunEventEmitter {

    /**
     * Appends one event; it is streamed once its transaction commits.
     *
     * @throws IllegalArgumentException if {@code data} is not the payload class of {@code type}
     */
    RunEvent emit(RunEventType type, RunEventData data);
}
