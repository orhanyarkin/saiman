package io.github.orhanyarkin.x402.observation;

/**
 * Names shared between the payment {@link io.micrometer.observation.Observation} created around
 * verify/settle and {@link X402RedactingObservationFilter}, so both sides agree on exactly what a
 * "payment observation" looks like (ADR-0006 amendment).
 */
public final class X402ObservationKeys {

    /** The {@link io.micrometer.observation.Observation} name for one payment attempt. */
    public static final String OBSERVATION_NAME = "x402.server.payment";

    /** Low cardinality: CAIP-2 network identifier, e.g. {@code "eip155:84532"}. */
    public static final String NETWORK = "x402.network";

    /** Low cardinality: payment scheme, e.g. {@code "exact"}. */
    public static final String SCHEME = "x402.scheme";

    /** Low cardinality: token contract address. */
    public static final String ASSET = "x402.asset";

    /** Low cardinality: how the payment attempt concluded, e.g. {@code "settled"}, {@code "replayed"}. */
    public static final String OUTCOME = "x402.outcome";

    /** High cardinality: the payer's wallet address (public on chain). */
    public static final String PAYER = "x402.payer";

    /** High cardinality: the settlement transaction hash (public on chain). */
    public static final String TX_HASH = "x402.tx_hash";

    private X402ObservationKeys() {}
}
