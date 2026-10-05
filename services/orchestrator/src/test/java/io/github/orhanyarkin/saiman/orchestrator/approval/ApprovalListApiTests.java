package io.github.orhanyarkin.saiman.orchestrator.approval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.orchestrator.spendtest.DashboardSeed;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.RunTestSupport;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** {@code GET /api/v1/approvals}: approvals by status. */
class ApprovalListApiTests extends RunTestSupport {

    private UUID pending;

    private void seedTwo() {
        DashboardSeed seed = new DashboardSeed(jdbc);
        UUID run = seed.run(Instant.now(), "AWAITING_APPROVAL", 50_000, 18_000, 0);
        UUID i1 = seed.intent(run, "disclosureSummary", "AWAITING_APPROVAL", 18_000L, "2026-01-01");
        UUID i2 = seed.intent(run, "askDisclosures", "REJECTED", 18_000L, "2026-01-01");
        pending = seed.approval(run, i1, "PENDING", 18_000);
        seed.approval(run, i2, "REJECTED", 18_000);
    }

    @Test
    void pendingIsTheDefault() {
        seedTwo();
        http.get()
                .uri("/api/v1/approvals")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(1)
                .jsonPath("$[0].id")
                .isEqualTo(pending.toString())
                .jsonPath("$[0].status")
                .isEqualTo("PENDING")
                .jsonPath("$[0].amountAtomic")
                .isEqualTo(18_000)
                .jsonPath("$[0].payTo")
                .isEqualTo(DashboardSeed.PAY_TO)
                .jsonPath("$[0].expiresAt")
                .isNotEmpty();
    }

    @Test
    void anotherStatusCanBeAsked() {
        seedTwo();
        http.get()
                .uri("/api/v1/approvals?status=REJECTED")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(1)
                .jsonPath("$[0].status")
                .isEqualTo("REJECTED");
    }

    @Test
    void anUnknownStatusIsA400WithoutEchoingIt() {
        String body = http.get()
                .uri("/api/v1/approvals?status=<script>alert(1)</script>")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).doesNotContain("script");
    }
}
