package io.github.orhanyarkin.saiman.sellerapi.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.sellerapi.testsupport.FakeIngestServer;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.RagTestBase;
import io.github.orhanyarkin.x402.core.X402Headers;
import io.github.orhanyarkin.x402.server.X402SettlementFilter;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.databind.json.JsonMapper;

/**
 * F5: an oversized or unmeasured request body is refused with 413 before payment verification,
 * ingest and the model, and it does not spend the payment authorization.
 */
class RequestBodyLimitEndpointTests extends RagTestBase {

    private static final String URI_PATH = "/v1/disclosures/THYAO/questions";
    private static final String BODY = "{\"question\":\"What did the board decide?\"}";

    @LocalServerPort
    private int port;

    @Autowired
    private List<FilterRegistrationBean<?>> filterRegistrations;

    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    void script() {
        INGEST.retrieves(
                List.of(
                        FakeIngestServer.chunk("kap:5:0000", "THYAO", "one"),
                        FakeIngestServer.chunk("kap:5:0001", "THYAO", "two")),
                "v-body");
        router.replyWith("{\"answer\":\"Text.\",\"citedChunkIds\":[\"kap:5:0000\",\"kap:5:0001\"]}");
    }

    private String oneMegabyteBody() {
        return "{\"question\":\"" + "x".repeat(1024 * 1024) + "\"}";
    }

    private void assertNothingHappened() {
        assertThat(FACILITATOR.verifyCallCount()).isZero();
        assertThat(FACILITATOR.settleCallCount()).isZero();
        assertThat(INGEST.tickerCalls()).isZero();
        assertThat(INGEST.retrieveCalls()).isZero();
        assertThat(router.routerRequests()).isZero();
    }

    @Test
    void aOneMegabyteBodyWithAValidPaymentIs413AndSpendsNothing() {
        String header = payment("20000");

        postWith(URI_PATH, header, oneMegabyteBody()).expectStatus().isEqualTo(413);
        assertNothingHappened();

        // The nonce was never claimed: the same authorization still pays for a well-formed request.
        postWith(URI_PATH, header, BODY).expectStatus().isOk();
    }

    @Test
    void anOversizedBodyIsRefusedBeforeTheMissingPaymentIsNoticed() {
        client.post()
                .uri(URI_PATH)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(oneMegabyteBody())
                .exchange()
                .expectStatus()
                .isEqualTo(413);
        assertNothingHappened();
    }

    @Test
    void aChunkedBodyOfUnknownLengthIs413AndSpendsNothing() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + URI_PATH))
                .header("Content-Type", "application/json")
                .header(X402Headers.PAYMENT_SIGNATURE, payment("20000"))
                .POST(HttpRequest.BodyPublishers.ofInputStream(
                        () -> new ByteArrayInputStream(BODY.getBytes(StandardCharsets.UTF_8))))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
        assertNothingHappened();
    }

    @Test
    void aBodyAtTheLimitIsStillServed() {
        // 500-character question inside a small envelope: well under 4 KiB.
        postPaid(URI_PATH, "20000", "{\"question\":\"" + "x".repeat(500) + "\"}")
                .expectStatus()
                .isOk();
    }

    @Test
    void theBodyLimitRunsBeforeTheX402Filter() {
        int bodyLimit = orderOf(BodySizeLimitFilter.class);
        int x402 = orderOf(X402SettlementFilter.class);
        assertThat(bodyLimit).isLessThan(x402);
    }

    @Test
    void theJsonParserRejectsAStringLongerThanTheConfiguredCap() {
        String tooLong = "{\"question\":\"" + "x".repeat(70_000) + "\"}";
        assertThatThrownBy(() -> jsonMapper.readTree(tooLong)).isInstanceOf(StreamConstraintsException.class);
    }

    private int orderOf(Class<?> filterType) {
        return filterRegistrations.stream()
                .filter(registration -> filterType.isInstance(registration.getFilter()))
                .findFirst()
                .orElseThrow()
                .getOrder();
    }
}
