/**
 * Payment intents and the paying seller client. Every paid call starts as a {@code payment_intent}
 * row created by code (with a random idempotency key), so only requests the orchestrator itself
 * built can ever be paid (ADR-0013); the {@link
 * io.github.orhanyarkin.saiman.orchestrator.payment.PaidResourceClient} sends them to the configured
 * seller only.
 */
@NullMarked
package io.github.orhanyarkin.saiman.orchestrator.payment;

import org.jspecify.annotations.NullMarked;
