package io.github.orhanyarkin.x402.core;

/**
 * HTTP header names carrying x402 v2 wire objects (specs/transports-v2/http.md).
 *
 * <p>Every value is base64-encoded JSON; see {@link X402Codec}.
 */
public final class X402Headers {

    /** Server → client: base64-encoded {@link PaymentRequired}, sent with HTTP 402. */
    public static final String PAYMENT_REQUIRED = "PAYMENT-REQUIRED";

    /** Client → server: base64-encoded {@link PaymentPayload}, sent on retry. */
    public static final String PAYMENT_SIGNATURE = "PAYMENT-SIGNATURE";

    /** Server → client: base64-encoded {@link SettlementResponse}, sent after settlement. */
    public static final String PAYMENT_RESPONSE = "PAYMENT-RESPONSE";

    private X402Headers() {}
}
