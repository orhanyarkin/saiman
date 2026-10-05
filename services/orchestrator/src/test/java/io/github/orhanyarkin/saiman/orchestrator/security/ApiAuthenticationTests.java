package io.github.orhanyarkin.saiman.orchestrator.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.orhanyarkin.saiman.apisecurity.testfixtures.TestTokens;
import io.github.orhanyarkin.saiman.orchestrator.approval.ApiRequestGuardFilter;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.RunTestSupport;
import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.orhanyarkin.saiman.shared.run.AgentStep;
import io.github.orhanyarkin.saiman.shared.run.RunCost;
import io.github.orhanyarkin.saiman.shared.run.RunEventData;
import io.github.orhanyarkin.saiman.shared.run.RunEventType;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The security chain on a real Tomcat (ADR-0023): 401 without or with a bad token, READER reads but cannot start runs
 * or decide approvals, OPERATOR can, a token in the URL is ignored, the SSE stream authenticates with a Bearer header
 * and survives its async dispatch, and the request guard still answers first.
 */
class ApiAuthenticationTests extends RunTestSupport {

    @LocalServerPort
    private int port;

    private RestTestClient as(@Nullable String token) {
        return http.mutate()
                .defaultHeaders(headers -> {
                    headers.remove(HttpHeaders.AUTHORIZATION);
                    if (token != null) {
                        headers.set(HttpHeaders.AUTHORIZATION, TestTokens.bearer(token));
                    }
                })
                .build();
    }

    @Test
    void noTokenAndABadTokenAre401WithAFixedProblemBody() {
        for (RestTestClient client : List.of(as(null), as(TestTokens.UNKNOWN))) {
            client.get()
                    .uri("/api/v1/runs")
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectHeader()
                    .exists(HttpHeaders.WWW_AUTHENTICATE)
                    .expectHeader()
                    .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        }
    }

    @Test
    void aTokenInTheQueryStringIsIgnored() {
        as(null).get()
                .uri("/api/v1/runs?access_token=" + TestTokens.OPERATOR)
                .exchange()
                .expectStatus()
                .isUnauthorized();
        as(null).get()
                .uri("/api/v1/runs?token=" + TestTokens.OPERATOR)
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void healthIsOpenButNothingElseIs() {
        as(null).get().uri("/actuator/health").exchange().expectStatus().isOk();
        as(null).get().uri("/actuator/info").exchange().expectStatus().isUnauthorized();
        as(null).get().uri("/v3/api-docs").exchange().expectStatus().isUnauthorized();
        as(TestTokens.OPERATOR).get().uri("/not-an-api").exchange().expectStatus().isForbidden();
    }

    @Test
    void aReaderReadsEverythingButCannotStartRunsOrDecideApprovals() {
        RestTestClient reader = as(TestTokens.READER);
        for (String path : List.of("/api/v1/runs", "/api/v1/approvals", "/api/v1/spend", "/api/v1/ping", "/api/v1/me")) {
            reader.get().uri(path).exchange().expectStatus().isOk();
        }

        reader.post()
                .uri("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .header(ApiRequestGuardFilter.CSRF_HEADER, "1")
                .body("{\"question\":\"What changed at THYAO?\"}")
                .exchange()
                .expectStatus()
                .isForbidden();
        reader.post()
                .uri("/api/v1/runs/{run}/approvals/{approval}", UUID.randomUUID(), UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .header(ApiRequestGuardFilter.CSRF_HEADER, "1")
                .body("{\"decision\":\"APPROVE\"}")
                .exchange()
                .expectStatus()
                .isForbidden();
        assertThat(jdbc.sql("SELECT count(*) FROM run").query(Integer.class).single())
                .isZero();
    }

    @Test
    void anOperatorPassesAuthenticationOnTheMutations() {
        RestTestClient operator = as(TestTokens.OPERATOR);
        // A question that fails validation: 400 proves the request got past authorization without starting a run.
        operator.post()
                .uri("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .header(ApiRequestGuardFilter.CSRF_HEADER, "1")
                .body("{\"question\":\"\"}")
                .exchange()
                .expectStatus()
                .isBadRequest();
        // An approval that does not exist: 404 comes from the handler.
        operator.post()
                .uri("/api/v1/runs/{run}/approvals/{approval}", UUID.randomUUID(), UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .header(ApiRequestGuardFilter.CSRF_HEADER, "1")
                .body("{\"decision\":\"APPROVE\"}")
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void meExpandsTheRoleHierarchy() {
        as(TestTokens.OPERATOR)
                .get()
                .uri("/api/v1/me")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.roles[0]")
                .isEqualTo("OPERATOR")
                .jsonPath("$.roles[1]")
                .isEqualTo("READER")
                .jsonPath("$.roles.length()")
                .isEqualTo(2)
                .jsonPath("$.name")
                .value(name -> assertThat((String) name).matches("operator:[0-9a-f]{8}"));
        as(TestTokens.READER)
                .get()
                .uri("/api/v1/me")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.roles.length()")
                .isEqualTo(1)
                .jsonPath("$.roles[0]")
                .isEqualTo("READER")
                .jsonPath("$.name")
                .value(name -> assertThat((String) name).matches("reader:[0-9a-f]{8}"));
    }

    @Test
    void theEventStreamAuthenticatesWithABearerHeaderAndEndsCleanlyAfterItsAsyncDispatch() throws Exception {
        UUID run = createRun(50_000);
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/runs/" + run + "/events"))
                .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.READER))
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .build();

        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");

        eventLog.append(run, RunEventType.STEP_STARTED, new RunEventData.StepChanged(AgentStep.PLANNER));
        eventLog.append(
                run,
                RunEventType.RUN_FAILED,
                new RunEventData.RunFailed("INTERNAL_ERROR", RunCost.of(Money.usdc(0), Money.usdMicros(0))));
        jdbc.sql("UPDATE run SET status = 'FAILED', finished_at = now() WHERE id = :id")
                .param("id", run)
                .update();

        List<String> lines = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                String line = in.readLine();
                if (line == null) {
                    return true; // the stream completed: the async dispatch was permitted and finished
                }
                lines.add(line);
                return false;
            });
        }
        assertThat(lines).anyMatch(line -> line.startsWith("event:STEP_CHANGED") || line.startsWith("event: STEP_CHANGED"));
        assertThat(lines).anyMatch(line -> line.contains("RUN_FAILED"));
    }

    @Test
    void theEventStreamWithoutATokenIs401AndOpensNoSubscription() throws Exception {
        UUID run = createRun(50_000);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/runs/" + run + "/events"))
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        HttpRequest inQuery = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/v1/runs/" + run + "/events?access_token=" + TestTokens.READER))
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .build();
        assertThat(HttpClient.newHttpClient().send(inQuery, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(401);
    }

    @Test
    void theRequestGuardAnswersBeforeAuthentication() throws IOException {
        // No token at all: a security-first chain would answer 401 to each of these.
        assertThat(raw("GET /api/v1/runs HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n"))
                .startsWith("HTTP/1.1 400");
        assertThat(raw("GET /api/v1/runs;x=1 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"))
                .startsWith("HTTP/1.1 400");
        assertThat(raw("GET /api/%76%31/runs HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"))
                .startsWith("HTTP/1.1 400");
        String noCsrf = raw("POST /api/v1/runs HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                + "Content-Length: 2\r\nConnection: close\r\n\r\n{}");
        assertThat(noCsrf).startsWith("HTTP/1.1 403").contains(ApiRequestGuardFilter.CSRF_HEADER);
        // A foreign Host loses even with a valid OPERATOR token.
        assertThat(raw("GET /api/v1/runs HTTP/1.1\r\nHost: evil.example\r\nAuthorization: "
                        + TestTokens.bearer(TestTokens.OPERATOR) + "\r\nConnection: close\r\n\r\n"))
                .startsWith("HTTP/1.1 400");
        // And a clean request without a token reaches the chain: 401.
        assertThat(raw("GET /api/v1/runs HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"))
                .startsWith("HTTP/1.1 401");
    }

    /** The response as text (status line, headers and the start of the body). */
    private String raw(String request) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return new String(socket.getInputStream().readNBytes(2048), StandardCharsets.US_ASCII);
        }
    }
}
