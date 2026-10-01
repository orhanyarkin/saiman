package io.github.orhanyarkin.x402.client;

import io.github.orhanyarkin.x402.core.Eip3009Authorization;
import io.github.orhanyarkin.x402.core.SettlementResponse;

/**
 * The spend-control plane's decision point for outgoing x402 payments: deterministic code, not an
 * LLM instruction (rule 3 in {@code CLAUDE.md}). {@link X402PaymentInterceptor} calls {@link
 * #reserve(PaymentIntent)} before any signing happens, so a denied payment never produces a
 * signature.
 *
 * <p>{@link PropertiesSpendGuard} is the M1 default: a per-request maximum and a payee allowlist,
 * both read from {@code x402.client.*} properties, plus in-memory idempotency-key dedupe. M3
 * replaces it with a bean backed by Redis (fast budget checks) and Postgres (durable,
 * ledger-linked reservations); this interface does not change.
 */
public interface SpendGuard {

    /**
     * Authorizes {@code intent}, or refuses it.
     *
     * @throws SpendDeniedException if the payment is over budget, the payee is not allowed, or
     *     {@code intent.idempotencyKey()} has already been used for a payment
     */
    SpendReservation reserve(PaymentIntent intent) throws SpendDeniedException;

    /**
     * Called by {@link X402PaymentInterceptor} after {@code reservation}'s payment was signed and
     * <b>before</b> the paid request is sent. A guard that tracks money in flight records what it
     * needs to reconcile the payment later (e.g. {@code from}, {@code nonce}, {@code validBefore}
     * for an {@code authorizationState(from, nonce)} query) here, durably, while nothing has left
     * the process yet.
     *
     * <p><b>Fail closed:</b> if this throws, the interceptor never sends the signed authorization;
     * it calls {@link #release} for {@code reservation} and rethrows. Implementations must not log
     * or store anything that would let a third party settle the authorization (this method is
     * never given the signature itself).
     *
     * <p>The default does nothing, which keeps existing guards source- and behaviour-compatible.
     *
     * @param reservation the reservation {@link #reserve} returned for this payment
     * @param authorization the signed EIP-3009 authorization about to be sent
     */
    default void signed(SpendReservation reservation, Eip3009Authorization authorization) {}

    /** Confirms {@code reservation}'s payment settled. */
    void commit(SpendReservation reservation, SettlementResponse settlement);

    /**
     * Releases {@code reservation}: its idempotency key becomes available for a fresh {@link
     * #reserve(PaymentIntent)} call.
     *
     * <p><b>Call this only when no signed authorization was ever sent to the server</b> — e.g. a
     * failure while building, signing or encoding the payment before the retry request left this
     * process. Once a signature has left the process, it is out of this process's control until
     * its {@code validBefore} expires: a hostile or merely slow/misbehaving server can still settle
     * it later even after answering this request with a 402 (our own server does exactly this on a
     * settlement failure or timeout). {@link X402PaymentInterceptor} therefore never calls this
     * after sending a signature, including when the paid retry itself comes back 402 — the
     * reservation is left reserved instead, so the same idempotency key can never be reused while
     * that authorization could still be settled. M3 reconciles such held reservations, e.g. via an
     * {@code authorizationState(from, nonce)} query against the token contract once {@code
     * validBefore} has passed.
     *
     * @param reason a short, non-sensitive diagnostic (never a header value or signature)
     */
    void release(SpendReservation reservation, String reason);
}
