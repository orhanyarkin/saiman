package io.github.orhanyarkin.x402.observation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import io.github.orhanyarkin.x402.server.X402PaidRequestFailedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Upfront flow: a paid-but-not-served request is counted with its amount, separately from x402.payment.amount. */
class X402PaidNotServedMetricsTests {

    private static final PaymentRequirements OFFER = new PaymentRequirements(
            TestnetAssets.SCHEME_EXACT,
            TestnetAssets.NETWORK,
            "20000",
            TestnetAssets.USDC_ADDRESS,
            "0x1111111111111111111111111111111111111111",
            60,
            Map.of("name", TestnetAssets.USDC_NAME, "version", TestnetAssets.USDC_VERSION, "paymentFlow", "upfront"));

    @Test
    void paidNotServedCountsTheOutcomeAndTheAmountOwed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("meterRegistry", registry);
        X402PaymentMetricsListener listener =
                new X402PaymentMetricsListener(beans.getBeanProvider(MeterRegistry.class));

        listener.onPaidRequestFailed(new X402PaidRequestFailedEvent(
                UUID.randomUUID(),
                "/paid",
                OFFER,
                "0x2222222222222222222222222222222222222222",
                "0x" + "1".repeat(64),
                "20000",
                "9999999999",
                "0x2222222222222222222222222222222222222222",
                "0x" + "a".repeat(64),
                503,
                "handler_server_error",
                Instant.now()));

        assertThat(registry.get(X402PaymentMetricsListener.PAID_NOT_SERVED_COUNTER_NAME)
                        .tag("network", TestnetAssets.NETWORK)
                        .counter()
                        .count())
                .isEqualTo(1.0);
        // The settled event already counted the payment in x402.payments: no second outcome there,
        // so summing x402.payments over outcomes counts each payment once.
        assertThat(registry.find(X402PaymentMetricsListener.PAYMENTS_COUNTER_NAME)
                        .counters())
                .isEmpty();
        assertThat(registry.get(X402PaymentMetricsListener.PAID_NOT_SERVED_AMOUNT_COUNTER_NAME)
                        .counter()
                        .count())
                .isEqualTo(20000.0);
        // The settled event already recorded the money once; this one does not record it again.
        assertThat(registry.find(X402PaymentMetricsListener.PAYMENT_AMOUNT_SUMMARY_NAME)
                        .summary())
                .isNull();
    }
}
