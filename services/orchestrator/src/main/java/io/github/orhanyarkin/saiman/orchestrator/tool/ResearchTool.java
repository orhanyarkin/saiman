package io.github.orhanyarkin.saiman.orchestrator.tool;

import java.util.List;

/**
 * A paid research tool (ADR-0014). Implementations own their transport (HTTP via {@code
 * PaidResourceClient} today; an MCP transport is one more implementation) and nothing else:
 * argument validation, limits, dedupe, approvals, sanitising and events all live in {@link
 * PaidToolGateway}, which never names a transport.
 */
public interface ResearchTool {

    /** The name the model calls the tool by, {@code [a-zA-Z]{1,40}}. */
    String name();

    /** A fixed description for the model. */
    String description();

    /** The tool's parameters, all required; a subset of {@link ToolParameter}. */
    List<ToolParameter> parameters();

    /**
     * Creates the payment intent for one validated invocation. No I/O to the seller happens here.
     *
     * @throws IllegalArgumentException if the arguments don't fit the tool (the gateway validated
     *     them already, so this is a second layer)
     */
    ToolCall prepare(ToolInvocation invocation);
}
