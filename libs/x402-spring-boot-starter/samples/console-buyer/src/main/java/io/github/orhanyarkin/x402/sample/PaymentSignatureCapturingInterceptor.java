package io.github.orhanyarkin.x402.sample;

import io.github.orhanyarkin.x402.core.X402Headers;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Captures the {@code PAYMENT-SIGNATURE} header value that was actually sent, for {@code buy} to
 * write to {@code build/last-payment.txt}.
 *
 * <p>Registered <em>after</em> {@code X402PaymentInterceptor} on the same {@code RestClient}, so it
 * sits closer to the transport and observes the request exactly as x402's interceptor rebuilt it
 * on retry (payment header included) rather than the original, unpaid request.
 */
final class PaymentSignatureCapturingInterceptor implements ClientHttpRequestInterceptor {

    private final AtomicReference<String> lastPaymentSignature;

    PaymentSignatureCapturingInterceptor(AtomicReference<String> lastPaymentSignature) {
        this.lastPaymentSignature = lastPaymentSignature;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String header = request.getHeaders().getFirst(X402Headers.PAYMENT_SIGNATURE);
        if (header != null) {
            lastPaymentSignature.set(header);
        }
        return execution.execute(request, body);
    }
}
