package io.github.orhanyarkin.saiman.orchestrator.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.orchestrator.tool.ResearchTool;
import io.github.orhanyarkin.saiman.orchestrator.tool.ToolCall;
import io.github.orhanyarkin.saiman.orchestrator.tool.ToolCatalog;
import io.github.orhanyarkin.saiman.orchestrator.tool.ToolInvocation;
import io.github.orhanyarkin.saiman.orchestrator.tool.ToolMessages;
import io.github.orhanyarkin.saiman.orchestrator.tool.ToolParameter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The generic tool adapter: built from the catalogue, routed by the tool context, bounded in code. */
class ResearchToolCallbacksTests {

    private record CatalogTool(String name, List<ToolParameter> parameters) implements ResearchTool {
        @Override
        public String description() {
            return "fixed description of " + name;
        }

        @Override
        public ToolCall prepare(ToolInvocation invocation) {
            throw new UnsupportedOperationException("never called by the adapter");
        }
    }

    private static final ToolCatalog CATALOG = new ToolCatalog(List.of(
            new CatalogTool("disclosureSummary", List.of(ToolParameter.TICKER)),
            new CatalogTool("askDisclosures", List.of(ToolParameter.TICKER, ToolParameter.QUESTION))));

    private final ResearchToolCallbacks adapter = new ResearchToolCallbacks(CATALOG.definitions(), 2);

    @Test
    void exposesExactlyTheCatalogueToolsWithOnlyTickerAndQuestionParameters() {
        JsonMapper json = JsonMapper.builder().build();
        List<ToolCallback> callbacks = adapter.callbacks();

        assertThat(callbacks)
                .extracting(c -> c.getToolDefinition().name())
                .containsExactly("disclosureSummary", "askDisclosures");
        for (ToolCallback callback : callbacks) {
            assertThat(callback.getToolDefinition().description())
                    .isEqualTo("fixed description of "
                            + callback.getToolDefinition().name());
            JsonNode schema = json.readTree(callback.getToolDefinition().inputSchema());
            Set<String> properties = new HashSet<>();
            schema.get("properties").properties().forEach(p -> properties.add(p.getKey()));
            assertThat(properties).isSubsetOf("ticker", "question").contains("ticker");
            assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
        }
    }

    @Test
    void aCallGoesToTheToolsOfTheRunNamedInTheToolContext() {
        UUID run = UUID.randomUUID();
        var tools = FakeRunTools.withEvidence();
        try (var binding = adapter.bind(run, tools)) {
            String result = adapter.callbacks()
                    .get(0)
                    .call("{\"ticker\":\"THYAO\"}", new ToolContext(adapter.toolContext(run)));

            assertThat(result).startsWith("<tool_data>");
            assertThat(tools.calls)
                    .containsExactly(new FakeRunTools.Call("disclosureSummary", "{\"ticker\":\"THYAO\"}"));
            assertThat(binding.calls()).isEqualTo(1);
        }
    }

    @Test
    void withoutAToolContextOrABoundRunNothingIsCalled() {
        UUID run = UUID.randomUUID();
        var tools = FakeRunTools.withEvidence();
        ToolCallback callback = adapter.callbacks().get(0);
        try (var binding = adapter.bind(run, tools)) {
            assertThat(callback.call("{\"ticker\":\"THYAO\"}")).isEqualTo(ToolMessages.TOOL_FAILED);
            assertThat(callback.call("{}", new ToolContext(Map.of(ResearchToolCallbacks.RUN_ID, "not-a-uuid"))))
                    .isEqualTo(ToolMessages.TOOL_FAILED);
            assertThat(callback.call("{}", new ToolContext(adapter.toolContext(UUID.randomUUID()))))
                    .isEqualTo(ToolMessages.TOOL_FAILED);
            assertThat(tools.calls).isEmpty();
        }
        // unbound after close
        assertThat(callback.call("{}", new ToolContext(adapter.toolContext(run))))
                .isEqualTo(ToolMessages.TOOL_FAILED);
    }

    @Test
    void theCallAfterTheLimitEndsTheLoopInCode() {
        UUID run = UUID.randomUUID();
        var tools = FakeRunTools.withEvidence();
        ToolContext context = new ToolContext(adapter.toolContext(run));
        try (var binding = adapter.bind(run, tools)) {
            adapter.callbacks().get(0).call("{\"ticker\":\"THYAO\"}", context);
            adapter.callbacks().get(1).call("{\"ticker\":\"THYAO\",\"question\":\"why?\"}", context);

            assertThatThrownBy(() -> adapter.callbacks().get(0).call("{\"ticker\":\"THYAO\"}", context))
                    .isInstanceOf(ResearchLimitReachedException.class);
            assertThat(tools.calls).hasSize(2);
        }
    }

    @Test
    void aRunCanBeBoundOnlyOnceAtATime() {
        UUID run = UUID.randomUUID();
        try (var binding = adapter.bind(run, FakeRunTools.withEvidence())) {
            assertThatThrownBy(() -> adapter.bind(run, FakeRunTools.withEvidence()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
