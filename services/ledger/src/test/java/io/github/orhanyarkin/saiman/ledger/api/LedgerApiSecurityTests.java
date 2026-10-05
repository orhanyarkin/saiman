package io.github.orhanyarkin.saiman.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.apisecurity.testfixtures.TestTokens;
import io.github.orhanyarkin.saiman.ledger.LedgerIntegrationTest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The ledger's request rules over real Tomcat (ADR-0023): READER reads, OPERATOR starts runs, health is open,
 * everything else is denied; and {@link LedgerApiGuardFilter} answers before the security chain looks at a token.
 */
@LedgerIntegrationTest
class LedgerApiSecurityTests {

    private static final String TRIAL_BALANCE = "/api/v1/ledger/trial-balance";
    private static final String RUNS = "/api/v1/reconciliation/runs";

    @LocalServerPort
    private int port;

    /** Sends only the headers each request sets (unlike the shared client, which adds an OPERATOR token). */
    private RestTestClient client;

    @BeforeEach
    void anonymousClient() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    private static final String OPERATOR = TestTokens.bearer(TestTokens.OPERATOR);

    @Test
    void readsNeedAReaderToken() {
        client.get()
                .uri(TRIAL_BALANCE)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        for (String token : List.of(TestTokens.UNKNOWN, TestTokens.SERVICE_LEDGER)) {
            client.get()
                    .uri(TRIAL_BALANCE)
                    .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(token))
                    .exchange()
                    .expectStatus()
                    .isUnauthorized();
        }
        for (String token : List.of(TestTokens.READER, TestTokens.OPERATOR)) {
            client.get()
                    .uri(TRIAL_BALANCE)
                    .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(token))
                    .exchange()
                    .expectStatus()
                    .isOk();
            client.get()
                    .uri(RUNS)
                    .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(token))
                    .exchange()
                    .expectStatus()
                    .isOk();
        }
    }

    @Test
    void aTokenInTheQueryStringIsIgnored() {
        client.get()
                .uri(TRIAL_BALANCE + "?access_token=" + TestTokens.READER)
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void aReaderCannotStartAReconciliationRun() {
        client.post()
                .uri(RUNS)
                .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.READER))
                .header(LedgerApiGuardFilter.CSRF_HEADER, "1")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        client.post()
                .uri(RUNS)
                .header(LedgerApiGuardFilter.CSRF_HEADER, "1")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void anythingOutsideTheRulesIsDeniedEvenForAnOperator() {
        client.post()
                .uri(TRIAL_BALANCE)
                .header(HttpHeaders.AUTHORIZATION, OPERATOR)
                .header(LedgerApiGuardFilter.CSRF_HEADER, "1")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .exchange()
                .expectStatus()
                .isForbidden();
        for (String path : List.of("/api/v2/anything", "/actuator/env", "/v3/api-docs.yaml", "/swagger-ui.html")) {
            client.get()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, OPERATOR)
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        }
    }

    @Test
    void healthIsOpen() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
        client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
    }

    @Test
    void theGuardAnswersBeforeAuthentication() throws IOException {
        // No token anywhere: a 401 would mean the security chain ran first.
        Response pathParameter = raw("GET /api;x=1/v1/ledger/trial-balance HTTP/1.1\r\nHost: localhost\r\n");
        assertThat(pathParameter.status()).isEqualTo(400);
        assertThat(pathParameter.hasHeader(HttpHeaders.WWW_AUTHENTICATE)).isFalse();

        Response encoded = raw("GET /%61pi/v1/ledger/trial-balance HTTP/1.1\r\nHost: localhost\r\n");
        assertThat(encoded.status()).isEqualTo(400);
        assertThat(encoded.hasHeader(HttpHeaders.WWW_AUTHENTICATE)).isFalse();

        Response foreignHost = raw("GET " + TRIAL_BALANCE + " HTTP/1.1\r\nHost: attacker.example\r\n");
        assertThat(foreignHost.status()).isEqualTo(400);
        assertThat(foreignHost.hasHeader(HttpHeaders.WWW_AUTHENTICATE)).isFalse();

        Response noCsrf = raw(
                "POST " + RUNS + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                        + "Content-Length: 2\r\n",
                "{}");
        assertThat(noCsrf.status()).isEqualTo(403);
        assertThat(noCsrf.hasHeader(HttpHeaders.WWW_AUTHENTICATE)).isFalse();
        assertThat(noCsrf.body()).contains(LedgerApiGuardFilter.CSRF_HEADER);
    }

    private record Response(int status, List<String> headers, String body) {
        boolean hasHeader(String name) {
            String prefix = name.toLowerCase(Locale.ROOT) + ":";
            return headers.stream().anyMatch(h -> h.toLowerCase(Locale.ROOT).startsWith(prefix));
        }
    }

    private Response raw(String head) throws IOException {
        return raw(head, "");
    }

    /** Writes the request over a socket (HTTP clients normalise paths and refuse a foreign Host). */
    private Response raw(String head, String body) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write((head + "Connection: close\r\n\r\n" + body).getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String statusLine = in.readLine();
            assertThat(statusLine).as("status line").isNotNull().startsWith("HTTP/1.1 ");
            List<String> headers = new ArrayList<>();
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                headers.add(line);
            }
            StringBuilder rest = new StringBuilder();
            while ((line = in.readLine()) != null) {
                rest.append(line).append('\n');
            }
            return new Response(Integer.parseInt(statusLine.substring(9, 12)), headers, rest.toString());
        }
    }
}
