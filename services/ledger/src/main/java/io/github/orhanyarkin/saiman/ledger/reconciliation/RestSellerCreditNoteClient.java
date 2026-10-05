package io.github.orhanyarkin.saiman.ledger.reconciliation;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link SellerCreditNoteClient} over seller-api's {@code GET /internal/credit-notes/{paymentKey}}.
 *
 * <p>Answers, strictly:
 *
 * <ul>
 *   <li><b>200</b> with a well-formed body naming the same key: the credit note.
 *   <li><b>404</b> with {@code application/problem+json} of type {@value #NOT_FOUND_TYPE}: definitely none.
 *   <li>Anything else is {@link SellerCreditNoteClient.SellerUnavailableException}: IO errors, timeouts, 429 and
 *       5xx are transient (retried with jittered exponential backoff and counted by the circuit breaker); any other
 *       status (a 400 from the seller's Host guard means a misconfiguration), an untyped 404, a malformed or
 *       oversized body, or an open breaker are not retried. None of them is ever read as "no credit note".
 * </ul>
 *
 * Redirects are never followed; responses are capped at {@value #MAX_RESPONSE_BYTES} bytes; the payment key is
 * checked against a strict pattern and placed in a literal URI (never template-encoded, so its colons stay as they
 * are). Errors carry fixed messages, never response text. Each call runs as {@code Retry(CircuitBreaker(call))} inside
 * the observation {@code saiman.ledger.seller.credit_note.lookup}, and counts {@code
 * saiman.ledger.seller.credit_note_lookups{outcome}}.
 *
 * <p>Every request carries the ledger's service token ({@code Authorization: Bearer}, ADR-0023; seller-api grants
 * {@code /internal/credit-notes/**} to {@code SERVICE_ledger} only). A 401 or 403 is "unavailable" like any other
 * unexpected status: the payment stays PENDING until the token is fixed.
 */
public class RestSellerCreditNoteClient implements SellerCreditNoteClient {

    /** Problem type of seller-api's "no credit note for this key" (seller-api {@code CreditNoteLookupController}). */
    static final String NOT_FOUND_TYPE = "urn:saiman:seller-api:credit-note-not-found";

    static final int MAX_RESPONSE_BYTES = 4096;

    /** The ledger's payment keys: Base Sepolia test USDC, lower-case (V3 constraints). */
    static final Pattern PAYMENT_KEY =
            Pattern.compile("eip155:84532:0x036cbd53842c5426634e7929541ec2318f3dcf7e:0x[0-9a-f]{40}:0x[0-9a-f]{64}");

    private static final Pattern TX_HASH = Pattern.compile("0x[0-9a-fA-F]{64}");
    private static final long MAX_AMOUNT = 9_007_199_254_740_991L;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String baseUrl;
    /** {@code Bearer <token>}; never logged, never part of an exception message. */
    private final String authorization;

    private final RestClient client;
    private final Retry retry;
    private final CircuitBreaker breaker;
    private final ObservationRegistry observations;
    private final MeterRegistry meters;

    public RestSellerCreditNoteClient(SellerProperties config, ObservationRegistry observations, MeterRegistry meters) {
        this.baseUrl = config.baseUrl();
        this.authorization = "Bearer " + config.requireServiceToken();
        this.observations = observations;
        this.meters = meters;
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withRedirects(HttpRedirects.DONT_FOLLOW)
                .withConnectTimeout(config.connectTimeout())
                .withReadTimeout(config.readTimeout());
        this.client = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(settings))
                .observationRegistry(observations)
                .build();
        this.breaker = CircuitBreaker.of(
                "seller-credit-notes",
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(5)
                        .failureRateThreshold(60)
                        .waitDurationInOpenState(config.circuitOpenWait())
                        .recordException(t -> t instanceof TransientSellerException)
                        .ignoreException(t -> !(t instanceof TransientSellerException))
                        .build());
        this.retry = Retry.of(
                "seller-credit-notes",
                RetryConfig.<Object>custom()
                        .maxAttempts(config.retryAttempts())
                        .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(config.retryWait(), 2.0, 0.5))
                        .retryOnException(t -> t instanceof TransientSellerException)
                        .build());
    }

    @Override
    public Optional<SellerCreditNote> find(String paymentKey) {
        if (!PAYMENT_KEY.matcher(paymentKey).matches()) {
            // Every ledger key matches (V3); anything else is a bug, not a seller answer.
            throw new IllegalArgumentException("not a ledger payment key");
        }
        URI uri = URI.create(baseUrl + "/internal/credit-notes/" + paymentKey);
        Supplier<Optional<SellerCreditNote>> guarded =
                CircuitBreaker.decorateSupplier(breaker, () -> exchange(uri, paymentKey));
        Supplier<Optional<SellerCreditNote>> retried = Retry.decorateSupplier(retry, guarded);
        String[] outcome = {"unavailable"};
        try {
            return Observation.createNotStarted("saiman.ledger.seller.credit_note.lookup", observations)
                    .observe(() -> {
                        try {
                            Optional<SellerCreditNote> found = retried.get();
                            outcome[0] = found.isPresent() ? "found" : "not_found";
                            return found;
                        } catch (CallNotPermittedException e) {
                            outcome[0] = "circuit_open";
                            throw new SellerUnavailableException("seller circuit is open");
                        } catch (TransientSellerException e) {
                            throw new SellerUnavailableException(String.valueOf(e.getMessage()));
                        }
                    });
        } finally {
            meters.counter("saiman.ledger.seller.credit_note_lookups", "outcome", outcome[0])
                    .increment();
        }
    }

    private Optional<SellerCreditNote> exchange(URI uri, String paymentKey) {
        Answer answer;
        try {
            answer = client.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .exchange((req, resp) -> {
                        int status = resp.getStatusCode().value();
                        if (status == 429 || status >= 500) {
                            throw new TransientSellerException("seller answered HTTP " + status);
                        }
                        // 401/403 (wrong or missing service token) and any other status: no definite answer, so the
                        // payment stays PENDING; never "no credit note", never a mismatch.
                        if (status != 200 && status != 404) {
                            throw new SellerUnavailableException("seller answered HTTP " + status);
                        }
                        MediaType type = resp.getHeaders().getContentType();
                        return new Answer(status, type, parse(readBounded(resp.getBody())));
                    });
        } catch (ResourceAccessException e) {
            throw new TransientSellerException(
                    "seller request failed (" + e.getClass().getSimpleName() + ")");
        }
        if (answer == null) {
            throw malformed();
        }
        JsonNode root = answer.body();
        if (answer.status() == 404) {
            boolean typed = answer.type() != null
                    && MediaType.APPLICATION_PROBLEM_JSON.equalsTypeAndSubtype(answer.type())
                    && NOT_FOUND_TYPE.equals(text(root, "type"));
            if (!typed) {
                throw new SellerUnavailableException("seller answered an untyped 404");
            }
            return Optional.empty();
        }
        String key = text(root, "paymentKey");
        String tx = text(root, "txHash");
        JsonNode amount = root.get("amountAtomic");
        if (!paymentKey.equals(key)
                || tx == null
                || !TX_HASH.matcher(tx).matches()
                || amount == null
                || !amount.isIntegralNumber()
                || !amount.canConvertToLong()) {
            throw malformed();
        }
        long atomic = amount.asLong();
        if (atomic <= 0 || atomic > MAX_AMOUNT) {
            throw malformed();
        }
        return Optional.of(new SellerCreditNote(tx.toLowerCase(Locale.ROOT), atomic));
    }

    private record Answer(int status, @Nullable MediaType type, JsonNode body) {}

    /** A JSON object, else {@link SellerUnavailableException}. */
    private static JsonNode parse(byte[] payload) {
        JsonNode root;
        try {
            root = JSON.readTree(payload);
        } catch (RuntimeException e) {
            throw malformed();
        }
        if (root == null || !root.isObject()) {
            throw malformed();
        }
        return root;
    }

    private static @Nullable String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node != null && node.isString() ? node.asString() : null;
    }

    private static byte[] readBounded(InputStream in) {
        try {
            byte[] bytes = in.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new SellerUnavailableException("seller response too large");
            }
            return bytes;
        } catch (IOException e) {
            throw new TransientSellerException("seller response could not be read");
        }
    }

    private static SellerUnavailableException malformed() {
        return new SellerUnavailableException("seller response malformed");
    }

    /** IO errors, 429 and 5xx: retried and counted by the breaker. */
    static final class TransientSellerException extends SellerUnavailableException {
        TransientSellerException(String message) {
            super(message);
        }
    }
}
