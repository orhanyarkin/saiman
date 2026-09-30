package io.github.orhanyarkin.x402.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.orhanyarkin.x402.core.Eip3009Authorization;
import io.github.orhanyarkin.x402.core.PaymentRequired;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.ResourceInfo;
import io.github.orhanyarkin.x402.core.SettlementResponse;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * {@link SpendGuard#signed} runs after signing and before the paid retry leaves the process. A
 * guard that throws there must cause nothing to be sent (fail closed) and the reservation to be
 * released. A real loopback HTTP server counts what actually arrives on the wire.
 */
class X402PaymentInterceptorSignedHookTest {

    private static final String COW_PRIVATE_KEY = "0xc85ef7d79691fe79573b1a7064c19c1a9819ebdbd1faaab1a8ec92344438aaf4";
    private static final String PAY_TO = "0x209693Bc6afc0C5328bA36FaF03C514EF312287C";
    private static final String AMOUNT = "1000";

    private final X402Codec codec = new X402Codec();
    private final PaymentSigner signer = new PrivateKeyPaymentSigner(COW_PRIVATE_KEY);
    private final AtomicInteger unpaidRequests = new AtomicInteger();
    private final AtomicInteger paidRequests = new AtomicInteger();
    private HttpServer stub;
    private String url;

    @BeforeEach
    void startStub() throws Exception {
        PaymentRequirements offer = new PaymentRequirements(
                TestnetAssets.SCHEME_EXACT,
                TestnetAssets.NETWORK,
                AMOUNT,
                TestnetAssets.USDC_ADDRESS,
                PAY_TO,
                60,
                Map.of("name", TestnetAssets.USDC_NAME, "version", TestnetAssets.USDC_VERSION));
        String paymentRequired = codec.encodePaymentRequired(new PaymentRequired(
                2,
                null,
                new ResourceInfo("http://127.0.0.1/paid", null, null, null, null, null),
                List.of(offer),
                null));
        String settled = codec.encodeSettlementResponse(new SettlementResponse(
                true,
                null,
                null,
                signer.address(),
                "0x" + "ab".repeat(32),
                TestnetAssets.NETWORK,
                AMOUNT,
                null,
                null,
                null));

        stub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        stub.createContext("/paid", exchange -> {
            if (exchange.getRequestHeaders().containsKey(X402Headers.PAYMENT_SIGNATURE)) {
                paidRequests.incrementAndGet();
                exchange.getResponseHeaders().add(X402Headers.PAYMENT_RESPONSE, settled);
                exchange.sendResponseHeaders(200, -1);
            } else {
                unpaidRequests.incrementAndGet();
                exchange.getResponseHeaders().add(X402Headers.PAYMENT_REQUIRED, paymentRequired);
                exchange.sendResponseHeaders(402, -1);
            }
            exchange.close();
        });
        stub.start();
        url = "http://127.0.0.1:" + stub.getAddress().getPort() + "/paid";
    }

    @AfterEach
    void stopStub() {
        stub.stop(0);
    }

    @Test
    void aThrowingSignedHookSendsNothingAndReleasesTheReservation() {
        RecordingGuard guard = new RecordingGuard(true);
        RestClient client = client(guard);

        assertThatThrownBy(() -> client.get()
                        .uri(url)
                        .header(X402PaymentInterceptor.IDEMPOTENCY_KEY_HEADER, "signed-hook-1")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(SpendDeniedException.class)
                .hasMessage("cannot record the signed authorization");

        assertThat(unpaidRequests.get()).isEqualTo(1);
        assertThat(paidRequests.get()).isZero();
        // The hook saw the authorization that was signed (it was signed, just never sent).
        assertThat(guard.signedAuthorizations).hasSize(1);
        assertThat(guard.signedAuthorizations.getFirst().from()).isEqualToIgnoringCase(signer.address());
        assertThat(guard.releaseReasons).containsExactly("signed hook failed");
        // Released for real: the same idempotency key can be reserved again.
        assertThat(guard.lastIntent).isNotNull();
        assertThat(guard.lastIntent.idempotencyKey()).isEqualTo("signed-hook-1");
        assertThat(guard.delegate.reserve(guard.lastIntent)).isNotNull();
    }

    @Test
    void aSucceedingSignedHookRunsBeforeThePaidRequestIsSent() {
        RecordingGuard guard = new RecordingGuard(false);
        guard.onSigned = () -> assertThat(paidRequests.get()).isZero();
        RestClient client = client(guard);

        client.get()
                .uri(url)
                .header(X402PaymentInterceptor.IDEMPOTENCY_KEY_HEADER, "signed-hook-2")
                .retrieve()
                .toBodilessEntity();

        assertThat(guard.signedAuthorizations).hasSize(1);
        assertThat(guard.onSignedRan).isTrue();
        assertThat(paidRequests.get()).isEqualTo(1);
        assertThat(guard.releaseReasons).isEmpty();
        assertThat(guard.commits.get()).isEqualTo(1);
    }

    private RestClient client(SpendGuard guard) {
        X402PaymentInterceptor interceptor =
                new X402PaymentInterceptor(signer, guard, codec, 5000, List.of(PAY_TO), ObservationRegistry.NOOP);
        return RestClient.builder()
                .requestFactory(X402RestClients.nonRedirectingRequestFactory())
                .requestInterceptor(interceptor)
                .build();
    }

    /** Delegates to {@link PropertiesSpendGuard} and records every call. */
    private static final class RecordingGuard implements SpendGuard {

        final PropertiesSpendGuard delegate = new PropertiesSpendGuard(5000, List.of(PAY_TO));
        final List<Eip3009Authorization> signedAuthorizations = new CopyOnWriteArrayList<>();
        final List<String> releaseReasons = new CopyOnWriteArrayList<>();
        final AtomicInteger commits = new AtomicInteger();
        private final boolean failOnSigned;
        PaymentIntent lastIntent;
        Runnable onSigned = () -> {};
        boolean onSignedRan;

        RecordingGuard(boolean failOnSigned) {
            this.failOnSigned = failOnSigned;
        }

        @Override
        public SpendReservation reserve(PaymentIntent intent) {
            lastIntent = intent;
            return delegate.reserve(intent);
        }

        @Override
        public void signed(SpendReservation reservation, Eip3009Authorization authorization) {
            signedAuthorizations.add(authorization);
            onSigned.run();
            onSignedRan = true;
            if (failOnSigned) {
                throw new SpendDeniedException("cannot record the signed authorization");
            }
        }

        @Override
        public void commit(SpendReservation reservation, SettlementResponse settlement) {
            commits.incrementAndGet();
            delegate.commit(reservation, settlement);
        }

        @Override
        public void release(SpendReservation reservation, String reason) {
            releaseReasons.add(reason);
            delegate.release(reservation, reason);
        }
    }
}
