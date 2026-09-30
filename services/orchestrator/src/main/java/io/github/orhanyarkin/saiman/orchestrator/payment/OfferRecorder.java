package io.github.orhanyarkin.saiman.orchestrator.payment;

import io.github.orhanyarkin.x402.core.X402Headers;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Sits inside the x402 interceptor and keeps the raw {@code PAYMENT-REQUIRED} header of a 402, so
 * that when the interceptor refuses the offer before the spend guard ever sees it (payee not on the
 * allowlist, amount over the per-request maximum) the intent can still record why. Stores nothing
 * else; the header is decoded only for that classification.
 */
final class OfferRecorder implements ClientHttpRequestInterceptor {

    static final String ATTRIBUTE = OfferRecorder.class.getName();

    /** Per-call holder, passed as a request attribute (no ThreadLocal). */
    static final class Exchange {
        private volatile @Nullable String paymentRequired;

        @Nullable
        String paymentRequired() {
            return paymentRequired;
        }
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        if (response.getStatusCode().value() == 402
                && request.getAttributes().get(ATTRIBUTE) instanceof Exchange exchange) {
            exchange.paymentRequired = response.getHeaders().getFirst(X402Headers.PAYMENT_REQUIRED);
        }
        return response;
    }
}
