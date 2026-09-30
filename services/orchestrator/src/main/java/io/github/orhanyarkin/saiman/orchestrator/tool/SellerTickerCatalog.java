package io.github.orhanyarkin.saiman.orchestrator.tool;

import io.github.orhanyarkin.saiman.orchestrator.payment.SellerProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The seller's free ticker catalogue, {@code GET /v1/tickers}: which tickers the paid tools can
 * answer about. A plain {@link RestClient} without the x402 interceptor (the endpoint is free and
 * nothing here is ever paid), no redirects, bounded body, a jittered retry and a circuit breaker.
 * The seller's list is untrusted: only entries of the ticker shape are kept, at most {@value
 * #MAX_TICKERS}.
 */
@Component
public class SellerTickerCatalog {

    static final int MAX_TICKERS = 1_000;
    private static final int MAX_BODY_BYTES = 128 * 1024;
    private static final Logger LOG = LoggerFactory.getLogger(SellerTickerCatalog.class);

    private final RestClient client;
    private final JsonMapper json;
    private final CircuitBreaker breaker;
    private final Retry retry;

    public SellerTickerCatalog(RestClient.Builder builder, SellerProperties seller, JsonMapper json) {
        this.client = builder.baseUrl(seller.baseUrl().toString())
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk()
                        .build(HttpClientSettings.defaults()
                                .withRedirects(HttpRedirects.DONT_FOLLOW)
                                .withTimeouts(seller.connectTimeout(), Duration.ofSeconds(5))))
                .build();
        this.json = json;
        this.breaker = CircuitBreaker.of(
                "seller-api-tickers",
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(5)
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .build());
        this.retry = Retry.of(
                "seller-api-tickers",
                RetryConfig.custom()
                        .maxAttempts(2)
                        .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(Duration.ofMillis(200), 2, 0.5))
                        .build());
    }

    /** The catalogue, or empty if the seller could not be asked (nothing is cached across runs). */
    public Optional<Set<String>> tickers() {
        Supplier<Set<String>> call =
                Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(breaker, this::fetch));
        try {
            return Optional.of(call.get());
        } catch (RuntimeException e) {
            LOG.warn("Seller ticker catalogue unavailable ({})", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Set<String> fetch() {
        String body = client.get()
                .uri("/v1/tickers")
                .accept(MediaType.APPLICATION_JSON)
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalStateException("ticker catalogue answered "
                                + response.getStatusCode().value());
                    }
                    return readLimited(response.getBody());
                });
        if (body == null) {
            throw new IllegalStateException("ticker catalogue response is too large");
        }
        JsonNode tickers = json.readTree(body).get("tickers");
        if (tickers == null || !tickers.isArray()) {
            throw new IllegalStateException("ticker catalogue response has no tickers array");
        }
        Set<String> result = new TreeSet<>();
        for (JsonNode entry : tickers) {
            if (result.size() >= MAX_TICKERS) {
                break;
            }
            JsonNode ticker = entry.isObject() ? entry.get("ticker") : null;
            if (ticker != null
                    && ticker.isString()
                    && ToolArguments.TICKER.matcher(ticker.asString()).matches()) {
                result.add(ticker.asString());
            }
        }
        return Set.copyOf(result);
    }

    private static @Nullable String readLimited(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(MAX_BODY_BYTES + 1);
        return bytes.length > MAX_BODY_BYTES ? null : new String(bytes, StandardCharsets.UTF_8);
    }
}
