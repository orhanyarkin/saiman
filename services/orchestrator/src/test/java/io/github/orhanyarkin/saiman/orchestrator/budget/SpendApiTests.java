package io.github.orhanyarkin.saiman.orchestrator.budget;

import io.github.orhanyarkin.saiman.orchestrator.spendtest.DashboardSeed;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.RunTestSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

/** {@code GET /api/v1/spend}: limits plus one UTC day of spend. Test limits: see SpendTestSupport. */
class SpendApiTests extends RunTestSupport {

    @Test
    void aDayShowsCountersLimitsAndTheIntentsByToolAndStatus() {
        DashboardSeed seed = new DashboardSeed(jdbc);
        UUID run = seed.run(Instant.now(), "RUNNING", 50_000, 0, 20_000);
        seed.spendDay("2026-03-04", 5_000, 20_000);
        seed.intent(run, "disclosureSummary", "SETTLED", 10_000L, "2026-03-04");
        seed.intent(run, "disclosureSummary", "SETTLED", 10_000L, "2026-03-04");
        seed.intent(run, "askDisclosures", "HELD", 5_000L, "2026-03-04");
        seed.intent(run, "askDisclosures", "SETTLED", 10_000L, "2026-03-05"); // another day

        http.get()
                .uri("/api/v1/spend?day=2026-03-04")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.day")
                .isEqualTo("2026-03-04")
                .jsonPath("$.dailyCap.atomicUnits")
                .isEqualTo(1_000_000)
                .jsonPath("$.dayReserved.atomicUnits")
                .isEqualTo(5_000)
                .jsonPath("$.dayCommitted.atomicUnits")
                .isEqualTo(20_000)
                .jsonPath("$.limits.defaultRunBudget.atomicUnits")
                .isEqualTo(50_000)
                .jsonPath("$.limits.maxRunBudget.atomicUnits")
                .isEqualTo(200_000)
                .jsonPath("$.limits.approvalThreshold.atomicUnits")
                .isEqualTo(15_000)
                .jsonPath("$.limits.perRequestMax.atomicUnits")
                .isEqualTo(20_000)
                .jsonPath("$.byTool.length()")
                .isEqualTo(2)
                .jsonPath("$.byTool[0].tool")
                .isEqualTo("askDisclosures")
                .jsonPath("$.byTool[0].status")
                .isEqualTo("HELD")
                .jsonPath("$.byTool[0].count")
                .isEqualTo(1)
                .jsonPath("$.byTool[1].tool")
                .isEqualTo("disclosureSummary")
                .jsonPath("$.byTool[1].count")
                .isEqualTo(2)
                .jsonPath("$.byTool[1].amount.atomicUnits")
                .isEqualTo(20_000);
    }

    @Test
    void theDefaultDayIsTodayUtcAndAnEmptyDayIsZero() {
        http.get()
                .uri("/api/v1/spend")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.day")
                .isEqualTo(LocalDate.now(ZoneOffset.UTC).toString())
                .jsonPath("$.dayReserved.atomicUnits")
                .isEqualTo(0)
                .jsonPath("$.dayCommitted.atomicUnits")
                .isEqualTo(0)
                .jsonPath("$.byTool.length()")
                .isEqualTo(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"yesterday", "2026-13-01", "2026-02-30", "20260304", "2026-3-4", "'; DROP TABLE run;--"})
    void anInvalidDayIsA400ProblemDetails(String day) {
        http.get()
                .uri(uriBuilder ->
                        uriBuilder.path("/api/v1/spend").queryParam("day", day).build())
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }
}
