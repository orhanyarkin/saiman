package io.github.orhanyarkin.saiman.orchestrator.payment;

import io.github.orhanyarkin.x402.core.X402Headers;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Sits inside the x402 interceptor and keeps two facts the interceptor's exceptions don't carry:
 *
 * <ul>
 *   <li>the raw {@code PAYMENT-REQUIRED} header of a 402, so that when the interceptor refuses the
 *       offer before the spend guard ever sees it (payee not on the allowlist, amount over the
 *       per-request maximum) the intent can still record why; the header is decoded only for that
 *       classification;
 *   <li>the HTTP status of the paid retry (the request carrying {@code PAYMENT-SIGNATURE}), so a
 *       seller rate limit (429) is not counted as a seller outage by the circuit breaker.
 * </ul>
 *
 * Stores nothing else.
 */
final class OfferRecorder implements ClientHttpRequestInterceptor {

    static final String ATTRIBUTE = OfferRecorder.class.getName();

    /** Per-call holder, passed as a request attribute (no ThreadLocal). */
    static final class Exchange {
        private volatile @Nullable String paymentRequired;
        private volatile int paidStatus = -1;

        @Nullable
        String paymentRequired() {
            return paymentRequired;
        }

        /** The status the seller answered the paid retry with, or -1 if none was sent or answered. */
        int paidStatus() {
            return paidStatus;
        }
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        if (request.getAttributes().get(ATTRIBUTE) instanceof Exchange exchange) {
            int status = response.getStatusCode().value();
            if (request.getHeaders().containsHeader(X402Headers.PAYMENT_SIGNATURE)) {
                exchange.paidStatus = status;
            } else if (status == 402) {
                exchange.paymentRequired = response.getHeaders().getFirst(X402Headers.PAYMENT_REQUIRED);
            }
        }
        return response;
    }
}
