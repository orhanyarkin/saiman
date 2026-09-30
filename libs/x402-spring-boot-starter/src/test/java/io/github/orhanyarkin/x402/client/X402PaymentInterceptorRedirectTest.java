package io.github.orhanyarkin.x402.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.orhanyarkin.x402.core.PaymentRequired;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.ResourceInfo;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.orhanyarkin.x402.core.X402Headers;
import io.github.orhanyarkin.x402.evm.PaymentSigner;
import io.github.orhanyarkin.x402.evm.PrivateKeyPaymentSigner;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Verifies the "Redirects" contract from {@link X402PaymentInterceptor}'s Javadoc against a real
 * {@code ClientHttpRequestFactory} and two real local (loopback) servers: {@code
 * MockRestServiceServer} (used in {@link X402PaymentInterceptorTest}) never actually performs HTTP
 * redirect handling, so this behaviour can't be tested against it. Also exercises {@link
 * X402RestClients#nonRedirectingRequestFactory()} itself, rather than building an equivalent
 * factory by hand.
 */
class X402PaymentInterceptorRedirectTest {

    private static final String COW_PRIVATE_KEY = "0xc85ef7d79691fe79573b1a7064c19c1a9819ebdbd1faaab1a8ec92344438aaf4";
    private static final String PAY_TO = "0x209693Bc6afc0C5328bA36FaF03C514EF312287C";
    private static final String AMOUNT = "1000";

    private final X402Codec codec = new X402Codec();
    private HttpServer paidServer;
    private HttpServer otherServer;

    @AfterEach
    void tearDown() {
        if (paidServer != null) {
            paidServer.stop(0);
        }
        if (otherServer != null) {
            otherServer.stop(0);
        }
    }

    @Test
    void aRedirectOnThePaidRetryIsNeverFollowedAndTheOtherHostNeverSeesThePaymentSignature() throws Exception {
        AtomicInteger otherServerRequests = new AtomicInteger();
        AtomicBoolean otherServerSawPaymentSignature = new AtomicBoolean();
        otherServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        otherServer.createContext("/elsewhere", exchange -> {
            otherServerRequests.incrementAndGet();
            if (exchange.getRequestHeaders().containsKey(X402Headers.PAYMENT_SIGNATURE)) {
                otherServerSawPaymentSignature.set(true);
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        otherServer.start();
        String otherServerUrl = "http://127.0.0.1:" + otherServer.getAddress().getPort() + "/elsewhere";

        PaymentRequirements offer = new PaymentRequirements(
                TestnetAssets.SCHEME_EXACT,
                TestnetAssets.NETWORK,
                AMOUNT,
                TestnetAssets.USDC_ADDRESS,
                PAY_TO,
                60,
                Map.of("name", TestnetAssets.USDC_NAME, "version", TestnetAssets.USDC_VERSION));
        String encodedPaymentRequired = codec.encodePaymentRequired(new PaymentRequired(
                2,
                null,
                new ResourceInfo("http://127.0.0.1/paid", null, null, null, null, null),
                List.of(offer),
                null));

        AtomicInteger paidServerRequests = new AtomicInteger();
        paidServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        paidServer.createContext("/paid", exchange -> {
            if (paidServerRequests.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add(X402Headers.PAYMENT_REQUIRED, encodedPaymentRequired);
                exchange.sendResponseHeaders(402, -1);
            } else {
                // A malicious or misconfigured seller redirects the paid retry elsewhere.
                exchange.getResponseHeaders().add("Location", otherServerUrl);
                exchange.sendResponseHeaders(302, -1);
            }
            exchange.close();
        });
        paidServer.start();
        String paidUrl = "http://127.0.0.1:" + paidServer.getAddress().getPort() + "/paid";

        PaymentSigner signer = new PrivateKeyPaymentSigner(COW_PRIVATE_KEY);
        SpendGuard spendGuard = new PropertiesSpendGuard(5000, List.of(PAY_TO));
        X402PaymentInterceptor interceptor =
                new X402PaymentInterceptor(signer, spendGuard, codec, 5000, List.of(PAY_TO), ObservationRegistry.NOOP);

        // The starter's own helper for the property spring.http.clients.redirects=dont-follow
        // documents applications must configure -- used directly here (there is no Spring
        // Environment in a plain RestClient.builder() unit test to set that property on).
        ClientHttpRequestFactory dontFollowRedirects = X402RestClients.nonRedirectingRequestFactory();
        RestClient restClient = RestClient.builder()
                .requestFactory(dontFollowRedirects)
                .requestInterceptor(interceptor)
                .build();

        assertThatThrownBy(() -> restClient
                        .get()
                        .uri(paidUrl)
                        .header(X402PaymentInterceptor.IDEMPOTENCY_KEY_HEADER, "redirect-1")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(AmbiguousPaymentException.class);

        assertThat(paidServerRequests.get()).isEqualTo(2);
        assertThat(otherServerRequests.get()).isEqualTo(0);
        assertThat(otherServerSawPaymentSignature.get()).isFalse();
    }

    /**
     * The plaintext allowlist checks the first hop only. A listed host answering the paid retry with
     * a redirect to another loopback address ({@code 127.0.0.2}) must never hand the signature on:
     * with the non-following factory the second listener sees zero {@code PAYMENT-SIGNATURE}
     * headers. (With a following factory it would, which is why the auto-configuration refuses a
     * non-empty allowlist unless {@code spring.http.clients.redirects=dont-follow}.)
     */
    @Test
    void aListedPlaintextHostRedirectingToAnotherAddressNeverForwardsThePaymentSignature() throws Exception {
        AtomicInteger signaturesSeenByTheOtherListener = new AtomicInteger();
        otherServer = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.2"), 0), 0);
        otherServer.createContext("/", exchange -> {
            if (exchange.getRequestHeaders().containsKey(X402Headers.PAYMENT_SIGNATURE)) {
                signaturesSeenByTheOtherListener.incrementAndGet();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        otherServer.start();
        String otherUrl = "http://127.0.0.2:" + otherServer.getAddress().getPort() + "/elsewhere";

        PaymentRequirements offer = new PaymentRequirements(
                TestnetAssets.SCHEME_EXACT,
                TestnetAssets.NETWORK,
                AMOUNT,
                TestnetAssets.USDC_ADDRESS,
                PAY_TO,
                60,
                Map.of("name", TestnetAssets.USDC_NAME, "version", TestnetAssets.USDC_VERSION));
        String encodedPaymentRequired = codec.encodePaymentRequired(new PaymentRequired(
                2,
                null,
                new ResourceInfo("http://listed.test/paid", null, null, null, null, null),
                List.of(offer),
                null));
        AtomicInteger paidRequests = new AtomicInteger();
        paidServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        paidServer.createContext("/paid", exchange -> {
            if (paidRequests.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add(X402Headers.PAYMENT_REQUIRED, encodedPaymentRequired);
                exchange.sendResponseHeaders(402, -1);
            } else {
                exchange.getResponseHeaders().add("Location", otherUrl);
                exchange.sendResponseHeaders(302, -1);
            }
            exchange.close();
        });
        paidServer.start();
        // "127.0.0.1" is a valid exact allowlist entry (digits and dots) and resolves without DNS.
        String paidUrl = "http://127.0.0.1:" + paidServer.getAddress().getPort() + "/paid";

        X402PaymentInterceptor interceptor = new X402PaymentInterceptor(
                new PrivateKeyPaymentSigner(COW_PRIVATE_KEY),
                new PropertiesSpendGuard(5000, List.of(PAY_TO)),
                codec,
                5000,
                List.of(PAY_TO),
                List.of("127.0.0.1"),
                ObservationRegistry.NOOP);
        RestClient restClient = RestClient.builder()
                .requestFactory(X402RestClients.nonRedirectingRequestFactory())
                .requestInterceptor(interceptor)
                .build();

        assertThatThrownBy(() -> restClient
                        .get()
                        .uri(paidUrl)
                        .header(X402PaymentInterceptor.IDEMPOTENCY_KEY_HEADER, "redirect-listed-1")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(AmbiguousPaymentException.class);

        assertThat(paidRequests.get()).isEqualTo(2);
        assertThat(signaturesSeenByTheOtherListener.get()).isZero();
    }
}
