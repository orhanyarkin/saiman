package io.github.orhanyarkin.saiman.modelrouter;

import io.github.orhanyarkin.saiman.shared.money.Money;

/**
 * A model-cost budget per scope (one agent run), enforced by reservation like {@link CostGuard}:
 * {@link #reserve} is an atomic check-and-add against the scope's budget, {@link #settle} corrects
 * it to the actual cost. The scope budget is pinned by the first reservation of a scope, so a later
 * call cannot raise it. Scoped guards are optional: the router works with the global day cap alone.
 *
 * <p>Fails closed: a store that cannot answer means the call is not made.
 */
public interface ScopedCostGuard {

    /** A reservation against one scope. */
    record ScopeReservation(String scopeId, Money estimate) {}

    /**
     * Atomically adds {@code estimate} to the scope's spend unless that would exceed {@code budget}
     * (the budget of the first reservation of the scope wins).
     *
     * @throws ScopeBudgetExceededException if the scope's spend plus {@code estimate} would exceed it
     * @throws IllegalArgumentException for a scope id that is not {@code [A-Za-z0-9_-]{1,64}}
     */
    ScopeReservation reserve(String scopeId, Money estimate, Money budget);

    /** Replaces the reservation by the actual cost; never refuses, never goes below zero. */
    void settle(String scopeId, ScopeReservation reservation, Money actual);

    /** Gives a reservation back completely. */
    default void release(String scopeId, ScopeReservation reservation) {
        settle(scopeId, reservation, reservation.estimate().zero());
    }

    /** The scope's spend so far: settled costs plus outstanding reservations. */
    Money spent(String scopeId);

    /** Accepts only ids that are safe inside a store key. */
    static void requireValidScopeId(String scopeId) {
        if (scopeId == null || !scopeId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("cost scope id must match [A-Za-z0-9_-]{1,64}");
        }
    }
}
