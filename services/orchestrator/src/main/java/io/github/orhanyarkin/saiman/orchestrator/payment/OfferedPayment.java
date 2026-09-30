package io.github.orhanyarkin.saiman.orchestrator.payment;

/**
 * The payment a seller's 402 asked for, as selected by the x402 interceptor. Untrusted input: the
 * spend guard decides whether it is paid.
 *
 * @param amountAtomic USDC atomic units
 * @param payTo payee address, lower-cased
 */
public record OfferedPayment(long amountAtomic, String payTo, String network, String asset) {}
