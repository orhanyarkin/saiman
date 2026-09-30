package io.github.orhanyarkin.saiman.orchestrator.tool;

import io.github.orhanyarkin.saiman.orchestrator.events.RunEventEmitter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * One run's view of the research tools: what an agent (T4b's Spring AI tool callbacks) talks to.
 * Created by {@link PaidToolGateway#openSession}; holds the run's counters, its ticker catalogue
 * and the evidence it retrieved. {@link #call} is serialised per run (paid calls are strictly
 * sequential) and never throws.
 */
public final class RunToolSession {

    private final UUID runId;
    private final PaidToolGateway gateway;
    private final RunEventEmitter events;
    private final RunPhaseListener phases;
    private final RunEvidence evidence = new RunEvidence();
    final ReentrantLock lock = new ReentrantLock();

    // Guarded by lock.
    int toolCalls;
    int paidCalls;
    private @Nullable Set<String> tickers;

    RunToolSession(UUID runId, PaidToolGateway gateway, RunEventEmitter events, RunPhaseListener phases) {
        this.runId = runId;
        this.gateway = gateway;
        this.events = events;
        this.phases = phases;
    }

    public UUID runId() {
        return runId;
    }

    /** The tools a model may call: names, fixed descriptions and code-generated input schemas. */
    public List<ToolDefinition> definitions() {
        return gateway.definitions();
    }

    /**
     * Calls a tool with the model's raw JSON arguments.
     *
     * @return a {@code <tool_data>} block with the sanitised result, or one of the fixed {@link
     *     ToolMessages}; never an exception message, never raw seller text
     */
    public String call(String toolName, String argumentsJson) {
        return gateway.call(this, toolName, argumentsJson);
    }

    /**
     * The seller's ticker catalogue, fetched once per run (free call); empty if it could not be
     * fetched. The planner validates its tickers against this.
     */
    public Optional<Set<String>> knownTickers() {
        lock.lock();
        try {
            if (tickers == null) {
                tickers = gateway.fetchTickers().orElse(null);
            }
            return Optional.ofNullable(tickers);
        } finally {
            lock.unlock();
        }
    }

    /** What this run's tools retrieved; the only source of citations for its report. */
    public RunEvidence evidence() {
        return evidence;
    }

    /** Paid calls whose payment settled or may have (ambiguous) so far. */
    public int paidCalls() {
        lock.lock();
        try {
            return paidCalls;
        } finally {
            lock.unlock();
        }
    }

    RunEventEmitter events() {
        return events;
    }

    RunPhaseListener phases() {
        return phases;
    }
}
