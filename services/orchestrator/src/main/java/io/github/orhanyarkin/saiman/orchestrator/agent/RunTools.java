package io.github.orhanyarkin.saiman.orchestrator.agent;

import io.github.orhanyarkin.saiman.orchestrator.tool.EvidenceCitation;
import io.github.orhanyarkin.saiman.orchestrator.tool.RunToolSession;
import io.github.orhanyarkin.saiman.orchestrator.tool.ToolDefinition;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What the agents need of one run's tools. In production this is the run's {@link RunToolSession}
 * (every call goes through the paid-tool gateway); tests substitute a scripted implementation so the
 * pipeline can be exercised without a database or a seller.
 */
public interface RunTools {

    /** The tools a model may call (names, fixed descriptions, code-generated input schemas). */
    List<ToolDefinition> definitions();

    /**
     * Calls a tool with the model's raw JSON arguments; never throws.
     *
     * @return a {@code <tool_data>} block or a fixed error message
     */
    String call(String toolName, String argumentsJson);

    /** The seller's ticker catalogue, or empty if it could not be fetched. */
    Optional<Set<String>> knownTickers();

    /** A chunk this run retrieved, by id. */
    Optional<EvidenceCitation> evidence(String chunkId);

    /** Every chunk this run retrieved, in retrieval order. */
    List<EvidenceCitation> allEvidence();

    /** The production adapter over a run's tool session. */
    static RunTools of(RunToolSession session) {
        return new RunTools() {
            @Override
            public List<ToolDefinition> definitions() {
                return session.definitions();
            }

            @Override
            public String call(String toolName, String argumentsJson) {
                return session.call(toolName, argumentsJson);
            }

            @Override
            public Optional<Set<String>> knownTickers() {
                return session.knownTickers();
            }

            @Override
            public Optional<EvidenceCitation> evidence(String chunkId) {
                return session.evidence().find(chunkId);
            }

            @Override
            public List<EvidenceCitation> allEvidence() {
                return session.evidence().all();
            }
        };
    }
}
