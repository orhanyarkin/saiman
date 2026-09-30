package io.github.orhanyarkin.saiman.sellerapi.retrieval;

import io.github.orhanyarkin.saiman.shared.retrieval.IndexedTicker;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrieveRequest;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrieveResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Calls the ingest service's internal retrieval API. Every call runs as {@code
 * Retry(CircuitBreaker(call))}: only transport errors and 5xx are retried and count against the
 * breaker (a 4xx is our bug, not an outage). The read timeout on the underlying request factory is
 * the per-attempt timeout. Any failure surfaces as {@link RetrievalUnavailableException}; only
 * exception class names are logged, never a URL, query or body.
 */
public class IngestClient {

    private static final Logger log = LoggerFactory.getLogger(IngestClient.class);
    private static final ParameterizedTypeReference<List<IndexedTicker>> TICKERS =
            new ParameterizedTypeReference<>() {};

    private final RestClient client;
    private final Retry retry;
    private final CircuitBreaker breaker;

    public IngestClient(RestClient client, IngestProperties properties) {
        this.client = client;
        this.breaker = CircuitBreaker.of(
                "ingest",
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(5)
                        .failureRateThreshold(60)
                        .waitDurationInOpenState(properties.circuitOpenWait())
                        .recordException(IngestClient::isOutage)
                        .ignoreException(t -> !isOutage(t))
                        .build());
        this.retry = Retry.of(
                "ingest",
                RetryConfig.custom()
                        .maxAttempts(Math.max(1, properties.retryAttempts()))
                        .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(properties.retryWait(), 2.0, 0.5))
                        .retryOnException(IngestClient::isOutage)
                        .build());
    }

    /** Hybrid retrieval ({@code POST /internal/v1/retrieve}). */
    public RetrieveResponse retrieve(RetrieveRequest request) {
        return call(() -> client.post()
                .uri("/internal/v1/retrieve")
                .body(request)
                .retrieve()
                .body(RetrieveResponse.class));
    }

    /** The tickers present in the corpus ({@code GET /internal/v1/tickers}). */
    public List<IndexedTicker> tickers() {
        return call(() -> client.get().uri("/internal/v1/tickers").retrieve().body(TICKERS));
    }

    private <T> T call(Supplier<T> request) {
        Supplier<T> guarded = CircuitBreaker.decorateSupplier(breaker, request);
        Supplier<T> retried = Retry.decorateSupplier(retry, guarded);
        try {
            T result = retried.get();
            if (result == null) {
                throw new IllegalStateException("empty body");
            }
            return result;
        } catch (RuntimeException e) {
            log.warn("ingest call failed: {}", e.getClass().getSimpleName());
            throw new RetrievalUnavailableException();
        }
    }

    private static boolean isOutage(Throwable t) {
        return t instanceof ResourceAccessException || t instanceof HttpServerErrorException;
    }
}
