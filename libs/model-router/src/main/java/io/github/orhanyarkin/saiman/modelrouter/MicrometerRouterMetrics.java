package io.github.orhanyarkin.saiman.modelrouter;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Micrometer implementation: {@code router.tokens{tier,direction}}, {@code
 * router.cost.usd_micros{tier}} and {@code router.calls{tier,outcome}} counters.
 */
final class MicrometerRouterMetrics implements RouterMetrics {

    private final MeterRegistry registry;

    MicrometerRouterMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void call(String tier, String outcome) {
        registry.counter("router.calls", "tier", tier, "outcome", outcome).increment();
    }

    @Override
    public void usage(String tier, long inputTokens, long outputTokens, long costUsdMicros) {
        registry.counter("router.tokens", "tier", tier, "direction", "in").increment((double) inputTokens);
        registry.counter("router.tokens", "tier", tier, "direction", "out").increment((double) outputTokens);
        registry.counter("router.cost.usd_micros", "tier", tier).increment((double) costUsdMicros);
    }
}
