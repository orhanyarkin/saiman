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
 * ADR-0015 cut the handler's deadline to {@code min(seller.llm.deadline, validBefore - now - settle
 * margin)} because the payment was settled after the handler. Under the upfront flow (ADR-0021) the
 * payment is settled before the handler runs, so a request whose remaining window is short (here: a
 * slow {@code /verify}) still gets the full configured deadline: cutting it would only turn paid
 * requests into 503s and credit notes.
 *
 * <p>Scaled-down timeouts that still pass the startup rule {@code deadline + connect + read timeout
 * + 5 s <= 45 s}: deadline 3 s, facilitator connect 3 s and read timeout 34 s (settle margin 42 s),
 * model timeout 1.5 s. An authorization valid for 46 s that spends 3 s in {@code /verify} would have
 * left under 1 s under the old cut, less than the model needs (1.2 s here).
 */
@TestPropertySource(
        properties = {
            "seller.llm.deadline=3s",
            "saiman.router.openai.timeout=1500ms",
            "x402.server.facilitator.read-timeout=34s"
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
        postWith(QUESTIONS, paymentWithWindow("20000", 46), BODY).expectStatus().isOk();

        assertThat(router.modelCalls()).isEqualTo(1);
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
    }

    @Test
    void aSettledQuestionGetsTheFullDeadlineEvenWhenLittleOfItsWindowIsLeft() {
        String header = paymentWithWindow("20000", 46);
        FACILITATOR.injectVerifyDelay(Duration.ofSeconds(3));
        router.replyWithDelay(ANSWER, MODEL_TIME);

        postWith(QUESTIONS, header, BODY).expectStatus().isOk();

        assertThat(router.modelCalls()).isEqualTo(1);
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
        assertThat(creditNotes()).isEmpty();
    }

    @Test
    void aSettledSummaryGetsTheFullDeadlineEvenWhenLittleOfItsWindowIsLeft() {
        INGEST.retrieves(List.of(FakeIngestServer.chunk("kap:5:0000", "THYAO", "one")), "v-full-deadline");
        router.replyWithDelay("{\"summary\":\"Summary.\",\"citedChunkIds\":[\"kap:5:0000\"]}", MODEL_TIME);
        String header = paymentWithWindow("10000", 46);
        FACILITATOR.injectVerifyDelay(Duration.ofSeconds(3));

        client.get()
                .uri(SUMMARY)
                .header(X402Headers.PAYMENT_SIGNATURE, header)
                .exchange()
                .expectStatus()
                .isOk();

        assertThat(router.modelCalls()).isEqualTo(1);
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
        assertThat(creditNotes()).isEmpty();
    }
}
