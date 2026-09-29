package io.github.orhanyarkin.x402.observation;

import io.github.orhanyarkin.x402.core.AssetAmount;
import io.github.orhanyarkin.x402.server.X402PaymentFailedEvent;
import io.github.orhanyarkin.x402.server.X402PaymentSettledEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;

/**
 * Records the {@code x402.payments} counter and {@code x402.payment.amount} distribution summary
 * from the same {@code X402PaymentSettledEvent}/{@code X402PaymentFailedEvent} events {@code
 * X402SettlementFilter} already publishes.
 *
 * <p>An event listener rather than code inside {@code X402SettlementFilter} itself: that class is
 * always loaded (it is not behind any {@code @ConditionalOnClass}), so it must never reference a
 * class that might genuinely be absent from an application's classpath (see {@code
 * X402ServerAutoConfiguration}'s Javadoc on the same JVM class-loading hazard). This class does
 * reference {@code io.micrometer.core.instrument.*} types directly, so it must only ever be
 * instantiated from behind {@code @ConditionalOnClass(MeterRegistry.class)} -- which the nested
 * configuration in {@code X402ObservationAutoConfiguration} that creates it enforces.
 *
 * <p>{@link ObjectProvider} rather than a direct {@link MeterRegistry} dependency: even with {@code
 * micrometer-core} on the classpath, an application need not have registered an actual {@link
 * MeterRegistry} bean (e.g. a minimal test); events are simply not recorded anywhere in that case,
 * rather than failing bean creation.
 */
public final class X402PaymentMetricsListener {

    static final String PAYMENTS_COUNTER_NAME = "x402.payments";
    static final String PAYMENT_AMOUNT_SUMMARY_NAME = "x402.payment.amount";

    private final ObjectProvider<MeterRegistry> meterRegistry;

    public X402PaymentMetricsListener(ObjectProvider<MeterRegistry> meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @EventListener
    public void onSettled(X402PaymentSettledEvent event) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry == null) {
            return;
        }
        Counter.builder(PAYMENTS_COUNTER_NAME)
                .tag("network", event.requirements().network())
                .tag("outcome", "settled")
                .register(registry)
                .increment();
        DistributionSummary.builder(PAYMENT_AMOUNT_SUMMARY_NAME)
                .baseUnit("usdc_atomic")
                .register(registry)
                .record((double)
                        AssetAmount.parse(event.requirements().amount()).atomicUnits());
    }

    @EventListener
    public void onFailed(X402PaymentFailedEvent event) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry == null) {
            return;
        }
        Counter.builder(PAYMENTS_COUNTER_NAME)
                .tag("network", event.requirements().network())
                .tag("outcome", "failed")
                .register(registry)
                .increment();
    }
}
