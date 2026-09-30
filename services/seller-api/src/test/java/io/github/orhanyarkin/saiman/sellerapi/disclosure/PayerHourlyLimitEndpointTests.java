package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.sellerapi.testsupport.FakeIngestServer;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.RagTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** F1(b)/F3: one payer may start only so many model runs per rolling hour, paid or not. */
@TestPropertySource(properties = {"seller.llm.max-runs-per-payer-per-hour=2", "seller.llm.max-unsettled-per-day=100"})
class PayerHourlyLimitEndpointTests extends RagTestBase {

    @Test
    void thePayersThirdRunInTheHourIsRefusedBeforeTheModelEvenIfTheFirstTwoSettled() {
        INGEST.retrieves(
                List.of(
                        FakeIngestServer.chunk("kap:5:0000", "THYAO", "one"),
                        FakeIngestServer.chunk("kap:5:0001", "THYAO", "two")),
                "v-hourly");
        router.replyWith("{\"answer\":\"Text.\",\"citedChunkIds\":[\"kap:5:0000\",\"kap:5:0001\"]}");
        String body = "{\"question\":\"What did the board decide?\"}";

        postPaid("/v1/disclosures/THYAO/questions", "20000", body)
                .expectStatus()
                .isOk();
        postPaid("/v1/disclosures/THYAO/questions", "20000", body)
                .expectStatus()
                .isOk();
        postPaid("/v1/disclosures/THYAO/questions", "20000", body)
                .expectStatus()
                .isEqualTo(429);

        assertThat(router.modelCalls()).isEqualTo(2);
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(2);
    }

    @Test
    void aPayerAtTheLimitCostsNoIngestCallsEvenWhenTheSameAuthorizationIsReplayed() {
        INGEST.retrieves(
                List.of(
                        FakeIngestServer.chunk("kap:5:0000", "THYAO", "one"),
                        FakeIngestServer.chunk("kap:5:0001", "THYAO", "two")),
                "v-hourly");
        router.replyWith("{\"answer\":\"Text.\",\"citedChunkIds\":[\"kap:5:0000\",\"kap:5:0001\"]}");
        String body = "{\"question\":\"What did the board decide?\"}";
        postPaid("/v1/disclosures/THYAO/questions", "20000", body)
                .expectStatus()
                .isOk();
        postPaid("/v1/disclosures/THYAO/questions", "20000", body)
                .expectStatus()
                .isOk();
        int retrievesAtLimit = INGEST.retrieveCalls();
        int tickerLookupsAtLimit = INGEST.tickerCalls();

        String replayed = payment("20000");
        for (int i = 0; i < 3; i++) {
            postWith("/v1/disclosures/THYAO/questions", replayed, body)
                    .expectStatus()
                    .isEqualTo(429);
        }
        client.get()
                .uri("/v1/disclosures/THYAO/summary")
                .header("PAYMENT-SIGNATURE", payment("10000"))
                .exchange()
                .expectStatus()
                .isEqualTo(429);

        assertThat(INGEST.retrieveCalls()).isEqualTo(retrievesAtLimit);
        assertThat(INGEST.tickerCalls()).isEqualTo(tickerLookupsAtLimit);
        assertThat(router.modelCalls()).isEqualTo(2);
    }
}
