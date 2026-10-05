package io.github.orhanyarkin.saiman.evals.run;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * A stand-in for seller-api's {@code POST /internal/v1/eval/questions}: checks the bearer token, answers by
 * matching a question text in the body, can answer with a bare HTTP status or redirect.
 */
final class StubSeller implements AutoCloseable {

    record Reply(int status, String body, String location) {}

    private final HttpServer server;
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<String> expectedToken = new AtomicReference<>("");

    StubSeller() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/internal/v1/eval/questions", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":"
                + server.getAddress().getPort();
    }

    /** Replies 200 with an outcome JSON for the question. */
    void answer(String question, String outcome, @Nullable String answer, long costMicros, String... chunkIds) {
        StringBuilder citations = new StringBuilder("[");
        for (int i = 0; i < chunkIds.length; i++) {
            if (i > 0) {
                citations.append(',');
            }
            citations
                    .append("{\"chunkId\":\"")
                    .append(chunkIds[i])
                    .append("\",\"sourceUrl\":\"https://www.kap.org.tr/tr/Bildirim/1\",")
                    .append("\"publishedAt\":\"2023-10-18T07:00:00Z\"}");
        }
        citations.append(']');
        String answerJson = answer == null ? "null" : "\"" + answer + "\"";
        replies.put(
                question,
                new Reply(
                        200,
                        "{\"outcome\":\"" + outcome + "\",\"answer\":" + answerJson + ",\"citations\":" + citations
                                + ",\"modelCostUsdMicros\":" + costMicros + "}",
                        ""));
    }

    void status(String question, int status) {
        replies.put(question, new Reply(status, "{\"title\":\"problem\"}", ""));
    }

    void redirect(String question, String location) {
        replies.put(question, new Reply(302, "", location));
    }

    int calls() {
        return calls.get();
    }

    List<String> requests() {
        return new ArrayList<>(requests);
    }

    void reset(String token) {
        replies.clear();
        requests.clear();
        calls.set(0);
        expectedToken.set(token);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        calls.incrementAndGet();
        requests.add(body);
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (!("Bearer " + expectedToken.get()).equals(auth)) {
            respond(exchange, 401, "{\"title\":\"Unauthorized\"}", "");
            return;
        }
        Reply reply = replies.entrySet().stream()
                .filter(e -> body.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(new Reply(400, "{\"title\":\"unknown question\"}", ""));
        respond(exchange, reply.status(), reply.body(), reply.location());
    }

    private static void respond(HttpExchange exchange, int status, String json, String location) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (!location.isEmpty()) {
            exchange.getResponseHeaders().add("Location", location);
        }
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
