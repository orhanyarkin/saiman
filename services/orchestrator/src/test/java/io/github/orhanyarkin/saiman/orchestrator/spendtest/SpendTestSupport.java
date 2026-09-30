package io.github.orhanyarkin.saiman.orchestrator.spendtest;

import io.github.orhanyarkin.saiman.orchestrator.TestcontainersConfiguration;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaidResourceClient;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentHandle;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentService;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentTestAccess;
import io.github.orhanyarkin.saiman.orchestrator.payment.SellerEndpoint;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared setup for the spend-control tests: a real Postgres (Testcontainers), the real starter
 * interceptor and {@code BudgetSpendGuard}, a spy signer and the real-socket {@link FakeSeller}.
 * Every table is truncated before each test.
 *
 * <p>Limits: per-request max 20000, approval threshold 15000 (strictly above waits), daily cap
 * 1000000, max 20 paid calls per run (so the budget, not the call count, is what binds).
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "x402.client.max-amount-per-request=20000",
            "x402.client.allowed-pay-to=" + FakeSeller.PAY_TO + "," + FakeSeller.PAY_TO_2,
            "saiman.orchestrator.spend.approval-threshold-atomic=15000",
            "saiman.orchestrator.spend.max-paid-calls-per-run=20",
            "saiman.orchestrator.spend.daily-cap-atomic=1000000"
        })
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, SpendTestSupport.SignerConfiguration.class})
public abstract class SpendTestSupport {

    protected final FakeSeller seller = FakeSeller.shared();

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected PaymentIntentService intents;

    @Autowired
    protected PaidResourceClient client;

    @Autowired
    protected CountingSigner signer;

    @DynamicPropertySource
    static void sellerUrl(DynamicPropertyRegistry registry) {
        registry.add("saiman.orchestrator.seller.base-url", FakeSeller.shared()::baseUrl);
    }

    @BeforeEach
    void resetState() {
        jdbc.sql("TRUNCATE tool_result, approval, payment_intent, run_event, spend_day, run")
                .update();
        seller.reset();
        signer.reset();
        PaymentTestAccess.resetCircuitBreaker(client);
    }

    protected UUID createRun(long budgetAtomic) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO run (id, question, status, budget_atomic, llm_budget_usd_micros)"
                        + " VALUES (:id, 'test question', 'RUNNING', :budget, 150000)")
                .param("id", id)
                .param("budget", budgetAtomic)
                .update();
        return id;
    }

    protected PaymentIntentHandle newIntent(UUID runId) {
        return intents.create(
                runId,
                "disclosureSummary",
                "args-" + UUID.randomUUID(),
                SellerEndpoint.DISCLOSURE_SUMMARY,
                Map.of("ticker", "THYAO"));
    }

    protected RunCounters run(UUID runId) {
        return jdbc.sql("SELECT budget_atomic, reserved_atomic, committed_atomic FROM run WHERE id = :id")
                .param("id", runId)
                .query((rs, row) -> new RunCounters(
                        rs.getLong("budget_atomic"), rs.getLong("reserved_atomic"), rs.getLong("committed_atomic")))
                .single();
    }

    protected RunCounters today() {
        return jdbc.sql("SELECT 0 AS budget, coalesce(sum(reserved_atomic), 0) AS reserved,"
                        + " coalesce(sum(committed_atomic), 0) AS committed FROM spend_day")
                .query((rs, row) -> new RunCounters(0, rs.getLong("reserved"), rs.getLong("committed")))
                .single();
    }

    protected int intentsWithStatus(UUID runId, String status) {
        return jdbc.sql("SELECT count(*) FROM payment_intent WHERE run_id = :id AND status = :status")
                .param("id", runId)
                .param("status", status)
                .query(Integer.class)
                .single();
    }

    public record RunCounters(long budget, long reserved, long committed) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class SignerConfiguration {

        /** Replaces the starter's key-backed signer; the starter's interceptor still does the signing. */
        @Bean
        CountingSigner countingSigner() {
            return new CountingSigner();
        }
    }
}
