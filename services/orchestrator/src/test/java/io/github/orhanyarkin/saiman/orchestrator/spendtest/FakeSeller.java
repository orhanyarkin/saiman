package io.github.orhanyarkin.saiman.orchestrator.spendtest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.orhanyarkin.x402.core.AssetAmount;
import io.github.orhanyarkin.x402.core.Eip3009Authorization;
import io.github.orhanyarkin.x402.core.PaymentPayload;
import io.github.orhanyarkin.x402.core.PaymentRequired;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.ResourceInfo;
import io.github.orhanyarkin.x402.core.SettlementResponse;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.orhanyarkin.x402.core.X402Headers;
import io.github.orhanyarkin.x402.evm.Eip3009TypedData;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A real-socket x402 seller stub on loopback: answers 402 with a configurable offer, verifies the
 * EIP-3009 signature on the paid retry (no facilitator, no chain) and then settles, fails with 500 or
 * redirects. A second listener on {@code 127.0.0.2} is the redirect target and counts what reaches
 * it. One shared instance per JVM, so every spend test class can share one Spring context.
 */
public final class FakeSeller {

    public static final String PAY_TO = "0x209693Bc6afc0C5328bA36FaF03C514EF312287C";
    public static final String PAY_TO_2 = "0x1111111111111111111111111111111111111111";
    public static final String NOT_ALLOWED_PAY_TO = "0x2222222222222222222222222222222222222222";

    /** One request that reached the seller: method, raw path and whether it carried a signature. */
    public record SeenRequest(String method, String path, boolean signed) {}

    /** What the seller does with a request that carries {@code PAYMENT-SIGNATURE}. */
    public enum PaidMode {
        SETTLE,
        FAIL_500,
        REDIRECT
    }

    /** What a settled paid request answers with, unless a test sets {@link #paidBody(String)}. */
    public static final String DEFAULT_PAID_BODY = "{\"answer\":\"ok\"}";

    /** The free catalogue ({@code GET /v1/tickers}) by default. */
    public static final List<String> DEFAULT_TICKERS = List.of("THYAO", "ASELS", "GARAN");

    private static final FakeSeller SHARED = new FakeSeller();

    private final X402Codec codec = new X402Codec();
    private final HttpServer server;
    private final HttpServer redirectTarget;
    private volatile long price = 10_000;
    private volatile String payTo = PAY_TO;
    private volatile PaidMode paidMode = PaidMode.SETTLE;
    private volatile boolean redirectUnpaid;
    private volatile String paidBody = DEFAULT_PAID_BODY;
    private volatile List<String> tickers = DEFAULT_TICKERS;
    private volatile boolean tickersFail;
    private final Map<String, Long> pricePerPath = new ConcurrentHashMap<>();
    private final Map<String, String> payToPerPath = new ConcurrentHashMap<>();
    private final List<SeenRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger tickerRequests = new AtomicInteger();
    private final List<String> paidRequestLines = new CopyOnWriteArrayList<>();
    private final List<String> paidRequestBodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger unpaidRequests = new AtomicInteger();
    private final AtomicInteger paidRequests = new AtomicInteger();
    private final AtomicInteger invalidSignatures = new AtomicInteger();
    private final AtomicInteger targetRequests = new AtomicInteger();
    private final AtomicInteger targetSignatures = new AtomicInteger();
    private final AtomicLong txCounter = new AtomicLong();

    private FakeSeller() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            redirectTarget = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.2"), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        redirectTarget.createContext("/", exchange -> {
            targetRequests.incrementAndGet();
            if (exchange.getRequestHeaders().containsKey(X402Headers.PAYMENT_SIGNATURE)) {
                targetSignatures.incrementAndGet();
            }
            write(exchange, 200, "{}");
        });
        redirectTarget.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        redirectTarget.start();
    }

    public static FakeSeller shared() {
        return SHARED;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        price = 10_000;
        payTo = PAY_TO;
        paidMode = PaidMode.SETTLE;
        redirectUnpaid = false;
        paidBody = DEFAULT_PAID_BODY;
        tickers = DEFAULT_TICKERS;
        tickersFail = false;
        pricePerPath.clear();
        payToPerPath.clear();
        requests.clear();
        tickerRequests.set(0);
        paidRequestLines.clear();
        paidRequestBodies.clear();
        unpaidRequests.set(0);
        paidRequests.set(0);
        invalidSignatures.set(0);
        targetRequests.set(0);
        targetSignatures.set(0);
    }

    public void price(long atomic) {
        this.price = atomic;
    }

    public void payTo(String address) {
        this.payTo = address;
    }

    /** Offers {@code atomic} for requests to exactly {@code rawPath} (other paths keep {@link #price}). */
    public void price(String rawPath, long atomic) {
        pricePerPath.put(rawPath, atomic);
    }

    /** Offers {@code address} as payee for requests to exactly {@code rawPath}. */
    public void payTo(String rawPath, String address) {
        payToPerPath.put(rawPath, address);
    }

    /** Every request that reached this seller (free and paid), in arrival order. */
    public List<SeenRequest> requests() {
        return List.copyOf(requests);
    }

    public void paidMode(PaidMode mode) {
        this.paidMode = mode;
    }

    public void redirectUnpaid(boolean redirect) {
        this.redirectUnpaid = redirect;
    }

    /** The JSON body a settled paid request answers with (untrusted tool output in the tests). */
    public void paidBody(String json) {
        this.paidBody = json;
    }

    /** The free ticker catalogue. */
    public void tickers(List<String> tickers) {
        this.tickers = List.copyOf(tickers);
    }

    /** Makes {@code GET /v1/tickers} answer 503. */
    public void tickersFail(boolean fail) {
        this.tickersFail = fail;
    }

    public int tickerRequests() {
        return tickerRequests.get();
    }

    /** {@code "METHOD /path"} of every request that carried a valid signature and settled, in order. */
    public List<String> paidRequestLines() {
        return List.copyOf(paidRequestLines);
    }

    /** The request bodies of those requests (empty for GET). */
    public List<String> paidRequestBodies() {
        return List.copyOf(paidRequestBodies);
    }

    public int unpaidRequests() {
        return unpaidRequests.get();
    }

    /** Requests that arrived carrying a {@code PAYMENT-SIGNATURE} header. */
    public int paidRequests() {
        return paidRequests.get();
    }

    public int invalidSignatures() {
        return invalidSignatures.get();
    }

    public int redirectTargetRequests() {
        return targetRequests.get();
    }

    public int redirectTargetSignatures() {
        return targetSignatures.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String rawPath = exchange.getRequestURI().getRawPath();
        requests.add(new SeenRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getRawQuery() == null
                        ? rawPath
                        : rawPath + "?" + exchange.getRequestURI().getRawQuery(),
                exchange.getRequestHeaders().containsKey(X402Headers.PAYMENT_SIGNATURE)));
        long offerPrice = pricePerPath.getOrDefault(rawPath, price);
        String offerPayTo = payToPerPath.getOrDefault(rawPath, payTo);
        if ("GET".equals(exchange.getRequestMethod())
                && "/v1/tickers".equals(exchange.getRequestURI().getRawPath())) {
            tickerRequests.incrementAndGet();
            if (tickersFail) {
                write(exchange, 503, "{}");
                return;
            }
            StringBuilder json = new StringBuilder("{\"tickers\":[");
            for (int i = 0; i < tickers.size(); i++) {
                json.append(i == 0 ? "" : ",")
                        .append("{\"ticker\":\"")
                        .append(tickers.get(i))
                        .append("\",\"documents\":3}");
            }
            write(exchange, 200, json.append("],\"dataSource\":\"fixture\"}").toString());
            return;
        }
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        String signature = exchange.getRequestHeaders().getFirst(X402Headers.PAYMENT_SIGNATURE);
        String url = baseUrl() + exchange.getRequestURI().getRawPath();
        if (signature == null) {
            unpaidRequests.incrementAndGet();
            if (redirectUnpaid) {
                redirect(exchange);
                return;
            }
            PaymentRequirements offer = new PaymentRequirements(
                    TestnetAssets.SCHEME_EXACT,
                    TestnetAssets.NETWORK,
                    Long.toString(offerPrice),
                    TestnetAssets.USDC_ADDRESS,
                    offerPayTo,
                    60,
                    Map.of("name", TestnetAssets.USDC_NAME, "version", TestnetAssets.USDC_VERSION));
            String header = codec.encodePaymentRequired(new PaymentRequired(
                    2, null, new ResourceInfo(url, null, null, null, null, null), List.of(offer), null));
            exchange.getResponseHeaders().add(X402Headers.PAYMENT_REQUIRED, header);
            write(exchange, 402, "{}");
            return;
        }
        paidRequests.incrementAndGet();
        PaymentPayload payload = codec.decodePaymentPayload(signature);
        Eip3009Authorization authorization = payload.payload().authorization();
        boolean valid = Eip3009TypedData.verify(authorization, payload.payload().signature())
                && authorization.to().equalsIgnoreCase(offerPayTo)
                && AssetAmount.parse(authorization.value()).atomicUnits() == offerPrice;
        if (!valid) {
            invalidSignatures.incrementAndGet();
            write(exchange, 402, "{}");
            return;
        }
        switch (paidMode) {
            case FAIL_500 -> write(exchange, 500, "{}");
            case REDIRECT -> redirect(exchange);
            case SETTLE -> {
                paidRequestLines.add(exchange.getRequestMethod() + " "
                        + exchange.getRequestURI().getRawPath());
                paidRequestBodies.add(new String(requestBody, StandardCharsets.UTF_8));
                String tx = "0x" + String.format("%064x", txCounter.incrementAndGet());
                SettlementResponse settled = new SettlementResponse(
                        true,
                        null,
                        null,
                        authorization.from(),
                        tx,
                        TestnetAssets.NETWORK,
                        authorization.value(),
                        null,
                        null,
                        null);
                exchange.getResponseHeaders()
                        .add(X402Headers.PAYMENT_RESPONSE, codec.encodeSettlementResponse(settled));
                write(exchange, 200, paidBody);
            }
        }
    }

    private void redirect(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders()
                .add(
                        "Location",
                        "http://127.0.0.2:" + redirectTarget.getAddress().getPort() + "/steal");
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void write(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(bytes);
        }
    }
}
