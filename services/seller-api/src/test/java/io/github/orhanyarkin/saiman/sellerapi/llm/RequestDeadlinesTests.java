package io.github.orhanyarkin.saiman.sellerapi.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The per-request budget: {@code min(deadline, validBefore - now - settleMargin)}. */
class RequestDeadlinesTests {

    private static final Duration DEADLINE = Duration.ofSeconds(25);
    private static final Duration SETTLE_MARGIN = Duration.ofSeconds(20); // 3 s connect + 12 s read + 5 s
    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    @Test
    void withoutAVerifiedPaymentTheConfiguredDeadlineApplies() {
        assertThat(RequestDeadlines.budget(DEADLINE, SETTLE_MARGIN, null, NOW)).isEqualTo(DEADLINE);
    }

    @Test
    void aLongAuthorizationKeepsTheConfiguredDeadline() {
        assertThat(RequestDeadlines.budget(DEADLINE, SETTLE_MARGIN, NOW.plusSeconds(60), NOW))
                .isEqualTo(DEADLINE);
        assertThat(RequestDeadlines.budget(DEADLINE, SETTLE_MARGIN, NOW.plusSeconds(45), NOW))
                .isEqualTo(DEADLINE);
    }

    @Test
    void aShortAuthorizationCutsTheDeadlineSoTheSettleStillFitsBeforeValidBefore() {
        // Verification took a while: 38 s of the window are left when the handler starts.
        assertThat(RequestDeadlines.budget(DEADLINE, SETTLE_MARGIN, NOW.plusSeconds(38), NOW))
                .isEqualTo(Duration.ofSeconds(18));
        assertThat(RequestDeadlines.budget(DEADLINE, SETTLE_MARGIN, NOW.plusMillis(20_500), NOW))
                .isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void anAuthorizationTooCloseToExpiryLeavesNoTimeAtAll() {
        assertThat(RequestDeadlines.budget(DEADLINE, SETTLE_MARGIN, NOW.plusSeconds(20), NOW))
                .isZero();
        assertThat(RequestDeadlines.budget(DEADLINE, SETTLE_MARGIN, NOW.plusSeconds(5), NOW))
                .isZero();
        assertThat(RequestDeadlines.budget(DEADLINE, SETTLE_MARGIN, NOW.minusSeconds(5), NOW))
                .isZero();
    }

    @Test
    void theProductionTimeoutsExactlyFitTheWindowAndLongerOnesDoNot() {
        assertThatNoException()
                .isThrownBy(() -> RequestDeadlines.requireFitsWindow(
                        Duration.ofSeconds(25),
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(12),
                        LlmRunProperties.MIN_AUTHORIZATION_WINDOW_SECONDS));
        assertThatThrownBy(() -> RequestDeadlines.requireFitsWindow(
                        Duration.ofMillis(25_001),
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(12),
                        LlmRunProperties.MIN_AUTHORIZATION_WINDOW_SECONDS))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RequestDeadlines.requireFitsWindow(
                        Duration.ofSeconds(25),
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(13),
                        LlmRunProperties.MIN_AUTHORIZATION_WINDOW_SECONDS))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theConnectTimeoutCountsTowardsTheWindowToo() {
        // 25 + 4 + 12 + 5 = 46 > 45: a slower connect alone is enough to fail the rule.
        assertThatThrownBy(() -> RequestDeadlines.requireFitsWindow(
                        Duration.ofSeconds(25),
                        Duration.ofSeconds(4),
                        Duration.ofSeconds(12),
                        LlmRunProperties.MIN_AUTHORIZATION_WINDOW_SECONDS))
                .isInstanceOf(IllegalStateException.class);
    }
}
