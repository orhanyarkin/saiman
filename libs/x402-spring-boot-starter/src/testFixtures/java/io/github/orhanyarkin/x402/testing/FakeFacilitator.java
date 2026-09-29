package io.github.orhanyarkin.x402.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.orhanyarkin.x402.core.AssetAmount;
import io.github.orhanyarkin.x402.core.Eip3009Authorization;
import io.github.orhanyarkin.x402.core.PaymentPayload;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.SettlementResponse;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import io.github.orhanyarkin.x402.core.VerifyResponse;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.orhanyarkin.x402.evm.Eip3009TypedData;
import io.github.orhanyarkin.x402.facilitator.SupportedKind;
import io.github.orhanyarkin.x402.facilitator.SupportedResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * An in-process x402 facilitator, implementing {@code /verify}, {@code /settle} and {@code
 * /supported} per the x402 v2 facilitator HTTP API (x402-foundation/x402 {@code
 * specs/x402-specification-v2.md}), for tests only.
 *
 * <p>Backed by the JDK's built-in {@link HttpServer} (no extra test dependency) bound to a random
 * loopback port. Start it and read {@link #url()} before building the Spring context under test
 * (e.g. via {@code @DynamicPropertySource}), so there is never an ordering question between this
 * fixture and {@code HttpFacilitatorClient}'s startup {@code /supported} handshake.
 *
 * <p><b>Behaviour.</b> Recovers the signer with {@link Eip3009TypedData#verify}, checks the
 * authorization's time window, recipient and amount against the submitted {@link
 * PaymentRequirements}, and tracks used {@code (from, nonce)} pairs -- marked used only on a
 * successful {@code /settle} (mirroring the on-chain EIP-3009 nonce, which {@code /verify} does not
 * consume). A settle or verify call for an already-used pair answers with {@code invalid_reason} /
 * {@code errorReason} {@code "invalid_transaction_state"}.
 *
 * <p><b>Failure injection.</b> {@link #injectVerifyInvalid(String)}, {@link
 * #injectSettleFailure(String)} and {@link #injectSettleDelay(Duration)} force the next calls to
 * fail or hang (the latter to exercise a facilitator timeout against a short {@code
 * x402.server.facilitator.read-timeout}); {@link #resetInjectedFailures()} clears them.
 */
public final class FakeFacilitator implements AutoCloseable {

    private final HttpServer server;
    private final X402Codec codec = new X402Codec();
    private final Set<String> usedAuthorizations = ConcurrentHashMap.newKeySet();
    private final AtomicReference<@Nullable String> injectedVerifyInvalidReason = new AtomicReference<>();
    private final AtomicReference<@Nullable String> injectedSettleFailureReason = new AtomicReference<>();
    private final AtomicReference<Boolean> injectedSettleMissingTransaction = new AtomicReference<>(false);
    private final AtomicLong injectedSettleDelayMillis = new AtomicLong();
    private final AtomicLong txHashCounter = new AtomicLong();
    private final AtomicLong verifyCallCount = new AtomicLong();
    private final AtomicLong settleCallCount = new AtomicLong();
    private final AtomicLong injectedVerifyServerErrorCount = new AtomicLong();
    private final AtomicLong injectedVerifyClientErrorCount = new AtomicLong();
    private final AtomicReference<@Nullable String> lastVerifyResourceUrl = new AtomicReference<>();
    private final AtomicReference<@Nullable String> lastSettleResourceUrl = new AtomicReference<>();

    public FakeFacilitator() {
        try {
            this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/supported", this::handleSupported);
        server.createContext("/verify", exchange -> handleVerifyOrSettle(exchange, false));
        server.createContext("/settle", exchange -> handleVerifyOrSettle(exchange, true));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    /** The facilitator base URL, e.g. {@code http://127.0.0.1:54321}. */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Forces the next {@code /verify} call(s) to answer invalid with {@code reason}. */
    public void injectVerifyInvalid(String reason) {
        injectedVerifyInvalidReason.set(reason);
    }

    /** Forces the next {@code /settle} call(s) to answer failure with {@code reason}. */
    public void injectSettleFailure(String reason) {
        injectedSettleFailureReason.set(reason);
    }

    /**
     * Forces the next {@code /settle} call to answer {@code success:true} with no {@code
     * transaction} field -- a malformed but not-outright-rejecting facilitator response, to
     * exercise the server's ambiguous-settlement handling (M1-A: this must never be treated as a
     * real success).
     */
    public void injectSettleSuccessWithoutTransaction() {
        injectedSettleMissingTransaction.set(true);
    }

    /** Delays every {@code /settle} response by {@code delay}, to exercise a client-side read timeout. */
    public void injectSettleDelay(Duration delay) {
        injectedSettleDelayMillis.set(delay.toMillis());
    }

    /**
     * Forces the next {@code count} {@code /verify} calls to answer with a raw HTTP {@code 500}
     * (no JSON body), to exercise the client's retry-on-5xx behaviour. Each call consumes one.
     */
    public void injectVerifyServerError(int count) {
        injectedVerifyServerErrorCount.set(count);
    }

    /**
     * Forces the next {@code count} {@code /verify} calls to answer with a raw HTTP {@code 429}
     * (no JSON body), to exercise the client's not-retried-on-4xx behaviour. Each call consumes one.
     */
    public void injectVerifyClientError(int count) {
        injectedVerifyClientErrorCount.set(count);
    }

    /** Clears every injected failure/delay. */
    public void resetInjectedFailures() {
        injectedVerifyInvalidReason.set(null);
        injectedSettleFailureReason.set(null);
        injectedSettleMissingTransaction.set(false);
        injectedSettleDelayMillis.set(0);
        injectedVerifyServerErrorCount.set(0);
        injectedVerifyClientErrorCount.set(0);
    }

    /** The {@code resource.url} sent in the last {@code /verify} request's payload, if any. */
    public @Nullable String lastVerifyResourceUrl() {
        return lastVerifyResourceUrl.get();
    }

    /** The {@code resource.url} sent in the last {@code /settle} request's payload, if any. */
    public @Nullable String lastSettleResourceUrl() {
        return lastSettleResourceUrl.get();
    }

    /** Resets {@link #verifyCallCount()} and {@link #settleCallCount()} to zero. Does not clear used-nonce tracking. */
    public void resetCallCounts() {
        verifyCallCount.set(0);
        settleCallCount.set(0);
    }

    /** Number of {@code /verify} calls received so far. */
    public long verifyCallCount() {
        return verifyCallCount.get();
    }

    /** Number of {@code /settle} calls received so far. */
    public long settleCallCount() {
        return settleCallCount.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handleSupported(HttpExchange exchange) throws IOException {
        SupportedResponse response = new SupportedResponse(
                List.of(new SupportedKind(2, TestnetAssets.SCHEME_EXACT, TestnetAssets.NETWORK)), List.of(), Map.of());
        writeJson(exchange, 200, codec.writeJson(response));
    }

    private void handleVerifyOrSettle(HttpExchange exchange, boolean settle) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, "{}");
            return;
        }
        if (settle) {
            settleCallCount.incrementAndGet();
        } else {
            verifyCallCount.incrementAndGet();
        }
        VerifyOrSettleRequest request;
        try {
            byte[] body = exchange.getRequestBody().readAllBytes();
            request = codec.readJson(new String(body, StandardCharsets.UTF_8), VerifyOrSettleRequest.class);
        } catch (RuntimeException malformed) {
            writeJson(exchange, 400, "{}");
            return;
        }

        var resource = request.paymentPayload().resource();
        String resourceUrl = resource == null ? null : resource.url();
        if (settle) {
            lastSettleResourceUrl.set(resourceUrl);
        } else {
            lastVerifyResourceUrl.set(resourceUrl);
        }

        if (!settle && injectedVerifyServerErrorCount.getAndUpdate(count -> count > 0 ? count - 1 : 0) > 0) {
            writeJson(exchange, 500, "{}");
            return;
        }
        if (!settle && injectedVerifyClientErrorCount.getAndUpdate(count -> count > 0 ? count - 1 : 0) > 0) {
            writeJson(exchange, 429, "{}");
            return;
        }

        Eip3009Authorization authorization = request.paymentPayload().payload().authorization();
        String signature = request.paymentPayload().payload().signature();

        if (settle) {
            long delayMillis = injectedSettleDelayMillis.get();
            if (delayMillis > 0) {
                sleepUninterruptibly(delayMillis);
            }
            String forcedFailure = injectedSettleFailureReason.get();
            if (forcedFailure != null) {
                writeJson(
                        exchange,
                        200,
                        codec.writeJson(settleFailure(request.paymentRequirements(), authorization, forcedFailure)));
                return;
            }
        } else {
            String forcedInvalid = injectedVerifyInvalidReason.get();
            if (forcedInvalid != null) {
                writeJson(exchange, 200, codec.writeJson(verifyFailure(authorization, forcedInvalid)));
                return;
            }
        }

        String rejectionReason = validate(request.paymentRequirements(), authorization, signature);
        if (rejectionReason == null && usedAuthorizations.contains(authorization.canonicalNonceKey())) {
            rejectionReason = "invalid_transaction_state";
        }

        if (settle) {
            if (rejectionReason != null) {
                writeJson(
                        exchange,
                        200,
                        codec.writeJson(settleFailure(request.paymentRequirements(), authorization, rejectionReason)));
                return;
            }
            usedAuthorizations.add(authorization.canonicalNonceKey());
            if (injectedSettleMissingTransaction.get()) {
                // Raw JSON, not the SettlementResponse record: a real hostile/malformed
                // facilitator sends bytes, not a Java object, and the record's `transaction`
                // component isn't @Nullable (NullAway would reject constructing one with a null
                // transaction) -- this is exactly the shape a facilitator omitting the field
                // produces once decoded through the server's tolerant mapper.
                writeJson(
                        exchange,
                        200,
                        "{\"success\":true,\"payer\":\"" + authorization.from() + "\",\"network\":\""
                                + request.paymentRequirements().network() + "\"}");
                return;
            }
            SettlementResponse success = new SettlementResponse(
                    true,
                    null,
                    null,
                    authorization.from(),
                    fakeTxHash(),
                    request.paymentRequirements().network(),
                    authorization.value(),
                    null,
                    null,
                    null);
            writeJson(exchange, 200, codec.writeJson(success));
        } else {
            if (rejectionReason != null) {
                writeJson(exchange, 200, codec.writeJson(verifyFailure(authorization, rejectionReason)));
                return;
            }
            VerifyResponse success = new VerifyResponse(true, null, null, authorization.from(), null, null, null);
            writeJson(exchange, 200, codec.writeJson(success));
        }
    }

    private static VerifyResponse verifyFailure(Eip3009Authorization authorization, String reason) {
        return new VerifyResponse(false, reason, null, authorization.from(), null, null, null);
    }

    private static SettlementResponse settleFailure(
            PaymentRequirements requirements, Eip3009Authorization authorization, String reason) {
        return new SettlementResponse(
                false, reason, null, authorization.from(), "", requirements.network(), null, null, null, null);
    }

    private static @Nullable String validate(
            PaymentRequirements requirements, Eip3009Authorization authorization, String signature) {
        if (!Eip3009TypedData.verify(authorization, signature)) {
            return "invalid_signature";
        }
        if (!TestnetAssets.NETWORK.equals(requirements.network())
                || !TestnetAssets.USDC_ADDRESS.equalsIgnoreCase(requirements.asset())) {
            return "invalid_network";
        }
        if (!authorization.to().equalsIgnoreCase(requirements.payTo())) {
            return "invalid_recipient";
        }
        try {
            if (AssetAmount.parse(authorization.value()).atomicUnits()
                    != AssetAmount.parse(requirements.amount()).atomicUnits()) {
                return "insufficient_funds";
            }
        } catch (RuntimeException malformedAmount) {
            return "invalid_amount";
        }
        long now = Instant.now().getEpochSecond();
        long validAfter = Long.parseLong(authorization.validAfter());
        long validBefore = Long.parseLong(authorization.validBefore());
        if (now < validAfter || now >= validBefore) {
            return "expired";
        }
        return null;
    }

    private String fakeTxHash() {
        return "0x" + String.format("%064x", txHashCounter.incrementAndGet());
    }

    private static void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(bytes);
        }
    }

    /** Mirrors the {@code /verify} and {@code /settle} request body shape; see the facilitator HTTP API spec section. */
    private record VerifyOrSettleRequest(
            int x402Version, PaymentPayload paymentPayload, PaymentRequirements paymentRequirements) {}
}
