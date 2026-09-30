package io.github.orhanyarkin.saiman.orchestrator.tool;

import java.util.UUID;

/**
 * One validated call of a research tool, as the gateway hands it to {@link ResearchTool#prepare}.
 *
 * @param argsHash {@link ToolArguments#hash} for {@code tool}; the dedupe key
 */
public record ToolInvocation(UUID runId, String tool, ToolArguments arguments, String argsHash) {}
