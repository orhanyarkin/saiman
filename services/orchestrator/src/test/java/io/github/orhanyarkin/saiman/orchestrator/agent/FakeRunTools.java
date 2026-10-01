package io.github.orhanyarkin.saiman.orchestrator.agent;

import io.github.orhanyarkin.saiman.orchestrator.tool.EvidenceCitation;
import io.github.orhanyarkin.saiman.orchestrator.tool.ToolDefinition;
import io.github.orhanyarkin.saiman.orchestrator.tool.ToolParameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;

/**
 * {@link RunTools} without a gateway: a fixed catalogue, and every call returns a {@code <tool_data>}
 * block whose citations become the run's evidence. Records the calls it got.
 */
final class FakeRunTools implements RunTools {

    static final String SUMMARY = "disclosureSummary";
    static final String ASK = "askDisclosures";

    record Call(String tool, String arguments) {}

    private final @Nullable Set<String> catalogue;
    private final List<EvidenceCitation> perCall;
    private final Map<String, EvidenceCitation> evidence = new LinkedHashMap<>();
    final List<Call> calls = new CopyOnWriteArrayList<>();

    FakeRunTools(@Nullable Set<String> catalogue, List<EvidenceCitation> perCall) {
        this.catalogue = catalogue;
        this.perCall = List.copyOf(perCall);
    }

    static FakeRunTools withEvidence(EvidenceCitation... citations) {
        return new FakeRunTools(Set.of("THYAO", "ASELS", "GARAN", "AKBNK"), List.of(citations));
    }

    static List<ToolDefinition> definitions(List<String> names) {
        List<ToolDefinition> definitions = new ArrayList<>();
        for (String name : names) {
            List<ToolParameter> parameters = name.equals(ASK)
                    ? List.of(ToolParameter.TICKER, ToolParameter.QUESTION)
                    : List.of(ToolParameter.TICKER);
            definitions.add(new ToolDefinition(name, "test tool " + name, parameters, "{\"type\":\"object\"}"));
        }
        return definitions;
    }

    @Override
    public List<ToolDefinition> definitions() {
        return definitions(List.of(SUMMARY, ASK));
    }

    @Override
    public synchronized String call(String toolName, String argumentsJson) {
        calls.add(new Call(toolName, argumentsJson));
        StringBuilder citations = new StringBuilder();
        for (EvidenceCitation citation : perCall) {
            evidence.putIfAbsent(citation.chunkId(), citation);
            if (!citations.isEmpty()) {
                citations.append(',');
            }
            citations.append("{\"chunkId\":\"").append(citation.chunkId()).append("\"}");
        }
        return "<tool_data>{\"answer\":\"evidence text\",\"citations\":[" + citations + "]}</tool_data>";
    }

    @Override
    public Optional<Set<String>> knownTickers() {
        return Optional.ofNullable(catalogue);
    }

    @Override
    public synchronized Optional<EvidenceCitation> evidence(String chunkId) {
        return Optional.ofNullable(evidence.get(chunkId));
    }

    @Override
    public synchronized List<EvidenceCitation> allEvidence() {
        return List.copyOf(evidence.values());
    }
}
