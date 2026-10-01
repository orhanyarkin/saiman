/**
 * Payment events (topics {@code payments.*.v1}, docs/events): what the buyer (orchestrator) and the seller
 * (seller-api) know about one EIP-3009 authorization. They carry the authorization's payer and nonce
 * (public on chain once used) but never the signature or an idempotency key.
 */
@NullMarked
package io.github.orhanyarkin.saiman.shared.payments;

import org.jspecify.annotations.NullMarked;
