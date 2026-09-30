package io.github.orhanyarkin.saiman.ingest.mkk;

import io.github.orhanyarkin.saiman.ingest.IngestProperties;
import io.github.orhanyarkin.saiman.ingest.mkk.MkkDtos.BlockedDisclosure;
import io.github.orhanyarkin.saiman.ingest.mkk.MkkDtos.DisclosureDetail;
import io.github.orhanyarkin.saiman.ingest.mkk.MkkDtos.DisclosureSummary;
import io.github.orhanyarkin.saiman.ingest.mkk.MkkDtos.LastIndex;
import io.github.orhanyarkin.saiman.ingest.mkk.MkkDtos.Member;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Client for the official MKK KAP data API test environment (ADR-0010).
 *
 * <p>Every call runs through Resilience4j: {@code Retry(CircuitBreaker(RateLimiter(call)))}, so
 * each attempt, including a retry, takes a rate-limiter permit. Only 429, 5xx and transport errors
 * are retried; other 4xx answers are the caller's fault and fail at once. The credential lives in
 * the client's default header and nowhere else; exceptions carry a status code, never a header, URL
 * or body.
 */
public class MkkClient {

    private static final ParameterizedTypeReference<List<Member>> MEMBERS = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<DisclosureSummary>> SUMMARIES =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<BlockedDisclosure>> BLOCKED =
            new ParameterizedTypeReference<>() {};

    private final RestClient client;
    private final Retry retry;
    private final CircuitBreaker breaker;
    private final RateLimiter limiter;
    private final @Nullable MeterRegistry meters;

    public MkkClient(RestClient client, IngestProperties.Mkk config, @Nullable MeterRegistry meters) {
        this.client = client;
        this.meters = meters;
        long refillMillis = Math.max(1, 60_000L / Math.max(1, config.ratePerMinute()));
        // One permit per refill period instead of a burst of N per minute: a burst at a window
        // boundary could exceed the portal's 6 calls/minute.
        this.limiter = RateLimiter.of(
                "mkk",
                RateLimiterConfig.custom()
                        .limitForPeriod(1)
                        .limitRefreshPeriod(Duration.ofMillis(refillMillis))
                        .timeoutDuration(config.rateLimiterTimeout())
                        .build());
        this.breaker = CircuitBreaker.of(
                "mkk",
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(5)
                        .failureRateThreshold(60)
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .recordException(MkkClient::countsAsOutage)
                        .build());
        IntervalFunction backoff = IntervalFunction.ofExponentialRandomBackoff(config.retryWait(), 2.0, 0.3);
        long maxRetryAfter = config.maxRetryAfter().toMillis();
        this.retry = Retry.of(
                "mkk",
                RetryConfig.<Object>custom()
                        .maxAttempts(config.retryAttempts())
                        .retryOnException(MkkClient::retryable)
                        .intervalBiFunction((attempt, outcome) -> {
                            long wait = backoff.apply(attempt);
                            if (outcome.isLeft() && outcome.getLeft() instanceof MkkHttpException http) {
                                Duration retryAfter = http.retryAfter();
                                if (retryAfter != null) {
                                    wait = Math.max(wait, Math.min(retryAfter.toMillis(), maxRetryAfter));
                                }
                            }
                            return wait;
                        })
                        .build());
    }

    /** Builds the default-header {@code RestClient} for the given base URL and credential. */
    public static RestClient.Builder configure(
            RestClient.Builder builder, IngestProperties.Mkk config, String version) {
        RestClient.Builder configured = builder.baseUrl(config.baseUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, userAgent(version))
                .defaultHeader(HttpHeaders.ACCEPT, "application/json");
        String credentials = config.credentials().strip();
        if (!credentials.isEmpty()) {
            configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + credentials);
        }
        return configured;
    }

    static String userAgent(String version) {
        return "saiman-ingest/" + version + " (+https://github.com/orhanyarkin/saiman; non-commercial research)";
    }

    public long lastDisclosureIndex() {
        LastIndex last = call("lastDisclosureIndex", () -> get("/lastDisclosureIndex", LastIndex.class));
        try {
            return Long.parseLong(last.lastDisclosureIndex().strip());
        } catch (NumberFormatException e) {
            throw new MkkException("MKK returned an unreadable lastDisclosureIndex");
        }
    }

    public List<Member> members() {
        return call("members", () -> get("/members", MEMBERS));
    }

    /**
     * One page of the windowed listing: a bounded, variable index window starting at {@code
     * fromIndex}, matches only (at most 50). An empty page does not mean the end.
     */
    public List<DisclosureSummary> disclosures(long fromIndex, long companyId) {
        return call(
                "disclosures",
                () -> client.get()
                        .uri("/disclosures?disclosureIndex={i}&companyId={c}", fromIndex, companyId)
                        .retrieve()
                        .onStatus(status -> status.isError(), (request, response) -> {
                            throw httpError(response.getStatusCode().value(), response.getHeaders());
                        })
                        .body(SUMMARIES));
    }

    public DisclosureDetail disclosureDetail(long disclosureIndex) {
        return call(
                "disclosureDetail",
                () -> get("/disclosureDetail/" + disclosureIndex + "?fileType=html", DisclosureDetail.class));
    }

    public List<BlockedDisclosure> blockedDisclosures() {
        return call("blockedDisclosures", () -> get("/blockedDisclosures", BLOCKED));
    }

    private <T> @Nullable T get(String path, Class<T> type) {
        return client.get()
                .uri(path)
                .retrieve()
                .onStatus(status -> status.isError(), (request, response) -> {
                    throw httpError(response.getStatusCode().value(), response.getHeaders());
                })
                .body(type);
    }

    private <T> @Nullable T get(String path, ParameterizedTypeReference<T> type) {
        return client.get()
                .uri(path)
                .retrieve()
                .onStatus(status -> status.isError(), (request, response) -> {
                    throw httpError(response.getStatusCode().value(), response.getHeaders());
                })
                .body(type);
    }

    private <T> T call(String operation, Supplier<@Nullable T> request) {
        Supplier<T> attempt = () -> {
            try {
                T body = request.get();
                if (body == null) {
                    throw new MkkException("MKK returned an empty body");
                }
                return body;
            } catch (MkkException e) {
                throw e;
            } catch (ResourceAccessException e) {
                throw new MkkIoException(e.getClass().getSimpleName());
            } catch (RuntimeException e) {
                // Message conversion errors can quote the payload; keep only the class name.
                throw new MkkException(
                        "MKK response could not be read: " + e.getClass().getSimpleName());
            }
        };
        Supplier<T> limited = RateLimiter.decorateSupplier(limiter, attempt);
        Supplier<T> guarded = CircuitBreaker.decorateSupplier(breaker, limited);
        Supplier<T> retried = Retry.decorateSupplier(retry, guarded);
        try {
            T result = retried.get();
            count(operation, "ok");
            return result;
        } catch (RequestNotPermitted e) {
            count(operation, "rate_limited");
            throw new MkkException("MKK client rate limiter timed out");
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            count(operation, "circuit_open");
            throw new MkkCircuitOpenException();
        } catch (MkkHttpException e) {
            count(operation, "http_" + e.status());
            throw e;
        } catch (MkkException e) {
            count(operation, "error");
            throw e;
        }
    }

    private void count(String operation, String outcome) {
        if (meters != null) {
            meters.counter("ingest.mkk.calls", "operation", operation, "outcome", outcome)
                    .increment();
        }
    }

    private static MkkHttpException httpError(int status, HttpHeaders headers) {
        return new MkkHttpException(status, parseRetryAfter(headers.getFirst(HttpHeaders.RETRY_AFTER)));
    }

    static @Nullable Duration parseRetryAfter(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value.strip());
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException e) {
            return null; // HTTP-date form: fall back to the backoff
        }
    }

    private static boolean retryable(Throwable t) {
        if (t instanceof MkkHttpException http) {
            return http.retryable();
        }
        return t instanceof MkkIoException;
    }

    private static boolean countsAsOutage(Throwable t) {
        return retryable(t);
    }
}
