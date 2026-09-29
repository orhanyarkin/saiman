package io.github.orhanyarkin.x402.observation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import io.github.orhanyarkin.x402.server.X402PaymentFailedEvent;
import io.github.orhanyarkin.x402.server.X402PaymentSettledEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** {@code x402.payments} counter and {@code x402.payment.amount} distribution summary, against a real {@link SimpleMeterRegistry}. */
class X402PaymentMetricsListenerTests {

    private static final PaymentRequirements OFFER = new PaymentRequirements(
            TestnetAssets.SCHEME_EXACT,
            TestnetAssets.NETWORK,
            "10000",
            TestnetAssets.USDC_ADDRESS,
            "0x1111111111111111111111111111111111111111",
            60,
            Map.of("name", TestnetAssets.USDC_NAME, "version", TestnetAssets.USDC_VERSION));

    @Test
    void settledEventRecordsACounterIncrementAndTheAmountDistribution() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        X402PaymentMetricsListener listener = new X402PaymentMetricsListener(fixed(registry));

        listener.onSettled(new X402PaymentSettledEvent(
                UUID.randomUUID(),
                "/paid",
                OFFER,
                "0x2222222222222222222222222222222222222222",
                "0x" + "1".repeat(64),
                "10000",
                "9999999999",
                "0x2222222222222222222222222222222222222222",
                "0x" + "a".repeat(64),
                Instant.now()));

        assertThat(registry.get(X402PaymentMetricsListener.PAYMENTS_COUNTER_NAME)
                        .tag("network", TestnetAssets.NETWORK)
                        .tag("outcome", "settled")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(registry.get(X402PaymentMetricsListener.PAYMENT_AMOUNT_SUMMARY_NAME)
                        .summary()
                        .totalAmount())
                .isEqualTo(10000.0);
    }

    @Test
    void failedEventRecordsACounterIncrementOnly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        X402PaymentMetricsListener listener = new X402PaymentMetricsListener(fixed(registry));

        listener.onFailed(new X402PaymentFailedEvent(
                UUID.randomUUID(),
                "/paid",
                OFFER,
                "0x2222222222222222222222222222222222222222",
                "0x" + "1".repeat(64),
                "10000",
                "9999999999",
                "0x2222222222222222222222222222222222222222",
                "insufficient_funds",
                Instant.now()));

        assertThat(registry.get(X402PaymentMetricsListener.PAYMENTS_COUNTER_NAME)
                        .tag("network", TestnetAssets.NETWORK)
                        .tag("outcome", "failed")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void noRegistryAvailableIsANoOp() {
        X402PaymentMetricsListener listener = new X402PaymentMetricsListener(fixed(null));

        listener.onSettled(new X402PaymentSettledEvent(
                UUID.randomUUID(),
                "/paid",
                OFFER,
                "0x2222222222222222222222222222222222222222",
                "0x" + "1".repeat(64),
                "10000",
                "9999999999",
                "0x2222222222222222222222222222222222222222",
                "0x" + "a".repeat(64),
                Instant.now()));
        // No assertion needed beyond "did not throw": there is no registry to record onto.
    }

    private static ObjectProvider<MeterRegistry> fixed(@Nullable MeterRegistry value) {
        return new ObjectProvider<>() {
            @Override
            public MeterRegistry getObject() {
                return value;
            }

            @Override
            public MeterRegistry getObject(Object... args) {
                return value;
            }

            @Override
            public @Nullable MeterRegistry getIfAvailable() {
                return value;
            }

            @Override
            public @Nullable MeterRegistry getIfUnique() {
                return value;
            }
        };
    }
}
