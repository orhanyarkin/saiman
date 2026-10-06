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

/**
 * A stand-in for ingest's {@code POST /internal/v1/retrieve}: answers by matching a question text inside the
 * request body, can fail the first N calls of a question and can fail one forever.
 */
final class StubIngest implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, List<String>> answers = new ConcurrentHashMap<>();
    private final Map<String, Integer> failFirst = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    final AtomicReference<String> corpusVersion = new AtomicReference<>("v-golden");

    StubIngest() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/internal/v1/retrieve", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":"
                + server.getAddress().getPort();
    }

    /** Chunk ids returned for a question. */
    void answer(String question, String... chunkIds) {
        answers.put(question, List.of(chunkIds));
    }

    /** The first {@code n} calls for the question fail with 503 (an unknown {@code n} of 999 means forever). */
    void failFirst(String question, int n) {
        failFirst.put(question, n);
    }

    int calls(String question) {
        AtomicInteger n = calls.get(question);
        return n == null ? 0 : n.get();
    }

    List<String> requests() {
        return new ArrayList<>(requests);
    }

    void reset() {
        answers.clear();
        failFirst.clear();
        calls.clear();
        requests.clear();
        corpusVersion.set("v-golden");
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(body);
        String question = answers.keySet().stream()
                .filter(q -> body.contains(escape(q)))
                .findFirst()
                .orElse(failFirst.keySet().stream()
                        .filter(q -> body.contains(escape(q)))
                        .findFirst()
                        .orElse(""));
        int call = calls.computeIfAbsent(question, q -> new AtomicInteger()).incrementAndGet();
        if (call <= failFirst.getOrDefault(question, 0)) {
            respond(exchange, 503, "{\"title\":\"unavailable\"}");
            return;
        }
        List<String> chunks = answers.get(question);
        if (chunks == null) {
            respond(exchange, 400, "{\"title\":\"unknown question\"}");
            return;
        }
        respond(exchange, 200, response(chunks));
    }

    private String response(List<String> chunkIds) {
        StringBuilder json = new StringBuilder("{\"chunks\":[");
        for (int i = 0; i < chunkIds.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"chunkId\":\"")
                    .append(chunkIds.get(i))
                    .append("\",\"ticker\":\"THYAO\",\"source\":\"kap\",")
                    .append("\"title\":\"t\",\"sourceUrl\":\"https://www.kap.org.tr/tr/Bildirim/1\",")
                    .append("\"publishedAt\":\"2023-05-01T10:00:00Z\",\"retrievedAt\":\"2026-01-01T00:00:00Z\",")
                    .append("\"text\":\"x\",\"rrfScore\":0.03,\"vectorRank\":")
                    .append(i + 1)
                    .append(",\"lexicalRank\":null}");
        }
        return json.append("],\"corpusWatermark\":\"2023-12-29T20:46:52Z\",\"corpusVersion\":\"")
                .append(corpusVersion.get())
                .append("\"}")
                .toString();
    }

    private static String escape(String text) {
        return text.replace("\"", "\\\"");
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
