package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.sellerapi.testsupport.FakeIngestServer;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.RagTestBase;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * ADR-0021 closes the ADR-0015 availability gap: under the upfront flow, attacker-steerable failures
 * (422 after the model ran) are paid and credited, so they can no longer use up the day's
 * unsettled-run budget (3 here) and lock honest buyers out until midnight UTC. Own context: it needs
 * a small budget.
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
    void paidFailuresBeyondTheUnsettledBudgetAreCreditedAndNeverRefused() {
        router.replyWith(UNGROUNDED);
        for (int i = 0; i < 5; i++) {
            postPaid(URI, "20000", BODY).expectStatus().isEqualTo(422);
        }
        assertThat(router.modelCalls()).isEqualTo(5);
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(5);
        assertThat(creditNotes())
                .hasSize(5)
                .allSatisfy(note -> assertThat(note)
                        .containsEntry("http_status", 422)
                        .containsEntry("reason_code", "handler_client_error"));

        router.replyWith(GROUNDED);
        postPaid(URI, "20000", BODY).expectStatus().isOk();
        assertThat(creditNotes()).hasSize(5);
    }
}
