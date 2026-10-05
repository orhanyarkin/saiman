package io.github.orhanyarkin.saiman.orchestrator.payment;

import io.github.orhanyarkin.saiman.orchestrator.spendtest.DashboardSeed;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.RunTestSupport;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** {@code GET /api/v1/runs/{id}/payments}: current intent states of one run. */
class RunPaymentsApiTests extends RunTestSupport {

    @Test
    void intentsAreListedOldestFirstWithMoneyAndPayee() {
        DashboardSeed seed = new DashboardSeed(jdbc);
        UUID run = seed.run(Instant.now(), "RUNNING", 50_000, 0, 10_000);
        UUID settled = seed.intent(run, "disclosureSummary", "SETTLED", 10_000L, "2026-01-01");
        seed.intent(run, "askDisclosures", "DENIED", null, "2026-01-01");

        http.get()
                .uri("/api/v1/runs/{id}/payments", run)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.items.length()")
                .isEqualTo(2)
                .jsonPath("$.items[0].paymentIntentId")
                .isEqualTo(settled.toString())
                .jsonPath("$.items[0].tool")
                .isEqualTo("disclosureSummary")
                .jsonPath("$.items[0].status")
                .isEqualTo("SETTLED")
                .jsonPath("$.items[0].amount.atomicUnits")
                .isEqualTo(10_000)
                .jsonPath("$.items[0].amount.asset")
                .isEqualTo("USDC")
                .jsonPath("$.items[0].payTo")
                .isEqualTo(DashboardSeed.PAY_TO)
                .jsonPath("$.items[0].resource")
                .isEqualTo("http://seller/x")
                .jsonPath("$.items[0].txHash")
                .isNotEmpty()
                .jsonPath("$.items[0].createdAt")
                .isNotEmpty()
                .jsonPath("$.items[0].updatedAt")
                .isNotEmpty()
                .jsonPath("$.items[1].status")
                .isEqualTo("DENIED")
                .jsonPath("$.items[1].amount")
                .isEmpty();
    }

    @Test
    void aHeldIntentShowsItsCurrentStatusAfterTheResolverSettledIt() {
        DashboardSeed seed = new DashboardSeed(jdbc);
        UUID run = seed.run(Instant.now(), "SUCCEEDED", 50_000, 0, 10_000);
        UUID held = seed.intent(run, "disclosureSummary", "HELD", 10_000L, "2026-01-01");
        jdbc.sql("UPDATE payment_intent SET status = 'SETTLED', tx_hash = '0xresolved', resolved_by = 'CHAIN',"
                        + " resolved_at = now() WHERE id = :id")
                .param("id", held)
                .update();

        http.get()
                .uri("/api/v1/runs/{id}/payments", run)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.items[0].status")
                .isEqualTo("SETTLED")
                .jsonPath("$.items[0].txHash")
                .isEqualTo("0xresolved");
    }

    @Test
    void aRunWithoutIntentsHasAnEmptyList() {
        UUID run = new DashboardSeed(jdbc).run(Instant.now(), "RUNNING", 50_000, 0, 0);
        http.get()
                .uri("/api/v1/runs/{id}/payments", run)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.items.length()")
                .isEqualTo(0);
    }

    @Test
    void anUnknownRunIs404ProblemDetails() {
        http.get()
                .uri("/api/v1/runs/{id}/payments", UUID.randomUUID())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }
}
