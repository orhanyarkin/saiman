package io.github.orhanyarkin.saiman.orchestrator.tool;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Every {@link ResearchTool} bean, by name. A new transport (MCP, Stretch) is one more bean; the
 * gateway does not change. Refuses to start on a duplicate name or a malformed one.
 */
@Component
public class ToolCatalog {

    private static final Pattern NAME = Pattern.compile("[a-zA-Z]{1,40}");

    private final Map<String, ResearchTool> tools;
    private final List<ToolDefinition> definitions;

    public ToolCatalog(List<ResearchTool> tools) {
        Map<String, ResearchTool> byName = new LinkedHashMap<>();
        for (ResearchTool tool : tools) {
            if (!NAME.matcher(tool.name()).matches()) {
                throw new IllegalStateException("research tool names must be 1-40 ASCII letters");
            }
            if (tool.parameters().isEmpty()
                    || new HashSet<>(tool.parameters()).size()
                            != tool.parameters().size()
                    || !tool.parameters().contains(ToolParameter.TICKER)) {
                throw new IllegalStateException("research tool " + tool.name() + " must take a ticker, once");
            }
            if (byName.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalStateException("duplicate research tool " + tool.name());
            }
        }
        this.tools = Map.copyOf(byName);
        this.definitions = byName.values().stream().map(ToolDefinition::of).toList();
    }

    public Optional<ResearchTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Set<String> names() {
        return tools.keySet();
    }

    /** What the model is told about the tools, in registration order. */
    public List<ToolDefinition> definitions() {
        return definitions;
    }
}
