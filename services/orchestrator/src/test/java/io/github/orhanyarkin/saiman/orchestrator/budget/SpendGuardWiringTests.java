package io.github.orhanyarkin.saiman.orchestrator.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import io.github.orhanyarkin.saiman.orchestrator.TestcontainersConfiguration;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaidResourceClient;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentHandle;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentService;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentStatus;
import io.github.orhanyarkin.saiman.orchestrator.payment.SellerCallFailedException;
import io.github.orhanyarkin.saiman.orchestrator.payment.SellerEndpoint;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.FakeSeller;
import io.github.orhanyarkin.x402.client.PropertiesSpendGuard;
import io.github.orhanyarkin.x402.client.SpendGuard;
import io.github.orhanyarkin.x402.client.X402PaymentInterceptor;
import io.github.orhanyarkin.x402.evm.PaymentSigner;
import io.github.orhanyarkin.x402.evm.PrivateKeyPaymentSigner;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Production-shaped wiring: the signer comes from {@code x402.client.private-key} (a published
 * EIP-712 test vector with no funds, as in the starter's own tests), so the starter's own
 * auto-configuration decides which {@link SpendGuard} the interceptor gets (F-C).
 */
@SpringBootTest(
        properties = {
            "x402.client.private-key=0xc85ef7d79691fe79573b1a7064c19c1a9819ebdbd1faaab1a8ec92344438aaf4",
            "x402.client.max-amount-per-request=20000",
            "x402.client.allowed-pay-to=" + FakeSeller.PAY_TO
        })
@Import(TestcontainersConfiguration.class)
class SpendGuardWiringTests {

    private final FakeSeller seller = FakeSeller.shared();

    @MockitoSpyBean
    private BudgetSpendGuard guard;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PaymentIntentService intents;

    @Autowired
    private PaidResourceClient client;

    @DynamicPropertySource
    static void sellerUrl(DynamicPropertyRegistry registry) {
        registry.add("saiman.orchestrator.seller.base-url", FakeSeller.shared()::baseUrl);
    }

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE approval, payment_intent, run_event, spend_day, run").update();
        seller.reset();
    }

    @Test
    void theBudgetGuardIsTheOnlySpendGuardAndThePropertiesFallbackIsInactive() {
        assertThat(context.getBeansOfType(SpendGuard.class)).hasSize(1);
        assertThat(context.getBean(SpendGuard.class)).isInstanceOf(BudgetSpendGuard.class);
        assertThat(context.getBeanNamesForType(PropertiesSpendGuard.class)).isEmpty();
        assertThat(context.getBean(PaymentSigner.class)).isInstanceOf(PrivateKeyPaymentSigner.class);
        assertThat(context.getBeansOfType(X402PaymentInterceptor.class)).hasSize(1);
    }

    @Test
    void aThrowingSignedHookReleasesTheReservationAndSendsNothing() {
        doThrow(new IllegalStateException("database unavailable")).when(guard).signed(any(), any());
        UUID run = UUID.randomUUID();
        jdbc.sql("INSERT INTO run (id, question, status, budget_atomic, llm_budget_usd_micros)"
                        + " VALUES (:id, 'q', 'RUNNING', 50000, 150000)")
                .param("id", run)
                .update();
        PaymentIntentHandle handle = intents.create(
                run, "disclosureSummary", "args", SellerEndpoint.DISCLOSURE_SUMMARY, Map.of("ticker", "THYAO"));

        assertThatThrownBy(() -> client.send(handle, null))
                .isInstanceOfSatisfying(
                        SellerCallFailedException.class,
                        e -> assertThat(e.kind()).isEqualTo(SellerCallFailedException.Kind.ABORTED));

        assertThat(seller.unpaidRequests()).isEqualTo(1);
        assertThat(seller.paidRequests()).isZero();
        assertThat(intents.find(handle.id()).orElseThrow().status()).isEqualTo(PaymentIntentStatus.RELEASED);
        assertThat(jdbc.sql("SELECT reserved_atomic + committed_atomic FROM run WHERE id = :id")
                        .param("id", run)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("SELECT coalesce(sum(reserved_atomic + committed_atomic), 0) FROM spend_day")
                        .query(Long.class)
                        .single())
                .isZero();
    }
}
