package io.github.orhanyarkin.saiman.orchestrator.payment;

import io.github.orhanyarkin.x402.client.X402ClientProperties;
import io.github.orhanyarkin.x402.client.X402PaymentInterceptor;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Wires the paying seller client: Boot's {@link RestClient.Builder} (observations, message
 * converters) plus a JDK request factory that never follows redirects, the starter's {@link
 * X402PaymentInterceptor} and, inside it, the {@link OfferRecorder}.
 *
 * <p>Without an {@code x402.client} signer the starter creates no interceptor; the client then
 * refuses every call ({@code NOT_CONFIGURED}) instead of the application failing to start, so the
 * service still runs (and its health endpoint answers) where no buyer key is mounted.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SellerProperties.class)
class PaidResourceClientConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PaidResourceClientConfiguration.class);

    @Bean
    PaidResourceClient paidResourceClient(
            RestClient.Builder builder,
            ObjectProvider<X402PaymentInterceptor> paymentInterceptor,
            SellerProperties seller,
            PaymentIntentService intents,
            X402Codec codec,
            X402ClientProperties x402) {
        X402PaymentInterceptor interceptor = paymentInterceptor.getIfAvailable();
        RestClient restClient = null;
        if (interceptor == null) {
            log.warn("No x402 client signer is configured: paid seller calls are disabled");
        } else {
            restClient = builder.requestFactory(ClientHttpRequestFactoryBuilder.jdk()
                            .build(HttpClientSettings.defaults()
                                    .withRedirects(HttpRedirects.DONT_FOLLOW)
                                    .withTimeouts(seller.connectTimeout(), seller.readTimeout())))
                    .requestInterceptor(interceptor)
                    .requestInterceptor(new OfferRecorder())
                    .build();
        }
        Set<String> allowedPayTo = x402.allowedPayTo().stream()
                .map(address -> address.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        Long max = x402.maxAmountPerRequest();
        return new X402PaidResourceClient(
                restClient, intents, sellerCircuitBreaker(), codec, allowedPayTo, max == null ? 0 : max);
    }

    /**
     * Opens on I/O errors and seller 5xx only; a denial, rejection or 4xx is not a seller failure,
     * and a 429 on the paid retry is ignored altogether (neither failure nor success): the seller is
     * up and rate-limiting this payer.
     */
    private static CircuitBreaker sellerCircuitBreaker() {
        return CircuitBreaker.of(
                "seller-api",
                CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(5)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .permittedNumberOfCallsInHalfOpenState(1)
                        .recordException(X402PaidResourceClient::isSellerFailure)
                        .ignoreException(
                                failure -> failure instanceof X402PaidResourceClient.SellerRateLimitedException)
                        .build());
    }
}
