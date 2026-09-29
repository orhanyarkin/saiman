package io.github.orhanyarkin.x402.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@link InMemoryPaymentNonceStore}: claim/replay/TTL-expiry/release-is-compare-and-delete, with a controllable clock. */
class InMemoryPaymentNonceStoreTests {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final InMemoryPaymentNonceStore store = new InMemoryPaymentNonceStore(clock);

    @AfterEach
    void closeStore() {
        store.close();
    }

    @Test
    void claimSucceedsOnceThenFailsForAnUnexpiredKey() {
        assertThat(store.claim("k", Duration.ofMinutes(1))).isNotNull();
        assertThat(store.claim("k", Duration.ofMinutes(1))).isNull();
    }

    @Test
    void claimSucceedsAgainAfterExpiry() {
        assertThat(store.claim("k", Duration.ofSeconds(10))).isNotNull();
        clock.advance(Duration.ofSeconds(11));
        assertThat(store.claim("k", Duration.ofSeconds(10))).isNotNull();
    }

    @Test
    void releaseAllowsAnImmediateReclaim() {
        String token = store.claim("k", Duration.ofMinutes(1));
        assertThat(token).isNotNull();
        store.release("k", token);
        assertThat(store.claim("k", Duration.ofMinutes(1))).isNotNull();
    }

    @Test
    void releaseIsANoOpForAWrongToken() {
        String token = store.claim("k", Duration.ofMinutes(1));
        assertThat(token).isNotNull();
        store.release("k", "not-the-real-token");
        assertThat(store.claim("k", Duration.ofMinutes(1))).isNull();
    }

    @Test
    void expiredClaimReclaimedBySomeoneElseIsNotDeletedByTheOriginalHoldersRelease() {
        String tokenA = store.claim("k", Duration.ofSeconds(10));
        assertThat(tokenA).isNotNull();

        clock.advance(Duration.ofSeconds(11));
        String tokenB = store.claim("k", Duration.ofMinutes(1));
        assertThat(tokenB).isNotNull().isNotEqualTo(tokenA);

        // A's stale release must not delete B's claim.
        store.release("k", tokenA);
        assertThat(store.claim("k", Duration.ofMinutes(1))).isNull();

        // B's own release still works.
        store.release("k", tokenB);
        assertThat(store.claim("k", Duration.ofMinutes(1))).isNotNull();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
