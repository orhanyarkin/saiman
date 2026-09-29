/**
 * The buyer side of this starter: a {@code RestClient} payment interceptor and the spend-control
 * hook it calls before signing.
 *
 * <p>{@link io.github.orhanyarkin.x402.client.X402PaymentInterceptor} is opt-in — applications add
 * it to a specific {@code RestClient.Builder} themselves; this starter never attaches it globally.
 * {@link io.github.orhanyarkin.x402.client.SpendGuard#reserve(io.github.orhanyarkin.x402.client.PaymentIntent)}
 * always runs before any signing (rule 3 in {@code CLAUDE.md}: spend limits are deterministic code,
 * never a prompt instruction, and live outside the signature). {@link
 * io.github.orhanyarkin.x402.client.PropertiesSpendGuard} is the M1 default; M3 replaces it with a
 * Valkey/Postgres-backed implementation without changing {@link
 * io.github.orhanyarkin.x402.client.SpendGuard}'s contract.
 */
@NullMarked
package io.github.orhanyarkin.x402.client;

import org.jspecify.annotations.NullMarked;
