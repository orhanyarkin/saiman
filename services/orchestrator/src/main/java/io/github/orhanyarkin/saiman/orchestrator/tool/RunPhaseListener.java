package io.github.orhanyarkin.saiman.orchestrator.tool;

import java.time.Instant;

/**
 * Told by the gateway when a run starts and stops waiting for a human approval, so the run's status
 * can say AWAITING_APPROVAL meanwhile. Implemented by the run package (the tool package does not
 * depend on it).
 */
public interface RunPhaseListener {

    RunPhaseListener NONE = new RunPhaseListener() {
        @Override
        public void awaitingApproval() {}

        @Override
        public void resumed() {}
    };

    void awaitingApproval();

    void resumed();

    /** The run's wall-clock deadline: no tool call starts and no approval is awaited past it. */
    default Instant deadline() {
        return Instant.MAX;
    }
}
