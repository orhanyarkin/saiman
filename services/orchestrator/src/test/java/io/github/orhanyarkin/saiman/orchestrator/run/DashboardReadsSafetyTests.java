package io.github.orhanyarkin.saiman.orchestrator.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.orhanyarkin.saiman.apisecurity.testfixtures.TestTokens;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.DashboardSeed;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.RunTestSupport;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * What the four dashboard reads must never do: leak key material, answer a bad Host, expose an
 * OpenAPI document, or go unobserved.
 */
class DashboardReadsSafetyTests extends RunTestSupport {

    @LocalServerPort
    private int port;

    private List<String> readPaths(UUID run) {
        return List.of(
                "/api/v1/runs",
                "/api/v1/runs/" + run + "/payments",
                "/api/v1/approvals?status=PENDING",
                "/api/v1/approvals?status=APPROVED",
                "/api/v1/spend?day=2026-01-01",
                "/api/v1/runs/" + run);
    }

    private UUID seedEverything() {
        DashboardSeed seed = new DashboardSeed(jdbc);
        UUID run = seed.run(Instant.now(), "AWAITING_APPROVAL", 50_000, 18_000, 10_000);
        seed.spendDay("2026-01-01", 18_000, 10_000);
        UUID waiting = seed.intent(run, "askDisclosures", "AWAITING_APPROVAL", 18_000L, "2026-01-01");
        UUID settled = seed.intent(run, "disclosureSummary", "SETTLED", 10_000L, "2026-01-01");
        seed.approval(run, waiting, "PENDING", 18_000);
        seed.approval(run, settled, "APPROVED", 10_000);
        return run;
    }

    @Test
    void noNonceSignatureKeyOrPayerAppearsInAnyNewResponse() {
        UUID run = seedEverything();
        for (String path : readPaths(run)) {
            String body = http.get()
                    .uri(path)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(String.class)
                    .returnResult()
                    .getResponseBody();
            assertThat(body).as(path).isNotBlank();
            assertThat(body)
                    .as(path)
                    .doesNotContain("MARKER")
                    .doesNotContain(DashboardSeed.MARKER_KEY)
                    .doesNotContain(DashboardSeed.MARKER_NONCE)
                    .doesNotContain(DashboardSeed.MARKER_PAYER)
                    .doesNotContainIgnoringCase("nonce")
                    .doesNotContainIgnoringCase("signature")
                    .doesNotContainIgnoringCase("idempotency")
                    .doesNotContainIgnoringCase("payer");
        }
    }

    @Test
    void theHostGuardStillAppliesToTheNewReads() throws IOException {
        UUID run = seedEverything();
        for (String path : readPaths(run)) {
            assertThat(status(path, "evil.example")).as(path).isEqualTo(400);
            assertThat(status(path, "localhost")).as(path).isEqualTo(200);
        }
    }

    @Test
    void theOpenApiDocumentIsNotReachableWhenSpringdocIsDisabled() {
        // the default configuration (springdoc.api-docs.enabled=false): nothing serves the contract
        http.get().uri("/v3/api-docs").exchange().expectStatus().isNotFound();
        http.get().uri("/v3/api-docs.yaml").exchange().expectStatus().isNotFound();
        http.get().uri("/swagger-ui/index.html").exchange().expectStatus().isNotFound();
    }

    @Test
    void theNewHandlersAreObservedWithSpans() {
        UUID run = seedEverything();
        readPaths(run)
                .forEach(path -> http.get().uri(path).exchange().expectStatus().isOk());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            for (String route :
                    List.of("/api/v1/runs", "/api/v1/runs/{runId}/payments", "/api/v1/approvals", "/api/v1/spend")) {
                assertThat(SPANS.getFinishedSpanItems())
                        .as(route)
                        .anySatisfy(span -> assertThat(span.getAttributes().asMap().values().stream()
                                        .map(Object::toString)
                                        .anyMatch(route::equals))
                                .as("span with http.route " + route)
                                .isTrue());
            }
        });
    }

    private int status(String path, String host) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream()
                    .write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nAuthorization: "
                                    + TestTokens.bearer(TestTokens.OPERATOR) + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String statusLine = new String(socket.getInputStream().readNBytes(32), StandardCharsets.US_ASCII);
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }
}
