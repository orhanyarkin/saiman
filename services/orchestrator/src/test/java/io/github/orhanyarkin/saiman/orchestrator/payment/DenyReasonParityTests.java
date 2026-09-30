package io.github.orhanyarkin.saiman.orchestrator.payment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.orchestrator.spendtest.SpendTestSupport;
import io.github.orhanyarkin.saiman.shared.run.DenyReason;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Every {@link DenyReason} value fits the {@code payment_intent.deny_reason} CHECK (V3). */
class DenyReasonParityTests extends SpendTestSupport {

    @ParameterizedTest
    @EnumSource(DenyReason.class)
    void everyDenyReasonCanBeRecorded(DenyReason reason) {
        UUID run = createRun(50_000);
        PaymentIntentHandle handle = newIntent(run);

        intents.markDenied(handle.id(), reason, null);

        assertThat(intents.find(handle.id()).orElseThrow().denyReason()).isEqualTo(reason);
    }
}
