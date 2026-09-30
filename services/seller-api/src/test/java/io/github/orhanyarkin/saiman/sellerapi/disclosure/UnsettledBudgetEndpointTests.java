package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.sellerapi.testsupport.FakeIngestServer;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.RagTestBase;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * F1(b): attacker-steerable failures that all end 422 after the model ran stop at the day's
 * unsettled-run budget (3 here) and later requests never reach the model. Own context: it needs a
 * small budget.
 */
@TestPropertySource(properties = {"seller.llm.max-unsettled-per-day=3", "seller.llm.max-runs-per-payer-per-hour=100"})
class UnsettledBudgetEndpointTests extends RagTestBase {

    private static final String URI = "/v1/disclosures/THYAO/questions";
    private static final String BODY = "{\"question\":\"What did the board decide?\"}";
    private static final String UNGROUNDED = "{\"answer\":\"Text.\",\"citedChunkIds\":[\"kap:1:0001\",\"kap:2:0002\"]}";
    private static final String GROUNDED = "{\"answer\":\"Text.\",\"citedChunkIds\":[\"kap:5:0000\",\"kap:5:0001\"]}";

    @BeforeEach
    void script() {
        INGEST.retrieves(
                List.of(
                        FakeIngestServer.chunk("kap:5:0000", "THYAO", "one"),
                        FakeIngestServer.chunk("kap:5:0001", "THYAO", "two")),
                "v-budget");
    }

    @Test
    void freshAuthorizationsThatAllEnd422StopAtTheBudgetAndNeverReachTheModelAgain() {
        router.replyWith(UNGROUNDED);
        for (int i = 0; i < 3; i++) {
            postPaid(URI, "20000", BODY).expectStatus().isEqualTo(422);
        }
        assertThat(router.modelCalls()).isEqualTo(3);

        for (int i = 0; i < 3; i++) {
            postPaid(URI, "20000", BODY).expectStatus().isEqualTo(429);
        }
        assertThat(router.modelCalls()).isEqualTo(3);
        assertThat(FACILITATOR.settleCallCount()).isZero();
    }

    @Test
    void aSettledRunFreesItsSlotSoHonestTrafficKeepsFlowing() {
        router.replyWith(UNGROUNDED);
        postPaid(URI, "20000", BODY).expectStatus().isEqualTo(422);
        postPaid(URI, "20000", BODY).expectStatus().isEqualTo(422);

        router.replyWith(GROUNDED);
        for (int i = 0; i < 5; i++) {
            postPaid(URI, "20000", BODY).expectStatus().isOk();
        }
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(5);

        // Two unpaid runs are still on the books, so one more unpaid run fits and the next does not.
        router.replyWith(UNGROUNDED);
        postPaid(URI, "20000", BODY).expectStatus().isEqualTo(422);
        postPaid(URI, "20000", BODY).expectStatus().isEqualTo(429);
    }

    @Test
    void theRefusalIsAFixedProblemDetailWithoutQuestionOrChunkText() {
        router.replyWith(UNGROUNDED);
        for (int i = 0; i < 3; i++) {
            postPaid(URI, "20000", BODY).expectStatus().isEqualTo(422);
        }
        String body = postPaid(URI, "20000", BODY)
                .expectStatus()
                .isEqualTo(429)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).contains("Too many answers requested right now").doesNotContain("board");
    }
}
