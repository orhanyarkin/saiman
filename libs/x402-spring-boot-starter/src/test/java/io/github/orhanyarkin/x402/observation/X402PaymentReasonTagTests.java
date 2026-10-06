package io.github.orhanyarkin.x402.observation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import io.github.orhanyarkin.x402.facilitator.FacilitatorReason;
import io.github.orhanyarkin.x402.server.X402PaymentFailedEvent;
import io.github.orhanyarkin.x402.server.X402PaymentSettledEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** The bounded {@code reason} tag on {@code x402.payments}. */
class X402PaymentReasonTagTests {

    private static final String ADDRESS = "0x2222222222222222222222222222222222222222";
    private static final PaymentRequirements OFFER = new PaymentRequirements(
            TestnetAssets.SCHEME_EXACT,
            TestnetAssets.NETWORK,
            "10000",
            TestnetAssets.USDC_ADDRESS,
            "0x1111111111111111111111111111111111111111",
            60,
            Map.of("name", TestnetAssets.USDC_NAME, "version", TestnetAssets.USDC_VERSION));

    private static X402PaymentFailedEvent failed(@Nullable String reason) {
        return new X402PaymentFailedEvent(
                UUID.randomUUID(),
                "/paid",
                OFFER,
                ADDRESS,
                "0x" + "1".repeat(64),
                "10000",
                "9999999999",
                ADDRESS,
                reason,
                Instant.now());
    }

    @Test
    void failedCounterCarriesTheKnownReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        X402PaymentMetricsListener listener = new X402PaymentMetricsListener(fixed(registry));

        listener.onFailed(failed("invalid_exact_evm_transaction_failed"));

        assertThat(registry.get("x402.payments")
                        .tag("outcome", "failed")
                        .tag("reason", "invalid_exact_evm_transaction_failed")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void unknownReasonsCollapseToOtherAndAbsentToNone() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        X402PaymentMetricsListener listener = new X402PaymentMetricsListener(fixed(registry));
        Random random = new Random(3);

        for (int i = 0; i < 1_000; i++) {
            listener.onFailed(failed("code_" + random.nextLong()));
        }
        listener.onFailed(failed("unrecognised"));
        listener.onFailed(failed(null));

        long series = registry.find("x402.payments").meters().size();
        assertThat(series)
                .as("one series per bounded reason, never per raw code")
                .isEqualTo(2);
        assertThat(registry.get("x402.payments")
                        .tag("reason", "other")
                        .counter()
                        .count())
                .isEqualTo(1_001.0);
        assertThat(registry.get("x402.payments").tag("reason", "none").counter().count())
                .isEqualTo(1.0);
        assertThat(FacilitatorReason.values().length).isGreaterThan(2);
    }

    @Test
    void settledCounterKeepsItsTagsAndHasReasonNoneForUniformTagKeys() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        X402PaymentMetricsListener listener = new X402PaymentMetricsListener(fixed(registry));

        listener.onSettled(new X402PaymentSettledEvent(
                UUID.randomUUID(),
                "/paid",
                OFFER,
                ADDRESS,
                "0x" + "1".repeat(64),
                "10000",
                "9999999999",
                ADDRESS,
                "0x" + "a".repeat(64),
                Instant.now()));
        listener.onFailed(failed("insufficient_funds"));

        assertThat(registry.get("x402.payments")
                        .tag("network", TestnetAssets.NETWORK)
                        .tag("outcome", "settled")
                        .tag("reason", "none")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(registry.find("x402.payments").meters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .extracting(tag -> tag.getKey())
                        .containsExactlyInAnyOrder("network", "outcome", "reason"));
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
