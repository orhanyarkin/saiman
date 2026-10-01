package io.github.orhanyarkin.x402.core;

import org.jspecify.annotations.Nullable;

/**
 * The x402 v2 payment flows this starter implements (specification v2.0, section 6.1, the
 * protocol-reserved {@code extra.paymentFlow} key of {@link PaymentRequirements}).
 *
 * <ul>
 *   <li>{@link #AUTHORIZATION} (the default, and what an absent {@code paymentFlow} means): the
 *       server verifies the authorization, serves the resource and settles afterwards, only if it
 *       served a 2xx.
 *   <li>{@link #UPFRONT}: the server verifies and settles <em>before</em> the resource is produced.
 *       If the resource then cannot be served, the money has already moved; the buyer receives the
 *       failure status together with the settlement ({@code PAYMENT-RESPONSE}) and the seller owes
 *       it a credit (ADR-0021).
 * </ul>
 *
 * The third flow of the specification, {@code escrow}, is not supported: an offer naming it (or
 * any unknown value) is rejected by {@link TestnetAssets#requireSupported(PaymentRequirements)}.
 */
public enum PaymentFlow {

    /** Verify, serve, then settle (spec default). */
    AUTHORIZATION("authorization"),

    /** Verify and settle, then serve. */
    UPFRONT("upfront");

    /** The {@code extra} key that carries the flow on the wire. */
    public static final String EXTRA_KEY = "paymentFlow";

    private final String wireValue;

    PaymentFlow(String wireValue) {
        this.wireValue = wireValue;
    }

    /** The value of {@code extra.paymentFlow} for this flow. */
    public String wireValue() {
        return wireValue;
    }

    /**
     * The flow named by an {@code extra.paymentFlow} value: {@link #AUTHORIZATION} when absent,
     * {@code null} for a value this starter does not implement (e.g. {@code escrow}).
     */
    public static @Nullable PaymentFlow fromWireValue(@Nullable String value) {
        if (value == null) {
            return AUTHORIZATION;
        }
        for (PaymentFlow flow : values()) {
            if (flow.wireValue.equals(value)) {
                return flow;
            }
        }
        return null;
    }

    /** The flow an offer asks for, or {@code null} if it names one this starter does not implement. */
    public static @Nullable PaymentFlow of(PaymentRequirements requirements) {
        return fromWireValue(requirements.extraString(EXTRA_KEY));
    }
}
