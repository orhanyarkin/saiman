package io.github.orhanyarkin.saiman.orchestrator.budget;

import org.jspecify.annotations.Nullable;

/**
 * The one x402 client limit the dashboard shows. A bean of its own so that the read controller never
 * depends on the client properties, which also carry the buyer's private key.
 *
 * @param perRequestMaxAtomic the client's per-request ceiling in USDC atomic units, or null if unset
 */
record SpendLimitsView(@Nullable Long perRequestMaxAtomic) {}
