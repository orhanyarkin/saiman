package io.github.orhanyarkin.saiman.orchestrator.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.orchestrator.spendtest.DashboardSeed;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.RunTestSupport;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;

/** {@code GET /api/v1/runs}: the keyset-paged run list for the dashboard. */
class RunListApiTests extends RunTestSupport {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    @Test
    void aRunIsListedWithItsMoneyCostAndPendingApprovals() {
        DashboardSeed seed = new DashboardSeed(jdbc);
        UUID run = seed.run(Instant.now(), "AWAITING_APPROVAL", 50_000, 18_000, 4_000);
        UUID intent = seed.intent(run, "disclosureSummary", "AWAITING_APPROVAL", 18_000L, "2026-01-01");
        seed.approval(run, intent, "PENDING", 18_000);

        http.get()
                .uri("/api/v1/runs")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.items.length()")
                .isEqualTo(1)
                .jsonPath("$.items[0].runId")
                .isEqualTo(run.toString())
                .jsonPath("$.items[0].status")
                .isEqualTo("AWAITING_APPROVAL")
                .jsonPath("$.items[0].question")
                .isEqualTo("seeded question")
                .jsonPath("$.items[0].budget.atomicUnits")
                .isEqualTo(50_000)
                .jsonPath("$.items[0].budget.asset")
                .isEqualTo("USDC")
                .jsonPath("$.items[0].budget.decimals")
                .isEqualTo(6)
                .jsonPath("$.items[0].reserved.atomicUnits")
                .isEqualTo(18_000)
                .jsonPath("$.items[0].committed.atomicUnits")
                .isEqualTo(4_000)
                .jsonPath("$.items[0].cost.llmUsd.atomicUnits")
                .isEqualTo(1_234)
                .jsonPath("$.items[0].pendingApprovals")
                .isEqualTo(1)
                .jsonPath("$.items[0].finishedAt")
                .isEmpty()
                .jsonPath("$.next")
                .isEmpty();
    }

    @Test
    void keysetPagingCoversEveryRunOnceNewestFirstEvenWithEqualTimestamps() {
        DashboardSeed seed = new DashboardSeed(jdbc);
        Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS);
        List<UUID> seeded = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            // runs 2, 3 and 4 share one created_at: only the id tie-break separates them
            Instant createdAt = base.minusSeconds(i >= 2 && i <= 4 ? 15 : i * 10L);
            seeded.add(seed.run(createdAt, "SUCCEEDED", 50_000, 0, 0));
        }

        List<UUID> seen = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 5; page++) {
            Map<String, Object> body = http.get()
                    .uri(cursor == null ? "/api/v1/runs?limit=3" : "/api/v1/runs?limit=3&before={c}", cursor)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(MAP)
                    .returnResult()
                    .getResponseBody();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
            sizes.add(items.size());
            items.forEach(i -> seen.add(UUID.fromString((String) i.get("runId"))));
            cursor = (String) body.get("next");
            if (cursor == null) {
                break;
            }
        }

        assertThat(sizes).containsExactly(3, 3, 1);
        assertThat(seen).hasSize(7).doesNotHaveDuplicates();
        assertThat((Set<UUID>) new LinkedHashSet<>(seen)).containsExactlyInAnyOrderElementsOf(seeded);
        // newest first: run 0, run 1, then the equal-timestamp trio (by id, descending), then runs 5 and 6
        assertThat(seen.subList(0, 2)).containsExactly(seeded.get(0), seeded.get(1));
        List<UUID> trio = new ArrayList<>(seeded.subList(2, 5));
        // Postgres orders uuid as unsigned bytes (= the hex text), unlike UUID.compareTo
        trio.sort((a, b) -> b.toString().compareTo(a.toString()));
        assertThat(seen.subList(2, 5)).containsExactlyElementsOf(trio);
        assertThat(seen.subList(5, 7)).containsExactly(seeded.get(5), seeded.get(6));
    }

    @Test
    void theDefaultLimitIs20() {
        DashboardSeed seed = new DashboardSeed(jdbc);
        for (int i = 0; i < 22; i++) {
            seed.run(Instant.now().minusSeconds(i), "SUCCEEDED", 50_000, 0, 0);
        }
        http.get()
                .uri("/api/v1/runs")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.items.length()")
                .isEqualTo(20)
                .jsonPath("$.next")
                .isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "limit=0",
                "limit=101",
                "limit=-1",
                "limit=abc",
                "limit=1.5",
                "limit=99999999999",
                "before=not-a-cursor",
                "before=bm9waXBl"
            })
    void invalidParametersGetAFixedProblemDetails(String query) {
        String body = http.get()
                .uri("/api/v1/runs?" + query)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).contains("\"status\":400");
        String value = query.substring(query.indexOf('=') + 1);
        if (value.length() > 4) { // shorter values could occur in the fixed text by chance
            assertThat(body).doesNotContain(value);
        }
    }
}
