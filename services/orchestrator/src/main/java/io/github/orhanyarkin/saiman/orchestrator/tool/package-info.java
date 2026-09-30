/**
 * Research tools and the paid-tool gateway (ADR-0013, ADR-0014). A {@link
 * io.github.orhanyarkin.saiman.orchestrator.tool.ResearchTool} owns only its transport; {@link
 * io.github.orhanyarkin.saiman.orchestrator.tool.PaidToolGateway} owns validation, limits, dedupe,
 * approvals, sanitising and events, and never lets an exception reach a model. Every tool result
 * is untrusted data.
 */
@NullMarked
package io.github.orhanyarkin.saiman.orchestrator.tool;

import org.jspecify.annotations.NullMarked;
