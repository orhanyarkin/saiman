package io.github.orhanyarkin.saiman.evals.answers;

import io.github.orhanyarkin.saiman.shared.eval.EvalAnswerRequest;
import io.github.orhanyarkin.saiman.shared.eval.EvalAnswerResponse;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls seller-api's {@code POST /internal/v1/eval/questions} with the evals service token. Sequential use only
 * (the seller's run guard allows one call in flight).
 *
 * <p>Retry: connect failures and 5xx other than 503, with exponential back-off and jitter. Never on a 4xx, and
 * never after a read timeout (the seller may still be running the model; a second call would hit the
 * one-in-flight guard). Mapping of failures: 401/403 to {@link SellerAuthException}, 429 and 503 (run guard
 * limit / undecidable) and a persistent transport failure to {@link SellerUnavailableException}, anything else
 * to {@link SellerCallException}. Redirects are never followed (the request factory is built with
 * {@code Redirect.NEVER}, and a 3xx is an error), so the bearer token cannot leave the configured host. The token
 * lives only in the {@code RestClient}'s default header; nothing here logs a header, a body or a question.
 */
public class SellerClient {

    private static final Logger log = LoggerFactory.getLogger(SellerClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient client;
    private final Retry retry;

    public SellerClient(RestClient client, int attempts, Duration firstWait) {
        this.client = client;
        this.retry = Retry.of(
                "seller",
                RetryConfig.custom()
                        .maxAttempts(Math.max(1, attempts))
                        .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(firstWait, 2.0, 0.5))
                        .retryOnException(SellerClient::isRetriable)
                        .build());
    }

    public EvalAnswerResponse answer(EvalAnswerRequest request) {
        Supplier<EvalAnswerResponse> call = () -> client.post()
                .uri("/internal/v1/eval/questions")
                // Serialised up front: a byte[] body is sent with a Content-Length, which seller-api's body-size
                // filter requires (it answers 413 to a chunked body of unknown length).
                .contentType(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsBytes(request))
                .retrieve()
                .onStatus(HttpStatusCode::is3xxRedirection, (req, res) -> {
                    throw new SellerCallException(res.getStatusCode().value(), "redirect refused");
                })
                .body(EvalAnswerResponse.class);
        try {
            EvalAnswerResponse response = Retry.decorateSupplier(retry, call).get();
            if (response == null) {
                throw new SellerCallException(200, "empty body");
            }
            return response;
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            log.warn("seller answer call failed: HTTP {}", status);
            if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
                throw new SellerAuthException(status);
            }
            if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
                throw new SellerUnavailableException("seller-api run guard limit reached (HTTP 429)", status, null);
            }
            throw new SellerCallException(status, "rejected");
        } catch (HttpServerErrorException e) {
            int status = e.getStatusCode().value();
            log.warn("seller answer call failed: HTTP {}", status);
            if (status == HttpStatus.SERVICE_UNAVAILABLE.value()) {
                throw new SellerUnavailableException(
                        "seller-api run guard could not decide or the service is unavailable (HTTP 503)", status, null);
            }
            throw new SellerCallException(status, "server error");
        } catch (ResourceAccessException e) {
            log.warn(
                    "seller answer call failed: {}",
                    e.getCause() == null ? "io" : e.getCause().getClass().getSimpleName());
            throw new SellerCallException(0, "transport failure");
        }
    }

    private static boolean isRetriable(Throwable t) {
        if (t instanceof HttpServerErrorException e) {
            return e.getStatusCode().value() != HttpStatus.SERVICE_UNAVAILABLE.value();
        }
        if (t instanceof ResourceAccessException e) {
            return e.getCause() instanceof ConnectException || e.getCause() instanceof HttpConnectTimeoutException;
        }
        return false;
    }
}
