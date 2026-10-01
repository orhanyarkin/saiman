package io.github.orhanyarkin.saiman.orchestrator.agent;

import io.github.orhanyarkin.saiman.orchestrator.budget.SpendProperties;
import io.github.orhanyarkin.saiman.orchestrator.tool.ToolCatalog;
import io.github.orhanyarkin.saiman.orchestrator.tool.ToolDefinition;
import io.github.orhanyarkin.saiman.orchestrator.tool.ToolMessages;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The research tools as Spring AI {@link ToolCallback}s, generated from the {@link ToolCatalog}: one
 * callback per {@code ResearchTool}, with its fixed description and the code-generated input schema
 * (only {@code ticker} and {@code question}). Nothing here names a transport.
 *
 * <p>The callbacks are shared singletons. Which run a call belongs to travels in Spring AI's {@link
 * ToolContext} under {@link #RUN_ID}: the tool context is passed to the callback but never sent to
 * the model, so the model can neither see nor choose the run. A call resolves the run's {@link
 * RunTools} bound by {@link #bind} and goes through it (in production the paid-tool gateway).
 *
 * <p>The researcher's tool loop is bounded here too: the call after {@code max-tool-calls-per-run}
 * throws {@link ResearchLimitReachedException}, which Spring AI does not feed back to the model (it
 * only converts {@code ToolExecutionException}s), so the loop ends in code, not by the model's choice.
 */
@Component
public class ResearchToolCallbacks {

    /** The tool-context key of the run id. */
    public static final String RUN_ID = "saiman.run-id";

    private final List<ToolCallback> callbacks;
    private final Map<UUID, Binding> bindings = new ConcurrentHashMap<>();
    private final int maxCallsPerRun;

    @Autowired
    public ResearchToolCallbacks(ToolCatalog catalog, SpendProperties spend) {
        this(catalog.definitions(), spend.maxToolCallsPerRun());
    }

    ResearchToolCallbacks(List<ToolDefinition> definitions, int maxCallsPerRun) {
        if (maxCallsPerRun <= 0) {
            throw new IllegalArgumentException("maxCallsPerRun must be positive");
        }
        this.maxCallsPerRun = maxCallsPerRun;
        this.callbacks = definitions.stream()
                .<ToolCallback>map(definition -> new Callback(definition, this))
                .toList();
    }

    /** One callback per catalogue tool, in catalogue order. */
    public List<ToolCallback> callbacks() {
        return callbacks;
    }

    /** The tool context that ties the callbacks of one {@code ChatClient} call to {@code runId}. */
    public Map<String, Object> toolContext(UUID runId) {
        return Map.of(RUN_ID, runId.toString());
    }

    /** Binds a run's tools for the duration of its research; close to unbind. */
    public Binding bind(UUID runId, RunTools tools) {
        Binding binding = new Binding(runId, tools);
        if (bindings.putIfAbsent(runId, binding) != null) {
            throw new IllegalStateException("the run's tools are already bound");
        }
        return binding;
    }

    /** A run's tools, bound while the run executes. */
    public final class Binding implements AutoCloseable {
        private final UUID runId;
        private final RunTools tools;
        private final AtomicInteger calls = new AtomicInteger();

        private Binding(UUID runId, RunTools tools) {
            this.runId = runId;
            this.tools = tools;
        }

        /** Tool calls the model attempted in this run so far. */
        public int calls() {
            return calls.get();
        }

        @Override
        public void close() {
            bindings.remove(runId, this);
        }
    }

    private String call(String toolName, String arguments, @Nullable ToolContext context) {
        Binding binding =
                context == null ? null : bindingOf(context.getContext().get(RUN_ID));
        if (binding == null) {
            return ToolMessages.TOOL_FAILED;
        }
        if (binding.calls.incrementAndGet() > maxCallsPerRun) {
            throw new ResearchLimitReachedException();
        }
        return binding.tools.call(toolName, arguments);
    }

    private @Nullable Binding bindingOf(@Nullable Object runId) {
        if (!(runId instanceof String id)) {
            return null;
        }
        try {
            return bindings.get(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The adapter: Spring AI's tool contract over one catalogue entry. */
    private static final class Callback implements ToolCallback {
        private final org.springframework.ai.tool.definition.ToolDefinition definition;
        private final ResearchToolCallbacks owner;

        Callback(ToolDefinition definition, ResearchToolCallbacks owner) {
            this.definition =
                    new DefaultToolDefinition(definition.name(), definition.description(), definition.inputSchema());
            this.owner = owner;
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            // Without a tool context there is no run: refuse, never guess one.
            return owner.call(definition.name(), toolInput, null);
        }

        @Override
        public String call(String toolInput, @Nullable ToolContext toolContext) {
            return owner.call(definition.name(), toolInput, toolContext);
        }
    }
}
