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
 * The handler's deadline is {@code min(seller.llm.deadline, validBefore - now - settle margin)} for
 * a settled (upfront, ADR-0021) request too: verify + settle + handler must stay inside the
 * authorization window, which the buyer's read timeout is sized to. A request whose window ran
 * short in {@code /verify} is therefore either refused before the settle (less than the settle
 * margin left: {@code 402}, nothing paid) or settled and failed fast ({@code 503}, credit note),
 * never served after the buyer gave up.
 *
 * <p>Scaled-down timeouts that still pass the startup rule {@code deadline + connect + read timeout
 * + 5 s <= 45 s}: deadline 4 s, facilitator connect 3 s and read timeout 33 s (settle margin 41 s),
 * model timeout 3 s. The test authorization's {@code validBefore} is a whole second, so the window
 * left is up to 1 s shorter than its nominal length; each case keeps at least that much distance
 * from its band's edges.
 */
@TestPropertySource(
        properties = {
            "seller.llm.deadline=4s",
            "saiman.router.openai.timeout=3s",
            "x402.server.facilitator.read-timeout=33s"
        })
class AuthorizationWindowDeadlineEndpointTests extends RagTestBase {

    private static final String QUESTIONS = "/v1/disclosures/THYAO/questions";
    private static final String SUMMARY = "/v1/disclosures/THYAO/summary";
    private static final String BODY = "{\"question\":\"What did the board decide?\"}";
    private static final String ANSWER = "{\"answer\":\"Text.\",\"citedChunkIds\":[\"kap:5:0000\",\"kap:5:0001\"]}";
    private static final Duration MODEL_TIME = Duration.ofMillis(1_200);

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
        // 45-46 s left: budget min(4 s, 4-5 s) = 4 s, more than the 3 s model timeout.
        router.replyWithDelay(ANSWER, MODEL_TIME);

        postWith(QUESTIONS, paymentWithWindow("20000", 46), BODY).expectStatus().isOk();

        assertThat(router.modelCalls()).isEqualTo(1);
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
        assertThat(creditNotes()).isEmpty();
    }

    @Test
    void aSettledQuestionWhoseWindowRanShortFailsFastWithACreditNote() {
        // 42-43 s left after a 3 s /verify: at least the 41 s settle margin, so it settles, but the
        // budget (1-2 s) is below the 3 s model timeout: 503 without calling the model.
        String header = paymentWithWindow("20000", 46);
        FACILITATOR.injectVerifyDelay(Duration.ofSeconds(3));

        postWith(QUESTIONS, header, BODY).expectStatus().isEqualTo(503);

        assertThat(router.modelCalls()).isZero();
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
        assertOneCreditNote(503);
    }

    @Test
    void aSettledSummaryWhoseWindowRanShortFailsFastWithACreditNote() {
        INGEST.retrieves(List.of(FakeIngestServer.chunk("kap:5:0000", "THYAO", "one")), "v-short-window");
        router.replyWithDelay("{\"summary\":\"Summary.\",\"citedChunkIds\":[\"kap:5:0000\"]}", MODEL_TIME);
        String header = paymentWithWindow("10000", 46);
        FACILITATOR.injectVerifyDelay(Duration.ofSeconds(3));

        client.get()
                .uri(SUMMARY)
                .header(X402Headers.PAYMENT_SIGNATURE, header)
                .exchange()
                .expectStatus()
                .isEqualTo(503);

        assertThat(router.modelCalls()).isZero();
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
        assertOneCreditNote(503);
    }

    @Test
    void aVerifyThatLeavesLessThanTheSettleMarginIsRefusedBeforeSettling() {
        // 39-40 s left after a 6 s /verify: below the 41 s settle margin, so nothing is settled.
        String header = paymentWithWindow("20000", 46);
        FACILITATOR.injectVerifyDelay(Duration.ofSeconds(6));

        postWith(QUESTIONS, header, BODY).expectStatus().isEqualTo(402);

        assertThat(router.modelCalls()).isZero();
        assertThat(FACILITATOR.verifyCallCount()).isEqualTo(1);
        assertThat(FACILITATOR.settleCallCount()).isZero();
        assertThat(creditNotes()).isEmpty();
    }
}
