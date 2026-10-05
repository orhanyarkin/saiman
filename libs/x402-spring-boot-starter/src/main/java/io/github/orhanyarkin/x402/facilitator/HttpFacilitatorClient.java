package io.github.orhanyarkin.x402.facilitator;

import io.github.orhanyarkin.x402.core.PaymentPayload;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.SettlementResponse;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import io.github.orhanyarkin.x402.core.VerifyResponse;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.orhanyarkin.x402.core.X402CodecException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * {@link FacilitatorClient} over HTTP, built on a Spring {@link RestClient} configured with the
 * host allowlist, timeouts and redirect policy below, and Resilience4j core (circuit breaker on
 * every call, retry with jitter on {@code /verify} and {@code /supported} only -- never on {@code
 * /settle}, since retrying a settlement could double-broadcast a transaction whose first attempt's
 * response was merely lost).
 *
 * <p><b>Host allowlist (enforced in code, not configuration; ADR-0008).</b> Only two hosts are
 * ever allowed: {@code x402.org} over {@code https}, and loopback ({@code localhost}, {@code
 * 127.0.0.1}, {@code ::1}) over {@code http} or {@code https}, for a locally-run facilitator (e.g.
 * a test fixture) only. Every other host fails application startup. Redirects are never followed.
 * The rejected host is never echoed in an exception message (it may itself be attacker-influenced
 * configuration); the message states only what is allowed.
 *
 * <p><b>Startup handshake.</b> This class itself makes no network call at construction: {@code
 * RequiresPaymentRegistry} calls {@link #supported()} once at startup and requires {@code exact}
 * on {@link TestnetAssets#NETWORK} to be advertised, failing application startup otherwise (fail
 * closed, no {@code enabled} flag) -- but only when at least one {@code @RequiresPayment} handler
 * exists (the same "iff" as {@code x402.server.pay-to}), so an application with this starter on
 * the classpath but no paid endpoints never depends on facilitator reachability at startup, and a
 * test with no paid handler never needs a real (or fake) facilitator listening at all.
 *
 * <p><b>Response size cap.</b> A response body larger than {@link #MAX_RESPONSE_BYTES} is rejected
 * before it is ever passed to {@link X402Codec}.
 */
public final class HttpFacilitatorClient implements FacilitatorClient {

    /** Maximum bytes read from a facilitator HTTP response body. */
    public static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private static final Logger log = LoggerFactory.getLogger(HttpFacilitatorClient.class);

    private static final String ALLOWED_REMOTE_HOST = "x402.org";
    private static final String VERIFY_PATH = "/verify";
    private static final String SETTLE_PATH = "/settle";
    private static final String SUPPORTED_PATH = "/supported";

    private final RestClient restClient;
    private final X402Codec codec;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public HttpFacilitatorClient(
            RestClient.Builder restClientBuilder,
            String facilitatorUrl,
            Duration connectTimeout,
            Duration readTimeout,
            X402Codec codec) {
        this.codec = codec;
        URI uri = parseAndValidate(facilitatorUrl);
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = restClientBuilder
                .baseUrl(uri.toString())
                .requestFactory(requestFactory)
                .build();
        this.circuitBreaker = CircuitBreaker.of("x402-facilitator", CircuitBreakerConfig.ofDefaults());
        this.retry = Retry.of(
                "x402-facilitator-read",
                RetryConfig.custom()
                        .maxAttempts(3)
                        .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(Duration.ofMillis(200)))
                        // Retries a network error, timeout, 5xx or undecodable body (all surface as a
                        // plain FacilitatorException) -- but not an open circuit breaker (retrying
                        // would only burn attempts without ever reaching the network) and not a 4xx,
                        // including 429 (a FacilitatorClientErrorException: the facilitator rejected
                        // this exact request, so retrying it unchanged is expected to fail the same way).
                        .retryExceptions(RuntimeException.class)
                        .ignoreExceptions(CallNotPermittedException.class, FacilitatorClientErrorException.class)
                        .build());
        log.info("x402 facilitator host: {}", uri.getHost());
    }

    @Override
    public VerifyResponse verify(PaymentPayload payload, PaymentRequirements requirements) {
        return withRetryAndCircuitBreaker(() -> post(VERIFY_PATH, payload, requirements, VerifyResponse.class));
    }

    @Override
    public SettlementResponse settle(PaymentPayload payload, PaymentRequirements requirements) {
        // Never retried: see this class's Javadoc.
        return withCircuitBreaker(() -> post(SETTLE_PATH, payload, requirements, SettlementResponse.class));
    }

    @Override
    public SupportedResponse supported() {
        return withRetryAndCircuitBreaker(() -> get(SUPPORTED_PATH, SupportedResponse.class));
    }

    private <T> T withRetryAndCircuitBreaker(Supplier<T> call) {
        Supplier<T> guarded = CircuitBreaker.decorateSupplier(circuitBreaker, call);
        Supplier<T> retried = Retry.decorateSupplier(retry, guarded);
        return callAndTranslate(retried);
    }

    private <T> T withCircuitBreaker(Supplier<T> call) {
        return callAndTranslate(CircuitBreaker.decorateSupplier(circuitBreaker, call));
    }

    private <T> T callAndTranslate(Supplier<T> call) {
        try {
            return call.get();
        } catch (CallNotPermittedException circuitOpen) {
            throw new FacilitatorException(
                    "the x402 facilitator circuit breaker is open", FacilitatorException.Failure.CIRCUIT_OPEN, 0);
        } catch (FacilitatorException alreadyTranslated) {
            throw alreadyTranslated;
        } catch (RuntimeException other) {
            throw new FacilitatorException("the x402 facilitator call failed");
        }
    }

    private <T> T post(String path, PaymentPayload payload, PaymentRequirements requirements, Class<T> responseType) {
        FacilitatorPaymentRequest requestBody = new FacilitatorPaymentRequest(2, payload, requirements);
        String requestJson = codec.writeJson(requestBody);
        return restClient
                .post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestJson)
                .exchange((request, response) -> decode(response, responseType));
    }

    private <T> T get(String path, Class<T> responseType) {
        return restClient.get().uri(path).exchange((request, response) -> decode(response, responseType));
    }

    private <T> T decode(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response, Class<T> responseType)
            throws IOException {
        byte[] bytes = readBounded(response.getBody(), MAX_RESPONSE_BYTES);
        if (response.getStatusCode().is4xxClientError()) {
            throw new FacilitatorClientErrorException(
                    "the x402 facilitator rejected the request",
                    response.getStatusCode().value());
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new FacilitatorException(
                    "the x402 facilitator responded with an error status",
                    FacilitatorException.Failure.TRANSPORT,
                    response.getStatusCode().value());
        }
        try {
            return codec.readJson(new String(bytes, StandardCharsets.UTF_8), responseType);
        } catch (X402CodecException undecodable) {
            throw new FacilitatorException(
                    "the x402 facilitator response could not be decoded",
                    FacilitatorException.Failure.MALFORMED,
                    response.getStatusCode().value());
        }
    }

    private static URI parseAndValidate(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("x402.server.facilitator.url must be a valid URL");
        }
        String host = uri.getHost();
        String scheme = uri.getScheme();
        if (host == null || scheme == null) {
            throw new IllegalStateException("x402.server.facilitator.url must be an absolute http(s) URL");
        }
        boolean isLoopback = isLoopbackHost(host);
        boolean isAllowedRemote = ALLOWED_REMOTE_HOST.equalsIgnoreCase(host);
        if (isAllowedRemote) {
            if (!"https".equals(scheme)) {
                throw new IllegalStateException(
                        "x402.server.facilitator.url for " + ALLOWED_REMOTE_HOST + " must use https");
            }
        } else if (isLoopback) {
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new IllegalStateException("x402.server.facilitator.url must use http or https");
            }
        } else {
            throw new IllegalStateException("x402.server.facilitator.url host is not allowlisted: only "
                    + ALLOWED_REMOTE_HOST + " (https) and loopback (http/https) are permitted");
        }
        return uri;
    }

    private static boolean isLoopbackHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized)
                || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized)
                || "[::1]".equals(normalized);
    }

    private static byte[] readBounded(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] chunk = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("response body exceeds " + maxBytes + " bytes");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
