package io.github.orhanyarkin.saiman.orchestrator.payment;

/** Test-only access to package-private payment internals. */
public final class PaymentTestAccess {

    private PaymentTestAccess() {}

    public static void resetCircuitBreaker(PaidResourceClient client) {
        ((X402PaidResourceClient) client).circuitBreaker().reset();
    }

    public static String idempotencyKey(PaymentIntentHandle handle) {
        return handle.idempotencyKey();
    }
}
