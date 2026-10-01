package io.github.orhanyarkin.saiman.evmrpc;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.web3j.crypto.Hash;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link BaseSepoliaUsdc} over plain JSON-RPC (ADR-0018). Read-only; talks to one validated endpoint only.
 *
 * <p>Safety posture: {@code eth_chainId} must be 84532 (a different chain fails construction; an unreachable node
 * defers the check to the first call, which then fails closed until it passes); redirects are never followed;
 * responses are capped at {@value #MAX_RESPONSE_BYTES} bytes; every number is range-checked; only logs emitted by
 * the USDC contract are read. Every failure surfaces as {@link ChainUnavailableException} with a fixed message
 * (response text is never echoed). Each call runs as {@code Retry(CircuitBreaker(RateLimiter(call)))}: IO errors,
 * 429 and 5xx are retried with jittered backoff, JSON-RPC errors and malformed answers are not.
 */
public class JsonRpcBaseSepoliaUsdc implements BaseSepoliaUsdc {

    /** Base Sepolia chain id. */
    public static final long CHAIN_ID = 84532L;

    /** Circle's test USDC on Base Sepolia. */
    public static final String USDC = "0x036CbD53842c5426634e7929541eC2318f3dCF7e";

    static final int MAX_RESPONSE_BYTES = 1_000_000;

    /** First four bytes of {@code keccak256("authorizationState(address,bytes32)")}. */
    static final String AUTHORIZATION_STATE_SELECTOR = selector("authorizationState(address,bytes32)");

    /** {@code keccak256("Transfer(address,address,uint256)")}. */
    static final String TRANSFER_TOPIC = topic("Transfer(address,address,uint256)");

    /** {@code keccak256("AuthorizationUsed(address,bytes32)")}. */
    static final String AUTHORIZATION_USED_TOPIC = topic("AuthorizationUsed(address,bytes32)");

    private static final Logger LOG = LoggerFactory.getLogger(JsonRpcBaseSepoliaUsdc.class);
    private static final Pattern ADDRESS = Pattern.compile("0x[0-9a-fA-F]{40}");
    private static final Pattern BYTES32 = Pattern.compile("0x[0-9a-fA-F]{64}");
    private static final Pattern QUANTITY = Pattern.compile("0x[0-9a-fA-F]{1,16}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final URI endpoint;
    private final RestClient client;
    private final int chunkBlocks;
    private final long maxLogRange;
    private final Retry retry;
    private final CircuitBreaker breaker;
    private final RateLimiter limiter;
    private final AtomicLong ids = new AtomicLong();
    private volatile boolean chainVerified;

    public JsonRpcBaseSepoliaUsdc(ChainProperties config) {
        this.endpoint = URI.create(config.rpcUrl());
        this.chunkBlocks = config.logChunkBlocks();
        this.maxLogRange = config.maxLogRangeBlocks();
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withRedirects(HttpRedirects.DONT_FOLLOW)
                .withConnectTimeout(config.connectTimeout())
                .withReadTimeout(config.readTimeout());
        this.client = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(settings))
                .build();
        this.limiter = RateLimiter.of(
                "base-sepolia-rpc",
                RateLimiterConfig.custom()
                        .limitForPeriod(1)
                        .limitRefreshPeriod(Duration.ofMillis(Math.max(1, 1000L / config.maxRequestsPerSecond())))
                        .timeoutDuration(Duration.ofSeconds(10))
                        .build());
        this.breaker = CircuitBreaker.of(
                "base-sepolia-rpc",
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(5)
                        .failureRateThreshold(60)
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .recordException(t -> t instanceof TransientChainException)
                        .ignoreException(t -> !(t instanceof TransientChainException))
                        .build());
        IntervalFunction backoff = IntervalFunction.ofExponentialRandomBackoff(config.retryWait(), 2.0, 0.5);
        this.retry = Retry.of(
                "base-sepolia-rpc",
                RetryConfig.<Object>custom()
                        .maxAttempts(config.retryAttempts())
                        .intervalFunction(backoff)
                        .retryOnException(t -> t instanceof TransientChainException)
                        .build());
        try {
            verifyChain();
        } catch (ChainUnavailableException e) {
            LOG.warn("Base Sepolia RPC not reachable at startup; the chain id is verified on the first call");
        }
    }

    @Override
    public ChainBlock block(BlockTag tag) {
        ensureChain();
        String name = tag == BlockTag.SAFE ? "safe" : "latest";
        JsonNode block = call("eth_getBlockByNumber", List.of(name, false));
        if (!block.isObject()) {
            throw malformed();
        }
        return new ChainBlock(quantity(block.get("number")), quantity(block.get("timestamp")));
    }

    @Override
    public boolean authorizationState(String authorizer, String nonce, long blockNumber) {
        requireMatch(ADDRESS, authorizer);
        requireMatch(BYTES32, nonce);
        if (blockNumber < 0) {
            throw new IllegalArgumentException("block number must not be negative");
        }
        ensureChain();
        String data =
                AUTHORIZATION_STATE_SELECTOR + pad(authorizer) + strip0x(nonce).toLowerCase(Locale.ROOT);
        JsonNode result =
                call("eth_call", List.of(Map.of("to", USDC, "data", data), "0x" + Long.toHexString(blockNumber)));
        if (!result.isString() || !BYTES32.matcher(result.asString()).matches()) {
            throw malformed();
        }
        BigInteger value = new BigInteger(strip0x(result.asString()), 16);
        if (value.equals(BigInteger.ZERO)) {
            return false;
        }
        if (value.equals(BigInteger.ONE)) {
            return true;
        }
        throw malformed();
    }

    @Override
    public Optional<UsdcReceipt> receipt(String txHash) {
        requireMatch(BYTES32, txHash);
        ensureChain();
        JsonNode result = call("eth_getTransactionReceipt", List.of(txHash));
        if (result.isNull()) {
            return Optional.empty();
        }
        if (!result.isObject()) {
            throw malformed();
        }
        String status = text(result.get("status"));
        if (!status.equals("0x1") && !status.equals("0x0")) {
            throw malformed();
        }
        long blockNumber = quantity(result.get("blockNumber"));
        JsonNode logs = result.get("logs");
        if (logs == null || !logs.isArray()) {
            throw malformed();
        }
        List<UsdcTransfer> transfers = new ArrayList<>();
        List<String> authorizations = new ArrayList<>();
        for (JsonNode log : logs) {
            if (!USDC.equalsIgnoreCase(text(log.get("address")))) {
                continue; // only the USDC contract's logs count
            }
            JsonNode topics = log.get("topics");
            if (topics == null || !topics.isArray() || topics.isEmpty()) {
                continue;
            }
            String topic0 = text(topics.get(0)).toLowerCase(Locale.ROOT);
            if (topic0.equals(TRANSFER_TOPIC) && topics.size() == 3) {
                transfers.add(new UsdcTransfer(
                        addressTopic(topics.get(1)), addressTopic(topics.get(2)), uint64Data(log.get("data"))));
            } else if (topic0.equals(AUTHORIZATION_USED_TOPIC) && topics.size() == 3) {
                authorizations.add(addressTopic(topics.get(1)) + ":" + bytes32(topics.get(2)));
            }
        }
        return Optional.of(new UsdcReceipt(
                txHash.toLowerCase(Locale.ROOT), blockNumber, status.equals("0x1"), transfers, authorizations));
    }

    @Override
    public Optional<String> findAuthorizationTx(String authorizer, String nonce, long fromBlock, long toBlock) {
        requireMatch(ADDRESS, authorizer);
        requireMatch(BYTES32, nonce);
        if (fromBlock < 0 || toBlock < fromBlock) {
            return Optional.empty();
        }
        if (toBlock - fromBlock + 1 > maxLogRange) {
            throw new IllegalArgumentException("block range exceeds saiman.chain.max-log-range-blocks");
        }
        ensureChain();
        List<@Nullable String> topics =
                List.of(AUTHORIZATION_USED_TOPIC, "0x" + pad(authorizer), nonce.toLowerCase(Locale.ROOT));
        for (long start = fromBlock; start <= toBlock; start += chunkBlocks) {
            long end = Math.min(start + chunkBlocks - 1, toBlock);
            JsonNode logs = call(
                    "eth_getLogs",
                    List.of(Map.of(
                            "fromBlock",
                            "0x" + Long.toHexString(start),
                            "toBlock",
                            "0x" + Long.toHexString(end),
                            "address",
                            USDC,
                            "topics",
                            topics)));
            if (!logs.isArray()) {
                throw malformed();
            }
            for (JsonNode log : logs) {
                if (!USDC.equalsIgnoreCase(text(log.get("address")))) {
                    continue;
                }
                String tx = text(log.get("transactionHash"));
                requireMalformedUnless(BYTES32.matcher(tx).matches());
                return Optional.of(tx.toLowerCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }

    // ---- transport -------------------------------------------------------------------------------------------

    private void ensureChain() {
        if (!chainVerified) {
            verifyChain();
        }
    }

    private void verifyChain() {
        JsonNode result = call("eth_chainId", List.of());
        if (quantity(result) != CHAIN_ID) {
            throw new IllegalStateException(
                    "saiman.chain.rpc-url is not Base Sepolia (eth_chainId != " + CHAIN_ID + ")");
        }
        chainVerified = true;
    }

    private JsonNode call(String method, List<?> params) {
        Map<String, Object> request =
                Map.of("jsonrpc", "2.0", "id", ids.incrementAndGet(), "method", method, "params", params);
        Supplier<JsonNode> attempt = () -> exchange(request);
        Supplier<JsonNode> limited = RateLimiter.decorateSupplier(limiter, attempt);
        Supplier<JsonNode> guarded = CircuitBreaker.decorateSupplier(breaker, limited);
        Supplier<JsonNode> retried = Retry.decorateSupplier(retry, guarded);
        try {
            return retried.get();
        } catch (RequestNotPermitted e) {
            throw new ChainUnavailableException("chain RPC client rate limiter timed out");
        } catch (CallNotPermittedException e) {
            throw new ChainUnavailableException("chain RPC circuit is open");
        } catch (TransientChainException e) {
            throw new ChainUnavailableException(String.valueOf(e.getMessage()));
        }
    }

    private JsonNode exchange(Map<String, Object> request) {
        byte[] body;
        try {
            body = JSON.writeValueAsBytes(request);
        } catch (RuntimeException e) {
            throw new ChainUnavailableException("chain RPC request could not be encoded");
        }
        byte[] payload;
        try {
            payload = client.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((req, resp) -> {
                        int status = resp.getStatusCode().value();
                        if (status == 429 || status >= 500) {
                            throw new TransientChainException("chain RPC answered HTTP " + status);
                        }
                        if (status != 200) {
                            throw new ChainUnavailableException("chain RPC answered HTTP " + status);
                        }
                        return readBounded(resp.getBody());
                    });
        } catch (ResourceAccessException e) {
            throw new TransientChainException(
                    "chain RPC request failed (" + e.getClass().getSimpleName() + ")");
        }
        JsonNode root;
        try {
            root = JSON.readTree(payload);
        } catch (RuntimeException e) {
            throw malformed();
        }
        if (root == null || !root.isObject()) {
            throw malformed();
        }
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            throw new ChainUnavailableException("chain RPC returned an error");
        }
        JsonNode result = root.get("result");
        if (result == null) {
            throw malformed();
        }
        return result;
    }

    private static byte[] readBounded(InputStream in) {
        try {
            byte[] bytes = in.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new ChainUnavailableException("chain RPC response too large");
            }
            return bytes;
        } catch (IOException e) {
            throw new TransientChainException("chain RPC response could not be read");
        }
    }

    // ---- parsing helpers ---------------------------------------------------------------------------------------

    private static ChainUnavailableException malformed() {
        return new ChainUnavailableException("chain RPC response malformed");
    }

    private static void requireMalformedUnless(boolean ok) {
        if (!ok) {
            throw malformed();
        }
    }

    private static String text(@Nullable JsonNode node) {
        if (node == null || !node.isString()) {
            throw malformed();
        }
        return node.asString();
    }

    /** A JSON-RPC quantity ({@code 0x} + hex) that fits a non-negative long. */
    private static long quantity(@Nullable JsonNode node) {
        String hex = text(node);
        if (!QUANTITY.matcher(hex).matches()) {
            throw malformed();
        }
        BigInteger value = new BigInteger(strip0x(hex), 16);
        if (value.bitLength() > 63) {
            throw malformed();
        }
        return value.longValue();
    }

    /** A 32-byte value that must fit a long (transfer amounts); anything larger rejects the receipt. */
    private static long uint64Data(@Nullable JsonNode node) {
        String hex = text(node);
        if (!BYTES32.matcher(hex).matches()) {
            throw malformed();
        }
        BigInteger value = new BigInteger(strip0x(hex), 16);
        if (value.bitLength() > 63) {
            throw malformed();
        }
        return value.longValue();
    }

    private static String addressTopic(@Nullable JsonNode node) {
        String hex = text(node);
        if (!BYTES32.matcher(hex).matches()) {
            throw malformed();
        }
        String body = strip0x(hex).toLowerCase(Locale.ROOT);
        if (!body.startsWith("0".repeat(24))) {
            throw malformed();
        }
        return "0x" + body.substring(24);
    }

    private static String bytes32(@Nullable JsonNode node) {
        String hex = text(node);
        if (!BYTES32.matcher(hex).matches()) {
            throw malformed();
        }
        return hex.toLowerCase(Locale.ROOT);
    }

    private static String pad(String address) {
        return "0".repeat(24) + strip0x(address).toLowerCase(Locale.ROOT);
    }

    private static String strip0x(String hex) {
        return hex.substring(2);
    }

    private static void requireMatch(Pattern pattern, String value) {
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("argument is not a well-formed hex value");
        }
    }

    private static String topic(String signature) {
        return Hash.sha3String(signature).toLowerCase(Locale.ROOT);
    }

    private static String selector(String signature) {
        return topic(signature).substring(0, 10);
    }

    /** A failure worth retrying (IO, 429, 5xx); also what the breaker counts as an outage. */
    static final class TransientChainException extends ChainUnavailableException {

        TransientChainException(String message) {
            super(message);
        }
    }
}
