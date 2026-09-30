package io.github.orhanyarkin.saiman.orchestrator.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.orchestrator.budget.BudgetSpendGuard;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.FakeSeller;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.SpendTestSupport;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.run.DenyReason;
import io.github.orhanyarkin.x402.client.PaymentIntent;
import io.github.orhanyarkin.x402.client.SpendDeniedException;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

/**
 * Acceptance 2 (docs/design/m3-orchestrator.md, T3): the budget blocks payment before signing,
 * against a real Postgres, the real x402 interceptor and a real-socket seller stub.
 */
class PaidCallSpendControlTests extends SpendTestSupport {

    @Autowired
    private BudgetSpendGuard guard;

    @Test
    void runBudgetPaysTwiceThenDeniesTheThirdCallBeforeSigning() {
        UUID run = createRun(20_000);

        PaidResponse first = client.send(newIntent(run), null);
        PaidResponse second = client.send(newIntent(run), null);
        PaymentIntentHandle third = newIntent(run);

        assertThat(first.paid()).isTrue();
        assertThat(first.amount()).isEqualTo(Money.usdc(10_000));
        assertThat(second.paid()).isTrue();
        assertThat(first.body()).isEqualTo("{\"answer\":\"ok\"}");
        assertThatThrownBy(() -> client.send(third, null))
                .isInstanceOfSatisfying(
                        PaymentDeniedException.class,
                        e -> assertThat(e.reason()).isEqualTo(DenyReason.RUN_BUDGET));

        assertThat(signer.calls()).isEqualTo(2);
        assertThat(seller.paidRequests()).isEqualTo(2);
        assertThat(seller.invalidSignatures()).isZero();
        assertThat(intentsWithStatus(run, "SETTLED")).isEqualTo(2);
        assertThat(intents.find(third.id()).orElseThrow())
                .extracting(PaymentIntentView::status, PaymentIntentView::denyReason)
                .containsExactly(PaymentIntentStatus.DENIED, DenyReason.RUN_BUDGET);
        assertThat(run(run)).isEqualTo(new RunCounters(20_000, 0, 20_000));
        assertThat(today()).isEqualTo(new RunCounters(0, 0, 20_000));
    }

    @Test
    void sixteenConcurrentCallsAgainstFiftyThousandGrantExactlyFive() throws Exception {
        UUID run = createRun(50_000);
        List<PaymentIntentHandle> handles = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            handles.add(newIntent(run));
        }
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (PaymentIntentHandle handle : handles) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        client.send(handle, null);
                        return "SETTLED";
                    } catch (PaymentDeniedException e) {
                        return e.reason().name();
                    }
                }));
            }
            start.countDown();
            Map<String, Long> outcomes = new java.util.HashMap<>();
            for (Future<String> result : results) {
                outcomes.merge(result.get(), 1L, Long::sum);
            }
            assertThat(outcomes).containsExactlyInAnyOrderEntriesOf(Map.of("SETTLED", 5L, "RUN_BUDGET", 11L));
        }

        assertThat(signer.calls()).isEqualTo(5);
        assertThat(seller.paidRequests()).isEqualTo(5);
        assertThat(run(run)).isEqualTo(new RunCounters(50_000, 0, 50_000));
    }

    @Test
    void aHeldReservationKeepsCountingAndTheNextCallIsDenied() {
        UUID run = createRun(20_000);
        seller.paidMode(FakeSeller.PaidMode.FAIL_500);
        PaymentIntentHandle held = newIntent(run);

        assertThatThrownBy(() -> client.send(held, null)).isInstanceOf(PaymentOutcomeUnknownException.class);
        assertThat(intents.find(held.id()).orElseThrow().status()).isEqualTo(PaymentIntentStatus.HELD);
        assertThat(run(run)).isEqualTo(new RunCounters(20_000, 10_000, 0));

        seller.paidMode(FakeSeller.PaidMode.SETTLE);
        assertThat(client.send(newIntent(run), null).paid()).isTrue();
        PaymentIntentHandle denied = newIntent(run);
        assertThatThrownBy(() -> client.send(denied, null))
                .isInstanceOfSatisfying(
                        PaymentDeniedException.class,
                        e -> assertThat(e.reason()).isEqualTo(DenyReason.RUN_BUDGET));

        assertThat(signer.calls()).isEqualTo(2);
        assertThat(run(run)).isEqualTo(new RunCounters(20_000, 10_000, 10_000));
        assertThat(today()).isEqualTo(new RunCounters(0, 10_000, 10_000));
    }

    @Test
    void aPayeeOutsideTheAllowlistIsDeniedWithZeroSignatures() {
        UUID run = createRun(50_000);
        seller.payTo(FakeSeller.NOT_ALLOWED_PAY_TO);
        PaymentIntentHandle handle = newIntent(run);

        assertThatThrownBy(() -> client.send(handle, null))
                .isInstanceOfSatisfying(
                        PaymentDeniedException.class,
                        e -> assertThat(e.reason()).isEqualTo(DenyReason.PAYEE_NOT_ALLOWED));

        assertThat(signer.calls()).isZero();
        assertThat(seller.paidRequests()).isZero();
        assertThat(intents.find(handle.id()).orElseThrow())
                .extracting(PaymentIntentView::status, PaymentIntentView::denyReason)
                .containsExactly(PaymentIntentStatus.DENIED, DenyReason.PAYEE_NOT_ALLOWED);
        assertThat(run(run)).isEqualTo(new RunCounters(50_000, 0, 0));
    }

    @Test
    void theGuardItselfRefusesAPayeeOutsideTheAllowlist() {
        UUID run = createRun(50_000);
        PaymentIntentHandle handle = newIntent(run);

        assertThatThrownBy(() -> guard.reserve(intentFor(handle, FakeSeller.NOT_ALLOWED_PAY_TO, 10_000)))
                .isInstanceOf(SpendDeniedException.class);

        assertThat(intents.find(handle.id()).orElseThrow().denyReason()).isEqualTo(DenyReason.PAYEE_NOT_ALLOWED);
        assertThat(run(run)).isEqualTo(new RunCounters(50_000, 0, 0));
    }

    @Test
    void anAmountOverThePerRequestMaximumIsDeniedWithZeroSignatures() {
        UUID run = createRun(50_000);
        seller.price(25_000);
        PaymentIntentHandle handle = newIntent(run);

        assertThatThrownBy(() -> client.send(handle, null))
                .isInstanceOfSatisfying(
                        PaymentDeniedException.class,
                        e -> assertThat(e.reason()).isEqualTo(DenyReason.OVER_PER_REQUEST_MAX));
        assertThat(signer.calls()).isZero();
        assertThat(seller.paidRequests()).isZero();
    }

    @Test
    void anUnknownIdempotencyKeyIsRefusedAndChangesNothing() {
        UUID run = createRun(50_000);
        PaymentIntentHandle handle = newIntent(run);
        PaymentIntent forged = new PaymentIntent("not-a-key-we-issued", handle.resource(), offer(FakeSeller.PAY_TO, 1));

        assertThatThrownBy(() -> guard.reserve(forged)).isInstanceOf(SpendDeniedException.class);

        assertThat(intents.find(handle.id()).orElseThrow().status()).isEqualTo(PaymentIntentStatus.PENDING);
        assertThat(run(run)).isEqualTo(new RunCounters(50_000, 0, 0));
    }

    @Test
    void aReusedIdempotencyKeyIsRefusedBeforeSigning() {
        UUID run = createRun(50_000);
        PaymentIntentHandle handle = newIntent(run);
        assertThat(client.send(handle, null).paid()).isTrue();

        assertThatThrownBy(() -> client.send(handle, null))
                .isInstanceOfSatisfying(
                        PaymentDeniedException.class,
                        e -> assertThat(e.reason()).isEqualTo(DenyReason.UNKNOWN_INTENT));

        assertThat(signer.calls()).isEqualTo(1);
        assertThat(seller.paidRequests()).isEqualTo(1);
        assertThat(intents.find(handle.id()).orElseThrow().status()).isEqualTo(PaymentIntentStatus.SETTLED);
        assertThat(run(run)).isEqualTo(new RunCounters(50_000, 0, 10_000));
    }

    @Test
    void aResourceOtherThanTheIntentsIsRefused() {
        UUID run = createRun(50_000);
        PaymentIntentHandle handle = newIntent(run);
        PaymentIntent elsewhere = new PaymentIntent(
                PaymentTestAccess.idempotencyKey(handle),
                URI.create("http://127.0.0.1:1/v1/disclosures/OTHER/summary"),
                offer(FakeSeller.PAY_TO, 10_000));

        assertThatThrownBy(() -> guard.reserve(elsewhere)).isInstanceOf(SpendDeniedException.class);
        assertThat(intents.find(handle.id()).orElseThrow().denyReason()).isEqualTo(DenyReason.UNKNOWN_INTENT);
    }

    @Test
    void aRedirectOnThePaidRetryIsNotFollowed() {
        UUID run = createRun(50_000);
        seller.paidMode(FakeSeller.PaidMode.REDIRECT);
        PaymentIntentHandle handle = newIntent(run);

        assertThatThrownBy(() -> client.send(handle, null)).isInstanceOf(PaymentOutcomeUnknownException.class);

        // The seller saw the signature once; the 302 target on 127.0.0.2 saw nothing at all.
        assertThat(seller.paidRequests()).isEqualTo(1);
        assertThat(seller.redirectTargetRequests()).isZero();
        assertThat(seller.redirectTargetSignatures()).isZero();
        assertThat(intents.find(handle.id()).orElseThrow().status()).isEqualTo(PaymentIntentStatus.HELD);
        assertThat(run(run)).isEqualTo(new RunCounters(50_000, 10_000, 0));
    }

    @Test
    void aRedirectOnTheFirstRequestIsNotFollowedAndNothingIsSigned() {
        UUID run = createRun(50_000);
        seller.redirectUnpaid(true);
        PaymentIntentHandle handle = newIntent(run);

        assertThatThrownBy(() -> client.send(handle, null))
                .isInstanceOfSatisfying(SellerCallFailedException.class, e -> {
                    assertThat(e.kind()).isEqualTo(SellerCallFailedException.Kind.HTTP_ERROR);
                    assertThat(e.status()).isEqualTo(302);
                });
        assertThat(seller.redirectTargetRequests()).isZero();
        assertThat(signer.calls()).isZero();
        assertThat(intents.find(handle.id()).orElseThrow().status()).isEqualTo(PaymentIntentStatus.RELEASED);
    }

    @Test
    void theRunBudgetIsImmutableInTheDatabase() {
        UUID run = createRun(20_000);

        assertThatThrownBy(() -> jdbc.sql("UPDATE run SET budget_atomic = 999999 WHERE id = :id")
                        .param("id", run)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("UPDATE run SET reserved_atomic = 20001 WHERE id = :id")
                        .param("id", run)
                        .update())
                .isInstanceOf(DataAccessException.class);
        assertThat(run(run).budget()).isEqualTo(20_000);
    }

    @Test
    void idempotencyKeysAreRandom128BitBase64Url() {
        UUID run = createRun(50_000);
        List<String> keys = java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> PaymentTestAccess.idempotencyKey(newIntent(run)))
                .toList();
        assertThat(keys).doesNotHaveDuplicates().allMatch(key -> key.matches("[A-Za-z0-9_-]{22}"));
        assertThat(keys.stream().collect(Collectors.toMap(Function.identity(), String::length)))
                .hasSize(50);
    }

    private static PaymentIntent intentFor(PaymentIntentHandle handle, String payTo, long amount) {
        return new PaymentIntent(PaymentTestAccess.idempotencyKey(handle), handle.resource(), offer(payTo, amount));
    }

    private static PaymentRequirements offer(String payTo, long amount) {
        return new PaymentRequirements(
                TestnetAssets.SCHEME_EXACT,
                TestnetAssets.NETWORK,
                Long.toString(amount),
                TestnetAssets.USDC_ADDRESS,
                payTo,
                60,
                Map.of("name", TestnetAssets.USDC_NAME, "version", TestnetAssets.USDC_VERSION));
    }
}
