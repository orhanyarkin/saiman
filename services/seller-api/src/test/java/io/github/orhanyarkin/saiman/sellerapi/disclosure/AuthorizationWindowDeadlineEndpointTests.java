package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.sellerapi.testsupport.FakeIngestServer;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.RagTestBase;
import io.github.orhanyarkin.x402.core.X402Headers;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * ADR-0015 (M3 part): the handler's deadline is {@code min(seller.llm.deadline, validBefore - now -
 * settle margin)}, so an authorization that reaches the handler with little of its window left
 * (here: a slow {@code /verify}) gets a short deadline, and the model is not called when less than
 * the model timeout remains.
 *
 * <p>Scaled-down timeouts that still pass the startup rule {@code deadline + read timeout + 5 s <=
 * 45 s}: deadline 3 s, facilitator read timeout 37 s (settle margin 42 s), model timeout 1.5 s. An
 * authorization valid for 46 s leaves {@code ~46 - 42 = 4 s}, capped at 3 s, when it arrives
 * promptly; after a 3 s {@code /verify} it leaves under 1 s.
 */
@TestPropertySource(
        properties = {
            "seller.llm.deadline=3s",
            "saiman.router.openai.timeout=1500ms",
            "x402.server.facilitator.read-timeout=37s"
        })
class AuthorizationWindowDeadlineEndpointTests extends RagTestBase {

    private static final String QUESTIONS = "/v1/disclosures/THYAO/questions";
    private static final String SUMMARY = "/v1/disclosures/THYAO/summary";
    private static final String BODY = "{\"question\":\"What did the board decide?\"}";
    private static final String ANSWER = "{\"answer\":\"Text.\",\"citedChunkIds\":[\"kap:5:0000\",\"kap:5:0001\"]}";

    @BeforeEach
    void script() {
        INGEST.retrieves(
                List.of(
                        FakeIngestServer.chunk("kap:5:0000", "THYAO", "one"),
                        FakeIngestServer.chunk("kap:5:0001", "THYAO", "two")),
                "v-auth-window");
        router.replyWith(ANSWER);
    }

    @Test
    void anAuthorizationArrivingWithEnoughWindowIsAnsweredAndSettled() {
        postWith(QUESTIONS, paymentWithWindow("20000", 46), BODY).expectStatus().isOk();

        assertThat(router.modelCalls()).isEqualTo(1);
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
    }

    @Test
    void aShortRemainingWindowSkipsRetrievalAndTheModelAndIsNeverSettled() {
        String header = paymentWithWindow("20000", 46);
        FACILITATOR.injectVerifyDelay(Duration.ofSeconds(3));

        postWith(QUESTIONS, header, BODY).expectStatus().isEqualTo(503);

        assertThat(FACILITATOR.verifyCallCount()).isEqualTo(1);
        assertThat(router.modelCalls()).isZero();
        assertThat(INGEST.retrieveCalls()).isZero();
        assertThat(FACILITATOR.settleCallCount()).isZero();
    }

    @Test
    void theSummaryEndpointAlsoSkipsTheModelWhenTheWindowIsShort() {
        String header = paymentWithWindow("10000", 46);
        FACILITATOR.injectVerifyDelay(Duration.ofSeconds(3));

        client.get()
                .uri(SUMMARY)
                .header(X402Headers.PAYMENT_SIGNATURE, header)
                .exchange()
                .expectStatus()
                .isEqualTo(503);

        assertThat(router.modelCalls()).isZero();
        assertThat(FACILITATOR.settleCallCount()).isZero();
    }
}
