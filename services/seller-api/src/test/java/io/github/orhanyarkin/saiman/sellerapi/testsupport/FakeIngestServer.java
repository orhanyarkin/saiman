package io.github.orhanyarkin.saiman.sellerapi.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.orhanyarkin.saiman.shared.retrieval.IndexedTicker;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrieveResponse;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrievedChunk;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/**
 * A stand-in for the ingest service's internal retrieval API (JDK {@link HttpServer} on a random
 * loopback port): {@code POST /internal/v1/retrieve} and {@code GET /internal/v1/tickers}, with a
 * scriptable response, failure injection and call counters. No database, no model, no network.
 */
public final class FakeIngestServer implements AutoCloseable {

    public static final Instant WATERMARK = Instant.parse("2023-12-29T09:30:00Z");

    private final HttpServer server;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AtomicReference<RetrieveResponse> retrieveResponse = new AtomicReference<>();
    private final AtomicReference<List<IndexedTicker>> tickers = new AtomicReference<>(List.of());
    private final AtomicReference<@Nullable Integer> failWithStatus = new AtomicReference<>();
    private final AtomicReference<Long> delayMillis = new AtomicReference<>(0L);
    private final AtomicInteger retrieveCalls = new AtomicInteger();
    private final AtomicInteger tickerCalls = new AtomicInteger();
    private final AtomicReference<@Nullable String> lastRetrieveBody = new AtomicReference<>();

    public FakeIngestServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/internal/v1/retrieve", exchange -> {
            retrieveCalls.incrementAndGet();
            lastRetrieveBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, retrieveResponse.get());
        });
        server.createContext("/internal/v1/tickers", exchange -> {
            tickerCalls.incrementAndGet();
            respond(exchange, tickers.get());
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Resets the scripted state to "ticker THYAO is indexed and retrieval returns nothing". */
    public void reset() {
        failWithStatus.set(null);
        delayMillis.set(0L);
        retrieveCalls.set(0);
        tickerCalls.set(0);
        lastRetrieveBody.set(null);
        tickers.set(List.of(new IndexedTicker("THYAO", 3, 12)));
        retrieveResponse.set(new RetrieveResponse(List.of(), WATERMARK, "v0"));
    }

    public void tickers(List<IndexedTicker> indexed) {
        tickers.set(indexed);
    }

    public void retrieves(List<RetrievedChunk> chunks, String corpusVersion) {
        retrieveResponse.set(new RetrieveResponse(chunks, WATERMARK, corpusVersion));
    }

    /** Every request answers with this HTTP status and an empty body. */
    public void failWith(int status) {
        failWithStatus.set(status);
    }

    /** Every response is held back this long first (to exercise the client's read timeout). */
    public void delayResponses(long millis) {
        delayMillis.set(millis);
    }

    public int retrieveCalls() {
        return retrieveCalls.get();
    }

    public int tickerCalls() {
        return tickerCalls.get();
    }

    public @Nullable String lastRetrieveBody() {
        return lastRetrieveBody.get();
    }

    public static RetrievedChunk chunk(String chunkId, String ticker, String text) {
        return new RetrievedChunk(
                chunkId,
                ticker,
                "kap",
                "Test disclosure " + chunkId,
                "https://www.kap.org.tr/tr/Bildirim/" + chunkId.split(":", -1)[1],
                Instant.parse("2023-06-01T10:00:00Z"),
                Instant.parse("2026-09-29T08:00:00Z"),
                text,
                0.03,
                1,
                2);
    }

    private void respond(HttpExchange exchange, Object body) throws IOException {
        try {
            Thread.sleep(delayMillis.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Integer failure = failWithStatus.get();
        if (failure != null) {
            exchange.sendResponseHeaders(failure, -1);
            exchange.close();
            return;
        }
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
