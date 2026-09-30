package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.modelrouter.RouterProperties;
import io.github.orhanyarkin.saiman.sellerapi.retrieval.IngestProperties;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.FakeIngestServer;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.RagTestBase;
import io.github.orhanyarkin.x402.core.X402Headers;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * F2: the model runs before settlement, so the authorization must still be valid when the handler
 * is done. The LLM endpoints demand a long enough authorization window (45 s) and answer within a
 * deadline (1 s here; 25 s in production) or not at all.
 */
@TestPropertySource(properties = "seller.llm.deadline=1s")
class DeadlineAndWindowEndpointTests extends RagTestBase {

    private static final String QUESTIONS = "/v1/disclosures/THYAO/questions";
    private static final String SUMMARY = "/v1/disclosures/THYAO/summary";
    private static final String BODY = "{\"question\":\"What did the board decide?\"}";
    private static final String GROUNDED = "{\"answer\":\"Text.\",\"citedChunkIds\":[\"kap:5:0000\",\"kap:5:0001\"]}";

    @Autowired
    private RouterProperties routerProperties;

    @Autowired
    private IngestProperties ingestProperties;

    @BeforeEach
    void script() {
        INGEST.retrieves(
                List.of(
                        FakeIngestServer.chunk("kap:5:0000", "THYAO", "one"),
                        FakeIngestServer.chunk("kap:5:0001", "THYAO", "two")),
                "v-window");
        router.replyWith(GROUNDED);
    }

    @Test
    void anAuthorizationValidFor21SecondsIsRejectedOnBothLlmEndpointsWithoutCallingTheFacilitator() {
        postWith(QUESTIONS, paymentWithWindow("20000", 21), BODY).expectStatus().isEqualTo(402);
        client.get()
                .uri(SUMMARY)
                .header(X402Headers.PAYMENT_SIGNATURE, paymentWithWindow("10000", 21))
                .exchange()
                .expectStatus()
                .isEqualTo(402);

        assertThat(FACILITATOR.verifyCallCount()).isZero();
        assertThat(router.routerRequests()).isZero();
        assertThat(INGEST.tickerCalls()).isZero();
    }

    @Test
    void anAuthorizationValidFor60SecondsIsAccepted() {
        postWith(QUESTIONS, paymentWithWindow("20000", 60), BODY).expectStatus().isOk();
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
    }

    @Test
    void aModelSlowerThanTheDeadlineIs503NeverSettledAndTheAuthorizationIsSpent() {
        router.replyWithDelay(GROUNDED, Duration.ofMillis(1_500));
        String header = payment("20000");

        postWith(QUESTIONS, header, BODY).expectStatus().isEqualTo(503);
        assertThat(FACILITATOR.settleCallCount()).isZero();
        assertThat(router.modelCalls()).isEqualTo(1);

        // The provider was paid for that run, so the same authorization can not be replayed.
        postWith(QUESTIONS, header, BODY).expectStatus().isEqualTo(402);
        assertThat(router.modelCalls()).isEqualTo(1);
    }

    @Test
    void productionTimeoutsFitInsideTheDeadline() {
        assertThat(routerProperties.openai().timeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(routerProperties.openai().maxRetries()).isZero();
        assertThat(ingestProperties.readTimeout()).isLessThanOrEqualTo(Duration.ofSeconds(5));
        assertThat(ingestProperties.retryAttempts()).isLessThanOrEqualTo(2);
    }
}
