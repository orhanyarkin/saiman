package io.github.orhanyarkin.saiman.orchestrator.approval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentApprovalRequiredException;
import io.github.orhanyarkin.saiman.orchestrator.payment.PaymentIntentHandle;
import io.github.orhanyarkin.saiman.orchestrator.spendtest.SpendTestSupport;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The request guard on a real Tomcat, with request lines written byte for byte over a socket
 * (MockMvc and HTTP clients normalise the path before it could reach the server). Path forms that
 * Spring MVC would still map to the approval endpoint ({@code ;} parameters, percent-encoding,
 * doubled slashes) are refused before the handler, and the approval stays PENDING.
 */
class ApiRequestGuardHttpTests extends SpendTestSupport {

    private static final String GOOD_HOST = "localhost";
    private static final String EVIL_HOST = "evil.example";
    private static final String APPROVE = "{\"decision\":\"APPROVE\"}";

    @LocalServerPort
    private int port;

    @Autowired
    private ApprovalService approvals;

    private UUID run;
    private UUID approvalId;

    @BeforeEach
    void pendingApproval() {
        run = createRun(50_000);
        seller.price(18_000); // above the 15000 threshold of SpendTestSupport
        PaymentIntentHandle handle = newIntent(run);
        try {
            client.send(handle, null);
            throw new AssertionError("expected an approval request");
        } catch (PaymentApprovalRequiredException e) {
            approvalId = e.approvalId();
        }
        assertThat(approvalStatus()).isEqualTo(ApprovalStatus.PENDING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api;x=1", "/%61pi", "/api%2F", "//api", "/api/./", "/x/../api"})
    void aDisguisedApprovalPathNeverReachesTheHandler(String prefix) throws IOException {
        String path = prefix + (prefix.endsWith("/") ? "" : "/") + "v1/runs/" + run + "/approvals/" + approvalId;

        assertThat(status("POST", path, EVIL_HOST, true, true)).isEqualTo(400);
        assertThat(status("POST", path, GOOD_HOST, true, false)).isIn(400, 403);
        assertThat(status("POST", path, GOOD_HOST, true, true)).isEqualTo(400);
        assertThat(status("POST", path, null, true, true)).isEqualTo(400);

        assertThat(approvalStatus()).isEqualTo(ApprovalStatus.PENDING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api;/v1/ping", "/api;x=1/v1/ping", "/%61pi/v1/ping", "//api/v1/ping"})
    void aDisguisedReadIsRefused(String path) throws IOException {
        assertThat(status("GET", path, GOOD_HOST, false, false)).isEqualTo(400);
    }

    @Test
    void aMissingHostIsRefused() throws IOException {
        String path = "/api/v1/runs/" + run + "/approvals/" + approvalId;
        // HTTP/1.1 without Host is refused by Tomcat itself; HTTP/1.0 reaches the filter.
        assertThat(status("POST", path, null, true, true)).isEqualTo(400);
        assertThat(raw("POST " + path + " HTTP/1.0\r\nContent-Type: application/json\r\n"
                        + ApiRequestGuardFilter.CSRF_HEADER + ": 1\r\nContent-Length: " + APPROVE.length()
                        + "\r\n\r\n" + APPROVE))
                .isEqualTo(400);
        assertThat(approvalStatus()).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void aCanonicalRequestWithTheRightHeadersStillWorks() throws IOException {
        assertThat(status("GET", "/api/v1/ping", GOOD_HOST, false, false)).isEqualTo(200);
        assertThat(status("POST", "/api/v1/runs/" + run + "/approvals/" + approvalId, GOOD_HOST, true, true))
                .isEqualTo(200);
        assertThat(approvalStatus()).isEqualTo(ApprovalStatus.APPROVED);
    }

    @Test
    void healthIsReachableAndEveryOtherPathChecksTheHost() throws IOException {
        assertThat(status("GET", "/actuator/health", GOOD_HOST, false, false)).isEqualTo(200);
        assertThat(status("GET", "/actuator/health/liveness", GOOD_HOST, false, false))
                .isEqualTo(200);
        assertThat(status("GET", "/actuator/health", EVIL_HOST, false, false)).isEqualTo(200);
        assertThat(status("GET", "/actuator/info", EVIL_HOST, false, false)).isEqualTo(400);
        assertThat(status("GET", "/api/v1/ping", EVIL_HOST, false, false)).isEqualTo(400);
    }

    private ApprovalStatus approvalStatus() {
        return approvals.find(approvalId).orElseThrow().status();
    }

    private int status(String method, String path, @Nullable String host, boolean json, boolean csrf)
            throws IOException {
        StringBuilder request = new StringBuilder(method + " " + path + " HTTP/1.1\r\n");
        if (host != null) {
            request.append("Host: ").append(host).append("\r\n");
        }
        if (json) {
            request.append("Content-Type: application/json\r\n");
        }
        if (csrf) {
            request.append(ApiRequestGuardFilter.CSRF_HEADER).append(": 1\r\n");
        }
        String body = method.equals("POST") ? APPROVE : "";
        request.append("Content-Length: ")
                .append(body.length())
                .append("\r\nConnection: close\r\n\r\n")
                .append(body);
        return raw(request.toString());
    }

    /** Writes the request bytes as given and returns the response status code. */
    private int raw(String request) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String statusLine = in.readLine();
            assertThat(statusLine).as("status line").isNotNull().startsWith("HTTP/1.1 ");
            return Integer.parseInt(statusLine.substring(9, 12));
        }
    }
}
