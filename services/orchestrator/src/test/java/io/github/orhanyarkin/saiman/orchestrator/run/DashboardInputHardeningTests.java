package io.github.orhanyarkin.saiman.orchestrator.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.apisecurity.testfixtures.TestTokens;
import io.github.orhanyarkin.saiman.orchestrator.approval.ApiRequestGuardFilter;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.RunTestSupport;
import io.github.orhanyarkin.x402.client.X402ClientProperties;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Hostile input to the API: no response may echo it, a forged cursor is a 400 (never a 500), and the
 * Host guard answers HEAD and OPTIONS too.
 */
class DashboardInputHardeningTests extends RunTestSupport {

    private static final String MARKER = "ZZMARK";
    private static final String NASTY = "a b\"'<>\nZZMARK&x=y%";

    @LocalServerPort
    private int port;

    @Autowired
    private ConfigurableApplicationContext context;

    private void assertFixedProblem(RestTestClient.ResponseSpec response, String expectedDetail) {
        String body = response.expectStatus()
                .isBadRequest()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).isNotNull().doesNotContain(MARKER).doesNotContain("ZZ");
        assertThat(body).contains("\"detail\":\"" + expectedDetail + "\"");
    }

    @Test
    void aBadPathVariableNamesTheParameterButNeverEchoesTheValue() {
        UUID valid = UUID.randomUUID();
        assertFixedProblem(http.get().uri("/api/v1/runs/" + MARKER).exchange(), "`runId` is invalid");
        assertFixedProblem(
                http.get().uri("/api/v1/runs/" + MARKER + "/payments").exchange(), "`runId` is invalid");
        assertFixedProblem(
                http.get()
                        .uri("/api/v1/runs/" + MARKER + "/events")
                        .accept(MediaType.APPLICATION_JSON)
                        .exchange(),
                "`runId` is invalid");
        assertFixedProblem(
                http.get()
                        .uri("/api/v1/runs/" + MARKER + "/events")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .exchange(),
                "`runId` is invalid");
        assertFixedProblem(approve(MARKER, valid.toString()), "`runId` is invalid");
        assertFixedProblem(approve(valid.toString(), MARKER), "`approvalId` is invalid");
    }

    @Test
    void theProblemInstanceIsTheRouteTemplateNotTheRequestPath() {
        http.get()
                .uri("/api/v1/runs/" + MARKER)
                .exchange()
                .expectBody()
                .jsonPath("$.instance")
                .isEqualTo("/api/v1/runs/%7BrunId%7D");
    }

    @Test
    void aHostileContentTypeIsNeverEchoedInBodyOrHeaders() {
        for (String contentType : List.of("application/json;x=\"ZZMARK\"", "application/x-ZZMARK")) {
            var result = http.post()
                    .uri("/api/v1/runs")
                    .header("Content-Type", contentType)
                    .header(ApiRequestGuardFilter.CSRF_HEADER, "1")
                    .body("{\"question\":\"hello there\"}")
                    .exchange()
                    .returnResult(String.class);
            assertThat(String.valueOf(result.getResponseBody())).doesNotContain(MARKER);
            assertThat(result.getResponseHeaders().toString()).doesNotContain(MARKER);
        }
    }

    @Test
    void theErrorDispatchEchoesNeitherPathNorMessage() {
        assertThat(context.getEnvironment().getProperty("server.error.include-path"))
                .isEqualTo("never");
        assertThat(context.getEnvironment().getProperty("server.error.include-message"))
                .isEqualTo("never");
        String body = http.get()
                .uri("/error?x=" + MARKER)
                .exchange()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).isNotNull().doesNotContain(MARKER).doesNotContain("\"path\"");
    }

    @Test
    void anAdmissionRejectionCarriesTheRouteTemplateAsInstance() {
        http.post()
                .uri("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .header(ApiRequestGuardFilter.CSRF_HEADER, "1")
                .body("{\"question\":\"x\"}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.instance")
                .isEqualTo("/api/v1/runs");
    }

    @Test
    void the429And503AdmissionProblemsCarryTheRouteTemplateToo() {
        RunController controller = new RunController(null, null, java.time.Clock.systemUTC());
        for (RunAdmissionException.Reason reason : RunAdmissionException.Reason.values()) {
            var response = controller.rejected(new RunAdmissionException(reason));
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getInstance()).hasToString("/api/v1/runs");
        }
    }

    @Test
    void anUnknownPathDoesNotEchoItEither() {
        String body = http.get()
                .uri("/api/v1/" + MARKER)
                .exchange()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).isNotNull().doesNotContain(MARKER);
    }

    @Test
    void badQueryParametersGetFixedMessages() {
        assertFixedProblem(
                http.get().uri("/api/v1/runs?limit={v}", NASTY).exchange(),
                "limit must be an integer between 1 and 100");
        assertFixedProblem(
                http.get().uri("/api/v1/runs?limit={v}", MARKER).exchange(),
                "limit must be an integer between 1 and 100");
        assertFixedProblem(
                http.get().uri("/api/v1/runs?before={v}", NASTY).exchange(),
                "before must be a cursor returned by this endpoint");
        assertFixedProblem(
                http.get().uri("/api/v1/runs?before={v}", MARKER).exchange(),
                "before must be a cursor returned by this endpoint");
        assertFixedProblem(
                http.get().uri("/api/v1/approvals?status={v}", NASTY).exchange(),
                "status must be PENDING, APPROVED, REJECTED or EXPIRED");
        assertFixedProblem(
                http.get().uri("/api/v1/approvals?status={v}", MARKER).exchange(),
                "status must be PENDING, APPROVED, REJECTED or EXPIRED");
        assertFixedProblem(
                http.get().uri("/api/v1/spend?day={v}", NASTY).exchange(), "day must be a date as YYYY-MM-DD");
        assertFixedProblem(
                http.get().uri("/api/v1/spend?day={v}", MARKER).exchange(), "day must be a date as YYYY-MM-DD");
    }

    @Test
    void forgedCursorsAreRejectedWith400NotA500() {
        for (String instant : List.of(
                "+300000-01-01T00:00:00Z",
                "-300000-01-01T00:00:00Z",
                "+1000000000-12-31T23:59:59Z",
                "0001-01-01T00:00:00Z",
                "1970-01-01T00:00:00Z",
                "2999-01-01T00:00:00Z",
                "9999-12-31T23:59:59.999999999Z")) {
            String cursor = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString((instant + "|" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
            assertFixedProblem(
                    http.get().uri("/api/v1/runs?before={c}", cursor).exchange(),
                    "before must be a cursor returned by this endpoint");
        }
    }

    @Test
    void headAndOptionsWithAForeignHostAreRefusedOnEveryDashboardPath() throws IOException {
        UUID run = UUID.randomUUID();
        List<String> paths = List.of(
                "/api/v1/runs",
                "/api/v1/runs/" + run,
                "/api/v1/runs/" + run + "/payments",
                "/api/v1/runs/" + run + "/events",
                "/api/v1/approvals",
                "/api/v1/spend");
        for (String method : List.of("HEAD", "OPTIONS")) {
            for (String path : paths) {
                assertThat(status(method, path, "evil.example"))
                        .as(method + " " + path)
                        .isEqualTo(400);
            }
        }
    }

    @Test
    void theSpendControllerHasNoDependencyOnTheKeyBearingClientProperties() {
        String[] x402Beans = context.getBeanNamesForType(X402ClientProperties.class);
        assertThat(x402Beans).isNotEmpty();
        List<String> dependencies = Arrays.asList(context.getBeanFactory().getDependenciesForBean("spendController"));
        assertThat(dependencies).isNotEmpty().doesNotContainAnyElementsOf(Arrays.asList(x402Beans));
    }

    private RestTestClient.ResponseSpec approve(String runId, String approvalId) {
        return http.post()
                .uri("/api/v1/runs/" + runId + "/approvals/" + approvalId)
                .contentType(MediaType.APPLICATION_JSON)
                .header(ApiRequestGuardFilter.CSRF_HEADER, "1")
                .body("{\"decision\":\"APPROVE\"}")
                .exchange();
    }

    private int status(String method, String path, String host) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream()
                    .write((method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\nAuthorization: "
                                    + TestTokens.bearer(TestTokens.OPERATOR) + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String statusLine = new String(socket.getInputStream().readNBytes(32), StandardCharsets.US_ASCII);
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }
}
