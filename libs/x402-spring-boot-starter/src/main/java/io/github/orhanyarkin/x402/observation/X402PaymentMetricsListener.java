package io.github.orhanyarkin.x402.observation;

import io.github.orhanyarkin.x402.core.AssetAmount;
import io.github.orhanyarkin.x402.server.X402PaidRequestFailedEvent;
import io.github.orhanyarkin.x402.server.X402PaymentFailedEvent;
import io.github.orhanyarkin.x402.server.X402PaymentSettledEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;

/**
 * Records the {@code x402.payments} counter, the {@code x402.payment.amount} distribution summary
 * and the {@code x402.payment.paid_not_served.amount} counter from the same {@code
 * X402PaymentSettledEvent}/{@code X402PaymentFailedEvent}/{@code X402PaidRequestFailedEvent}
 * events the server side already publishes.
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
    static final String PAID_NOT_SERVED_AMOUNT_COUNTER_NAME = "x402.payment.paid_not_served.amount";

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

    /**
     * Upfront flow, paid but not served: {@code x402.payments{outcome=paid_not_served}} plus the
     * amount in {@code x402.payment.paid_not_served.amount} (atomic units). The money moved and was
     * already recorded once in {@code x402.payment.amount} by the settled event, so it is counted
     * here separately -- the amount the seller now owes -- not a second time there.
     */
    @EventListener
    public void onPaidRequestFailed(X402PaidRequestFailedEvent event) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry == null) {
            return;
        }
        Counter.builder(PAYMENTS_COUNTER_NAME)
                .tag("network", event.requirements().network())
                .tag("outcome", "paid_not_served")
                .register(registry)
                .increment();
        Counter.builder(PAID_NOT_SERVED_AMOUNT_COUNTER_NAME)
                .baseUnit("usdc_atomic")
                .description("Atomic USDC settled up front for requests that were then not served")
                .register(registry)
                .increment((double)
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
