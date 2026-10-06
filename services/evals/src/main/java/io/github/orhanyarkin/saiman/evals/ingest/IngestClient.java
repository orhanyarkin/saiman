package io.github.orhanyarkin.saiman.evals.ingest;

import io.github.orhanyarkin.saiman.shared.retrieval.RetrieveRequest;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrieveResponse;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Calls ingest's internal retrieval API ({@code POST /internal/v1/retrieve}) with a retry on transport errors
 * and 5xx (exponential back-off with jitter). The read timeout of the request factory is the per-attempt
 * timeout. No circuit breaker: this is a one-shot CLI, and a dead ingest should fail the run fast through its
 * per-item errors, not be protected from load. Only exception class names are logged, never a query.
 */
public class IngestClient {

    private static final Logger log = LoggerFactory.getLogger(IngestClient.class);

    private final RestClient client;
    private final Retry retry;

    public IngestClient(RestClient client, int attempts, Duration firstWait) {
        this.client = client;
        this.retry = Retry.of(
                "ingest",
                RetryConfig.custom()
                        .maxAttempts(Math.max(1, attempts))
                        .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(firstWait, 2.0, 0.5))
                        .retryOnException(IngestClient::isOutage)
                        .build());
    }

    public RetrieveResponse retrieve(RetrieveRequest request) {
        Supplier<RetrieveResponse> call = () -> client.post()
                .uri("/internal/v1/retrieve")
                .body(request)
                .retrieve()
                .body(RetrieveResponse.class);
        try {
            RetrieveResponse response = Retry.decorateSupplier(retry, call).get();
            if (response == null) {
                throw new IllegalStateException("empty body");
            }
            return response;
        } catch (RuntimeException e) {
            log.warn("ingest retrieve failed: {}", e.getClass().getSimpleName());
            throw new IngestUnavailableException(e);
        }
    }

    private static boolean isOutage(Throwable t) {
        return t instanceof ResourceAccessException || t instanceof HttpServerErrorException;
    }
}
