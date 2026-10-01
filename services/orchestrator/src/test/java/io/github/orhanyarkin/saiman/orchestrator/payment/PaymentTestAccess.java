package io.github.orhanyarkin.saiman.orchestrator.payment;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/** Test-only access to package-private payment internals. */
public final class PaymentTestAccess {

    private PaymentTestAccess() {}

    public static void resetCircuitBreaker(PaidResourceClient client) {
        ((X402PaidResourceClient) client).circuitBreaker().reset();
    }

    public static CircuitBreaker circuitBreaker(PaidResourceClient client) {
        return ((X402PaidResourceClient) client).circuitBreaker();
    }

    public static String idempotencyKey(PaymentIntentHandle handle) {
        return handle.idempotencyKey();
    }
}
