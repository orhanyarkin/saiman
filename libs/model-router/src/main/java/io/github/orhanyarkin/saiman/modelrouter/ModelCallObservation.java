package io.github.orhanyarkin.saiman.modelrouter;

/**
 * Names of the router's per-round-trip observation ({@code saiman.model.call}) and its key values,
 * for callers that listen to it (for example to record a run's LLM cost). One place, so a rename
 * here is a compile error there instead of a silent miss.
 */
public final class ModelCallObservation {

    /** The observation name, one per model round trip. */
    public static final String NAME = "saiman.model.call";

    /** High cardinality: the round trip's cost in USD micro-dollars. */
    public static final String COST_USD_MICROS = "saiman.cost.usd_micros";

    /** High cardinality: input tokens. */
    public static final String TOKENS_IN = "tokens.in";

    /** High cardinality: output tokens. */
    public static final String TOKENS_OUT = "tokens.out";

    /** High cardinality: the caller's cost scope ({@link RouterAdvisorParams#COST_SCOPE}). */
    public static final String COST_SCOPE = "saiman.cost.scope";

    private ModelCallObservation() {}
}
