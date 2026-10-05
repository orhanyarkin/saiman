package io.github.orhanyarkin.saiman.ledger.query;

/**
 * The same JSON shape as {@link io.github.orhanyarkin.saiman.shared.money.Money}, but the atomic units may be
 * negative: for differences such as net revenue. Still an integer of atomic units, never a decimal.
 *
 * @param atomicUnits signed amount in the asset's smallest unit (a JSON integer)
 */
public record SignedAmount(long atomicUnits, String asset, int decimals) {}
