package io.github.orhanyarkin.x402.observation;

import io.github.orhanyarkin.x402.core.AssetAmount;
import io.github.orhanyarkin.x402.facilitator.FacilitatorReason;
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
 * and the {@code x402.payments.paid_not_served} and {@code x402.payment.paid_not_served.amount}
 * counters from the same {@code
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
    static final String PAID_NOT_SERVED_COUNTER_NAME = "x402.payments.paid_not_served";
    static final String PAID_NOT_SERVED_AMOUNT_COUNTER_NAME = "x402.payment.paid_not_served.amount";

    /**
     * Bounded (closed {@link FacilitatorReason} set). Present on every {@code x402.payments}
     * series -- {@code none} when settled -- because Prometheus rejects one meter name registered
     * with different tag keys.
     */
    static final String REASON_TAG = "reason";

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
                .tag(REASON_TAG, FacilitatorReason.NONE.code())
                .register(registry)
                .increment();
        DistributionSummary.builder(PAYMENT_AMOUNT_SUMMARY_NAME)
                .baseUnit("usdc_atomic")
                .register(registry)
                .record((double)
                        AssetAmount.parse(event.requirements().amount()).atomicUnits());
    }

    /**
     * Upfront flow, paid but not served: {@code x402.payments.paid_not_served{network}} plus the
     * amount in {@code x402.payment.paid_not_served.amount} (atomic units). The payment was already
     * counted once in {@code x402.payments{outcome=settled}} and {@code x402.payment.amount} by the
     * settled event, so this is a separate counter -- requests the seller now owes a credit for --
     * and never a second {@code x402.payments} outcome: summing that counter's outcomes still
     * counts each payment exactly once.
     */
    @EventListener
    public void onPaidRequestFailed(X402PaidRequestFailedEvent event) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry == null) {
            return;
        }
        Counter.builder(PAID_NOT_SERVED_COUNTER_NAME)
                .tag("network", event.requirements().network())
                .description("Upfront-settled requests that were then not served")
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
                .tag(REASON_TAG, FacilitatorReason.fromCode(event.errorReason()).code())
                .register(registry)
                .increment();
    }
}
