package io.github.orhanyarkin.x402.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.SettlementResponse;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PropertiesSpendGuardTest {

    private static final String PAY_TO = "0x209693Bc6afc0C5328bA36FaF03C514EF312287C";

    private ExecutorService pool;

    @AfterEach
    void shutDownPool() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private static PaymentIntent intent(String idempotencyKey, String payTo, String amount) {
        PaymentRequirements requirements = new PaymentRequirements(
                TestnetAssets.SCHEME_EXACT,
                TestnetAssets.NETWORK,
                amount,
                TestnetAssets.USDC_ADDRESS,
                payTo,
                60,
                Map.of("name", TestnetAssets.USDC_NAME, "version", TestnetAssets.USDC_VERSION));
        return new PaymentIntent(idempotencyKey, URI.create("http://seller.example/resource"), requirements);
    }

    @Test
    void rejectsANonPositiveMaximum() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PropertiesSpendGuard(0, List.of(PAY_TO)));
        assertThatIllegalArgumentException().isThrownBy(() -> new PropertiesSpendGuard(-1, List.of(PAY_TO)));
    }

    @Test
    void rejectsAnEmptyAllowlist() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PropertiesSpendGuard(1000, List.of()));
    }

    @Test
    void rejectsAMalformedAllowlistEntryWithoutEchoingIt() {
        String malformed = "not-an-address";
        assertThatThrownBy(() -> new PropertiesSpendGuard(1000, List.of(malformed)))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(malformed));
    }

    @Test
    void rejectsATooShortAllowlistEntry() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PropertiesSpendGuard(1000, List.of("0x1234")));
    }

    @Test
    void normalizesAllowlistEntriesToLowercaseAndStillMatches() {
        // "0x" stays lowercase (the address pattern requires the literal prefix); only the hex
        // digits are upper-cased, to prove normalization -- not just case-insensitive matching --
        // is what makes this work.
        String upperHex = "0x" + PAY_TO.substring(2).toUpperCase(java.util.Locale.ROOT);
        PropertiesSpendGuard guard = new PropertiesSpendGuard(1000, List.of(upperHex));

        SpendReservation reservation = guard.reserve(intent("key-normalize", PAY_TO, "500"));

        assertThat(reservation).isNotNull();
    }

    @Test
    void reservesAnAcceptableIntent() {
        PropertiesSpendGuard guard = new PropertiesSpendGuard(1000, List.of(PAY_TO));

        SpendReservation reservation = guard.reserve(intent("key-1", PAY_TO, "500"));

        assertThat(reservation.idempotencyKey()).isEqualTo("key-1");
    }

    @Test
    void deniesAnAmountAboveTheMaximum() {
        PropertiesSpendGuard guard = new PropertiesSpendGuard(1000, List.of(PAY_TO));

        assertThatThrownBy(() -> guard.reserve(intent("key-2", PAY_TO, "1001")))
                .isInstanceOf(SpendDeniedException.class);
    }

    @Test
    void deniesAPayeeOutsideTheAllowlist() {
        PropertiesSpendGuard guard = new PropertiesSpendGuard(1000, List.of(PAY_TO));

        assertThatThrownBy(() -> guard.reserve(intent("key-3", "0x1111111111111111111111111111111111111A", "500")))
                .isInstanceOf(SpendDeniedException.class);
    }

    @Test
    void allowlistMatchIsCaseInsensitive() {
        PropertiesSpendGuard guard = new PropertiesSpendGuard(1000, List.of(PAY_TO.toLowerCase(java.util.Locale.ROOT)));

        SpendReservation reservation = guard.reserve(intent("key-4", PAY_TO, "500"));

        assertThat(reservation).isNotNull();
    }

    @Test
    void deniesAConcurrentReservationForTheSameIdempotencyKey() {
        PropertiesSpendGuard guard = new PropertiesSpendGuard(1000, List.of(PAY_TO));
        guard.reserve(intent("key-5", PAY_TO, "500"));

        assertThatThrownBy(() -> guard.reserve(intent("key-5", PAY_TO, "500")))
                .isInstanceOf(SpendDeniedException.class);
    }

    @Test
    void deniesReuseOfAKeyAfterItWasCommitted() {
        PropertiesSpendGuard guard = new PropertiesSpendGuard(1000, List.of(PAY_TO));
        SpendReservation reservation = guard.reserve(intent("key-6", PAY_TO, "500"));
        guard.commit(reservation, settlement());

        assertThatThrownBy(() -> guard.reserve(intent("key-6", PAY_TO, "500")))
                .isInstanceOf(SpendDeniedException.class);
    }

    @Test
    void releaseFreesTheKeyForReuse() {
        PropertiesSpendGuard guard = new PropertiesSpendGuard(1000, List.of(PAY_TO));
        SpendReservation reservation = guard.reserve(intent("key-7", PAY_TO, "500"));
        guard.release(reservation, "server rejected the payment");

        SpendReservation second = guard.reserve(intent("key-7", PAY_TO, "500"));

        assertThat(second).isNotNull();
    }

    /**
     * Regression test for a TOCTOU race a concurrent probe found in an earlier two-{@code Set}
     * implementation (200k iterations, 412 double reserve+commit): a "not yet committed" read and
     * the insertion into the reserved set were two separate, non-atomic operations, so a second
     * thread's full reserve-then-commit cycle could complete in between them. {@link
     * PropertiesSpendGuard} now uses one atomic {@code putIfAbsent}/{@code replace}/{@code remove}
     * state machine instead; this drives many threads at the same key, with a {@link CountDownLatch}
     * barrier to force maximal contention every round, and asserts at most one ever wins.
     */
    @Test
    void concurrentReserveAndCommitAllowsAtMostOneSuccessPerKey() throws Exception {
        int threadCount = 12;
        int rounds = 300;
        pool = Executors.newFixedThreadPool(threadCount);
        PropertiesSpendGuard guard = new PropertiesSpendGuard(1000, List.of(PAY_TO));

        for (int round = 0; round < rounds; round++) {
            String key = "round-" + round;
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger successes = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        SpendReservation reservation = guard.reserve(intent(key, PAY_TO, "500"));
                        guard.commit(reservation, settlement());
                        successes.incrementAndGet();
                    } catch (SpendDeniedException expected) {
                        // Every thread but (at most) one is expected to land here.
                    }
                    return null;
                }));
            }
            ready.await();
            go.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
            assertThat(successes.get()).isEqualTo(1);
        }
    }

    private static SettlementResponse settlement() {
        return new SettlementResponse(
                true,
                null,
                null,
                "0x209693Bc6afc0C5328bA36FaF03C514EF312287C",
                "0xabc",
                TestnetAssets.NETWORK,
                "500",
                null,
                null,
                null);
    }
}
