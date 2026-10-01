package io.github.orhanyarkin.saiman.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.orhanyarkin.saiman.ledger.reconciliation.SellerCreditNoteClient.SellerCreditNote;
import io.github.orhanyarkin.saiman.ledger.reconciliation.SellerCreditNoteClient.SellerUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The real HTTP client against a loopback JDK {@link HttpServer} (no WireMock in this codebase): only a 200 with a
 * well-formed body or a typed 404 is a definite answer; everything else is "unavailable", never "no credit note".
 */
class RestSellerCreditNoteClientTests {

    private static final String KEY =
            "eip155:84532:0x036cbd53842c5426634e7929541ec2318f3dcf7e:0x" + "12".repeat(20) + ":0x" + "34".repeat(32);
    private static final String TX = "0x" + "AB".repeat(32);
    private static final String FOUND = """
            {"paymentKey":"%s","txHash":"%s","amountAtomic":20000,"httpStatus":503,
             "reasonCode":"handler_server_error","createdAt":"2026-10-01T10:00:00Z"}""".formatted(KEY, TX);
    private static final String NOT_FOUND = """
            {"type":"urn:saiman:seller-api:credit-note-not-found","status":404,"detail":"No credit note"}""";

    private record Reply(int status, String contentType, String body, long delayMillis) {
        static Reply of(int status, String contentType, String body) {
            return new Reply(status, contentType, body, 0);
        }
    }

    private final Deque<Reply> replies = new ArrayDeque<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private RestSellerCreditNoteClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new RestSellerCreditNoteClient(
                new SellerProperties(
                        base,
                        List.of("seller-api"),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(500),
                        3,
                        Duration.ofMillis(1),
                        Duration.ofSeconds(30)),
                ObservationRegistry.NOOP,
                new SimpleMeterRegistry());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        paths.add(exchange.getRequestURI().getRawPath());
        Reply reply;
        synchronized (replies) {
            reply = replies.isEmpty() ? Reply.of(500, "text/plain", "no reply queued") : replies.poll();
        }
        if (reply.delayMillis() > 0) {
            try {
                Thread.sleep(reply.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", reply.contentType());
        if (reply.status() == 302) {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/elsewhere");
        }
        exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void queue(Reply... next) {
        synchronized (replies) {
            replies.addAll(List.of(next));
        }
    }

    @Test
    void aRecordedCreditNoteIsReturnedLowerCasedFromALiteralPath() {
        queue(Reply.of(200, "application/json", FOUND));

        assertThat(client.find(KEY)).contains(new SellerCreditNote(TX.toLowerCase(java.util.Locale.ROOT), 20_000));
        assertThat(paths).containsExactly("/internal/credit-notes/" + KEY); // colons not percent-encoded
    }

    @Test
    void onlyATypedNotFoundProblemMeansNoCreditNote() {
        queue(Reply.of(404, "application/problem+json", NOT_FOUND));
        assertThat(client.find(KEY)).isEqualTo(Optional.empty());

        queue(Reply.of(404, "text/html", "<html>not here</html>"));
        assertThatThrownBy(() -> client.find(KEY)).isInstanceOf(SellerUnavailableException.class);

        queue(Reply.of(404, "application/problem+json", "{\"type\":\"about:blank\",\"status\":404}"));
        assertThatThrownBy(() -> client.find(KEY)).isInstanceOf(SellerUnavailableException.class);
    }

    @Test
    void serverErrorsAreRetriedThenAnswered() {
        queue(
                Reply.of(503, "text/plain", ""),
                Reply.of(502, "text/plain", ""),
                Reply.of(200, "application/json", FOUND));

        assertThat(client.find(KEY)).isPresent();
        assertThat(paths).hasSize(3);
    }

    @Test
    void persistentServerErrorsAreUnavailableAfterTheLastAttempt() {
        queue(Reply.of(500, "text/plain", ""), Reply.of(500, "text/plain", ""), Reply.of(500, "text/plain", ""));

        assertThatThrownBy(() -> client.find(KEY)).isInstanceOf(SellerUnavailableException.class);
        assertThat(paths).hasSize(3);
    }

    @Test
    void aRefusedHostOrARedirectIsUnavailableAndNotRetried() {
        queue(Reply.of(400, "application/problem+json", "{\"status\":400,\"detail\":\"Host not allowed\"}"));
        assertThatThrownBy(() -> client.find(KEY)).isInstanceOf(SellerUnavailableException.class);
        assertThat(paths).hasSize(1);

        queue(Reply.of(302, "text/plain", ""));
        assertThatThrownBy(() -> client.find(KEY)).isInstanceOf(SellerUnavailableException.class);
        assertThat(paths).hasSize(2);
    }

    @Test
    void malformedOrOversizedAnswersAreUnavailable() {
        for (String body : List.of(
                FOUND.replace(KEY, KEY.replace(":0x12", ":0x13")), // another key
                FOUND.replace(TX, "0x1234"),
                FOUND.replace("20000", "0"),
                FOUND.replace("20000", "\"20000\""),
                FOUND.replace("20000", "9007199254740992"),
                "[]",
                "not json",
                "{\"pad\":\"" + "x".repeat(RestSellerCreditNoteClient.MAX_RESPONSE_BYTES) + "\"}")) {
            queue(Reply.of(200, "application/json", body));
            assertThatThrownBy(() -> client.find(KEY)).as(body).isInstanceOf(SellerUnavailableException.class);
        }
    }

    @Test
    void aSlowSellerTimesOutAsUnavailable() {
        queue(
                new Reply(200, "application/json", FOUND, 1_500),
                new Reply(200, "application/json", FOUND, 1_500),
                new Reply(200, "application/json", FOUND, 1_500));

        assertThatThrownBy(() -> client.find(KEY)).isInstanceOf(SellerUnavailableException.class);
    }

    @Test
    void aKeyThatIsNotALedgerKeyIsABugNotAQuestion() {
        assertThatThrownBy(() -> client.find(KEY.toUpperCase(java.util.Locale.ROOT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.find(KEY + "/../x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(paths).isEmpty();
    }

    @Test
    void theBaseUrlIsPinnedToTheAllowedHost() {
        assertThat(SellerProperties.of("http://seller-api:8081").baseUrl()).isEqualTo("http://seller-api:8081");
        assertThat(SellerProperties.of("http://seller-api:8081/").baseUrl()).isEqualTo("http://seller-api:8081");
        for (String bad : List.of(
                "http://evil.example:8081",
                "http://seller-api.evil.example:8081",
                "http://user:pw@seller-api:8081",
                "http://seller-api:8081/prefix",
                "http://seller-api:8081?x=1",
                "http://10.0.0.5:8081",
                "ftp://seller-api",
                "seller-api:8081")) {
            assertThatThrownBy(() -> SellerProperties.of(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
