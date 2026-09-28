package io.github.orhanyarkin.x402.core;

/**
 * A network or asset was requested that this starter does not support.
 *
 * <p>This starter is locked to Base Sepolia testnet USDC (ADR-0008): there is no configuration
 * property for network or asset. Thrown by {@link TestnetAssets#requireSupported(PaymentRequirements)}.
 * {@link #getMessage()} never includes the rejected value, only what this starter supports: the
 * network/asset are address-like identifiers, and the {@code extra.*} fields are
 * attacker-influenceable scheme tokens, and validation-failure messages should not repeat
 * rejected input verbatim (it tends to resurface in logs or bind-failure reports further up the
 * stack).
 */
public final class UnsupportedPaymentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnsupportedPaymentException(String message) {
        super(message);
    }
}
