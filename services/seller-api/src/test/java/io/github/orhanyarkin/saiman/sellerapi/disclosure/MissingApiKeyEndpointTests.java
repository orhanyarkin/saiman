package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.sellerapi.SellerApiApplication;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.FakeIngestServer;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.RagTestBase;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.TestcontainersConfiguration;
import io.github.orhanyarkin.saiman.testsupport.RedisContainerConfiguration;
import io.github.orhanyarkin.x402.core.PaymentFlow;
import io.github.orhanyarkin.x402.core.PaymentRequirements;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import io.github.orhanyarkin.x402.core.X402Codec;
import io.github.orhanyarkin.x402.core.X402Headers;
import io.github.orhanyarkin.x402.testing.PaymentPayloads;
import io.github.orhanyarkin.x402.testing.TestWallets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The REAL model router (Redis cost guard, OpenAI adapter) with no API key configured: the first
 * model call fails closed before anything leaves the process, and the buyer gets a 503 that is
 * never settled. No network, no key.
 */
@Import({TestcontainersConfiguration.class, RedisContainerConfiguration.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = SellerApiApplication.class,
        // Blank beats any OPENAI_API_KEY in the developer's environment: "not configured".
        properties = {"seller.disclosures.source=rag", "openai_api_key="})
@AutoConfigureRestTestClient
class MissingApiKeyEndpointTests {

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        registry.add("x402.server.pay-to", () -> RagTestBase.PAY_TO);
        registry.add("x402.server.facilitator.url", RagTestBase.FACILITATOR::url);
        registry.add("seller.ingest.base-url", RagTestBase.INGEST::url);
    }

    @Autowired
    private RestTestClient client;

    @Autowired
    private X402Codec codec;

    @BeforeEach
    void reset() {
        RagTestBase.FACILITATOR.resetCallCounts();
        RagTestBase.INGEST.reset();
        RagTestBase.INGEST.retrieves(
                List.of(
                        FakeIngestServer.chunk("kap:5:0000", "THYAO", "CHUNKTEXTMARKER one"),
                        FakeIngestServer.chunk("kap:5:0001", "THYAO", "CHUNKTEXTMARKER two")),
                "v-no-key");
    }

    @Test
    void noApiKeyMeansA503AndNoSettlement() {
        PaymentRequirements offer = new PaymentRequirements(
                TestnetAssets.SCHEME_EXACT,
                TestnetAssets.NETWORK,
                "20000",
                TestnetAssets.USDC_ADDRESS,
                RagTestBase.PAY_TO,
                60,
                Map.of(
                        "name",
                        TestnetAssets.USDC_NAME,
                        "version",
                        TestnetAssets.USDC_VERSION,
                        PaymentFlow.EXTRA_KEY,
                        PaymentFlow.UPFRONT.wireValue()));
        String header = PaymentPayloads.header(codec, PaymentPayloads.build(TestWallets.PAYER, offer));

        String body = client.post()
                .uri("/v1/disclosures/THYAO/questions")
                .header(X402Headers.PAYMENT_SIGNATURE, header)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("{\"question\":\"SECRETQUESTIONMARKER?\"}")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        // Upfront flow (ADR-0021): settled before the handler, so the 503 is credited.
        assertThat(RagTestBase.FACILITATOR.settleCallCount()).isEqualTo(1);
        assertThat(body).doesNotContain("SECRETQUESTIONMARKER").doesNotContain("CHUNKTEXTMARKER");
    }
}
