package io.github.orhanyarkin.x402.facilitator;

/**
 * One (version, scheme, network) combination a facilitator advertises support for, from {@code GET
 * /supported} (x402-foundation/x402 {@code specs/x402-specification-v2.md}, facilitator HTTP API).
 *
 * @param x402Version protocol version the facilitator supports for this combination
 * @param scheme payment scheme, e.g. {@code "exact"}
 * @param network CAIP-2 network identifier, e.g. {@code "eip155:84532"}
 */
public record SupportedKind(int x402Version, String scheme, String network) {}
