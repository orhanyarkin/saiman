package io.github.orhanyarkin.x402.client;

import io.github.orhanyarkin.x402.core.AssetAmount;
import io.github.orhanyarkin.x402.core.SettlementResponse;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The M1 default {@link SpendGuard}: a per-request maximum, a payee allowlist, and in-memory
 * idempotency-key dedupe.
 *
 * <p>Every {@code Idempotency-Key} is tracked as a single entry in one {@link ConcurrentHashMap}:
 * absent (never used), {@link ReservationState#RESERVED} (a payment is in progress) or {@link
 * ReservationState#COMMITTED} (a payment already settled, permanently blocking reuse of that key —
 * rule 3 in {@code CLAUDE.md}: every paid call needs an idempotency key). {@link #reserve} inserts
 * the key with a single atomic {@link ConcurrentHashMap#putIfAbsent}; {@link #commit} and {@link
 * #release} use the atomic compare-and-swap overloads ({@link ConcurrentHashMap#replace(Object,
 * Object, Object)}, {@link ConcurrentHashMap#remove(Object, Object)}) rather than a separate
 * read-then-write, so the full "is this key free, and if so claim it" decision is one atomic
 * operation on one map — a two-collection design (a reserved set plus a separate committed set)
 * looks equivalent but is not: a thread's "not yet committed" read and its own insertion into the
 * reserved set are two separate operations, so a second thread's {@link #commit} can complete
 * (moving the key out of "reserved" and into "committed") in between them, letting the first thread
 * insert its own, now-stale reservation for an already-committed key. This is a real regression
 * found by a concurrent probe (200k iterations, 412 double reserve+commit); the single-map,
 * single-atomic-operation design here has no such window.
 *
 * <p>This state does not survive a restart and is not shared across instances; M3's
 * Valkey/Postgres-backed {@link SpendGuard} fixes both.
 */
public final class PropertiesSpendGuard implements SpendGuard {

    private enum ReservationState {
        RESERVED,
        COMMITTED
    }

    private final long maxAmountPerRequest;
    private final Set<String> allowedPayTo;
    private final ConcurrentHashMap<String, ReservationState> idempotencyKeyState = new ConcurrentHashMap<>();

    /**
     * @param maxAmountPerRequest the largest single payment, in atomic units, this guard allows
     * @param allowedPayTo the wallet addresses this guard allows paying (case-insensitive)
     * @throws IllegalArgumentException if {@code maxAmountPerRequest} is not positive, or {@code
     *     allowedPayTo} is null, empty, or contains an entry that is not a well-formed address
     *     (see {@link PayToAllowlist}); no rejected value is ever echoed
     */
    public PropertiesSpendGuard(long maxAmountPerRequest, List<String> allowedPayTo) {
        if (maxAmountPerRequest <= 0) {
            throw new IllegalArgumentException("maxAmountPerRequest must be a positive atomic amount");
        }
        this.maxAmountPerRequest = maxAmountPerRequest;
        this.allowedPayTo = Set.copyOf(PayToAllowlist.requireValidAndNormalize(allowedPayTo));
    }

    @Override
    public SpendReservation reserve(PaymentIntent intent) {
        Objects.requireNonNull(intent, "intent must not be null");

        long amount;
        try {
            amount = AssetAmount.parse(intent.requirements().amount()).atomicUnits();
        } catch (IllegalArgumentException e) {
            throw new SpendDeniedException("payment amount is not a valid atomic-unit value");
        }
        if (amount > maxAmountPerRequest) {
            throw new SpendDeniedException("payment amount exceeds the configured per-request maximum");
        }
        if (!allowedPayTo.contains(intent.requirements().payTo().toLowerCase(Locale.ROOT))) {
            throw new SpendDeniedException("payTo is not in the configured allowlist");
        }

        // Single atomic check-and-claim: absent -> RESERVED. Whether the key was already RESERVED
        // (a concurrent or duplicate in-flight attempt) or already COMMITTED (already paid), this
        // one call is the only thing that can decide "is this key free" -- see the class Javadoc.
        if (idempotencyKeyState.putIfAbsent(intent.idempotencyKey(), ReservationState.RESERVED) != null) {
            throw new SpendDeniedException(
                    "idempotency key is already reserved or has already been used for a payment");
        }
        return new SpendReservation(intent.idempotencyKey(), intent);
    }

    @Override
    public void commit(SpendReservation reservation, SettlementResponse settlement) {
        Objects.requireNonNull(reservation, "reservation must not be null");
        Objects.requireNonNull(settlement, "settlement must not be null");
        idempotencyKeyState.replace(
                reservation.idempotencyKey(), ReservationState.RESERVED, ReservationState.COMMITTED);
    }

    @Override
    public void release(SpendReservation reservation, String reason) {
        Objects.requireNonNull(reservation, "reservation must not be null");
        idempotencyKeyState.remove(reservation.idempotencyKey(), ReservationState.RESERVED);
    }
}
