package io.github.orhanyarkin.saiman.orchestrator.tool;

import io.github.orhanyarkin.saiman.shared.run.DenyReason;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The fixed strings a model gets back instead of a tool result. Nothing from an exception, the
 * seller or the model's own input is ever part of one (Spring AI would feed an exception's message
 * to the model; the gateway never lets one escape).
 */
public final class ToolMessages {

    public static final String UNKNOWN_TOOL = "ERROR unknown_tool: no tool has this name. Nothing was paid.";
    public static final String INVALID_ARGUMENTS =
            "ERROR invalid_arguments: use exactly the documented parameters. Nothing was paid.";
    public static final String UNKNOWN_TICKER =
            "ERROR unknown_ticker: the ticker is not in the seller's catalogue. Nothing was paid.";
    public static final String CATALOGUE_UNAVAILABLE =
            "ERROR catalogue_unavailable: the ticker catalogue could not be checked. Nothing was paid.";
    public static final String TOOL_CALL_LIMIT =
            "ERROR tool_call_limit: this run may make no more tool calls. Nothing was paid.";
    public static final String OUTCOME_UNKNOWN =
            "ERROR payment_outcome_unknown: the payment may have gone through; do not repeat this call.";
    public static final String SELLER_UNAVAILABLE =
            "ERROR tool_unavailable: the seller could not answer. Nothing was paid.";
    public static final String UNUSABLE_RESULT =
            "ERROR unusable_result: the seller's answer could not be used. Do not repeat this call.";
    public static final String NO_STORED_RESULT =
            "ERROR result_unavailable: this call was already paid and its answer was unusable. Do not repeat it.";
    public static final String TOOL_FAILED = "ERROR tool_failed: the tool call failed.";

    private static final Map<DenyReason, String> DENIED = denied();

    private ToolMessages() {}

    /** The fixed message for a refused payment. */
    public static String denied(DenyReason reason) {
        return Objects.requireNonNull(DENIED.get(reason));
    }

    /** Every fixed message (for tests: a tool output is one of these or a {@code <tool_data>} block). */
    public static Set<String> all() {
        Set<String> all = new HashSet<>(DENIED.values());
        all.addAll(Set.of(
                UNKNOWN_TOOL,
                INVALID_ARGUMENTS,
                UNKNOWN_TICKER,
                CATALOGUE_UNAVAILABLE,
                TOOL_CALL_LIMIT,
                OUTCOME_UNKNOWN,
                SELLER_UNAVAILABLE,
                UNUSABLE_RESULT,
                NO_STORED_RESULT,
                TOOL_FAILED));
        return Set.copyOf(all);
    }

    private static Map<DenyReason, String> denied() {
        Map<DenyReason, String> map = new EnumMap<>(DenyReason.class);
        for (DenyReason reason : DenyReason.values()) {
            map.put(
                    reason,
                    "ERROR payment_denied: " + reason.name()
                            + ". The spend-control plane refused this payment and nothing was paid; do not retry it.");
        }
        return map;
    }
}
