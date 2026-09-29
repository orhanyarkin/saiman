package io.github.orhanyarkin.saiman.modelrouter;

/**
 * Model tiers (ADR-0003). Embeddings are not a tier: they have their own method on {@link
 * ModelRouter}.
 */
public enum Tier {
    /** Routing, extraction, classification (low reasoning effort). */
    TIER0,
    /** Tool-using agent steps and cited answers. */
    TIER1,
    /** Hard runs and eval comparison. */
    TIER1_PREMIUM,
    /** Final synthesis and the eval judge (batch where possible). */
    TIER2
}
