package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.modelrouter.DailyCapExceededException;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.Concurrently;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.FakeIngestServer;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.RagTestBase;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrievedChunk;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * F1/F3 under the x402 upfront flow (ADR-0021): both LLM endpoints settle before the handler runs, so
 * every model run is paid. A request that then ends non-2xx is credited, its nonce claim is kept (no
 * replay of the same authorization), the run guard's per-payer limits still apply, and the unsettled
 * day counter is never touched.
 */
class UpfrontRunControlsTests extends RagTestBase {

    private static final String ANSWER_PRICE = "20000";
    private static final String SUMMARY_PRICE = "10000";
    private static final String QUESTIONS = "/v1/disclosures/THYAO/questions";
    private static final String SUMMARY = "/v1/disclosures/THYAO/summary";
    private static final String BODY = "{\"question\":\"What did the board decide?\"}";

    private static final RetrievedChunk C1 = FakeIngestServer.chunk("kap:5:0000", "THYAO", "one");
    private static final RetrievedChunk C2 = FakeIngestServer.chunk("kap:5:0001", "THYAO", "two");

    @BeforeEach
    void script() {
        INGEST.retrieves(List.of(C1, C2), "v-unpaid");
    }

    private static String answer(String... ids) {
        return "{\"answer\":\"Text.\",\"citedChunkIds\":[\"" + String.join("\",\"", ids) + "\"]}";
    }

    private String unsettledKey() {
        return "seller:runs:unsettled:" + LocalDate.now(ZoneOffset.UTC);
    }

    @Test
    void aPaid422IsCreditedAndTheSameAuthorizationCanNotBuyAnotherModelRun() {
        router.replyWith(answer("kap:1:0001", "kap:2:0002")); // ungrounded: 422 after the model ran
        String header = payment(ANSWER_PRICE);

        postWith(QUESTIONS, header, BODY).expectStatus().isEqualTo(422);
        assertThat(router.modelCalls()).isEqualTo(1);
        assertOneCreditNote(422);

        postWith(QUESTIONS, header, BODY).expectStatus().isEqualTo(402);
        assertThat(router.modelCalls()).isEqualTo(1);
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
    }

    @Test
    void theSameHoldsForTheSummaryEndpoint() {
        router.replyWith("{\"summary\":\"Text.\",\"citedChunkIds\":[\"kap:9:9999\"]}");
        String header = payment(SUMMARY_PRICE);

        client.get()
                .uri(SUMMARY)
                .header("PAYMENT-SIGNATURE", header)
                .exchange()
                .expectStatus()
                .isEqualTo(422);
        client.get()
                .uri(SUMMARY)
                .header("PAYMENT-SIGNATURE", header)
                .exchange()
                .expectStatus()
                .isEqualTo(402);

        assertThat(router.modelCalls()).isEqualTo(1);
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
    }

    @Test
    void aFailureBeforeAnythingWasSentToTheProviderIsStillCreditedAndNotReplayable() {
        router.failWith(new DailyCapExceededException("cap"));
        String header = payment(ANSWER_PRICE);
        postWith(QUESTIONS, header, BODY).expectStatus().isEqualTo(503);
        assertOneCreditNote(503);

        // The money moved before the handler ran, so the authorization is spent.
        router.replyWith(answer("kap:5:0000", "kap:5:0001"));
        postWith(QUESTIONS, header, BODY).expectStatus().isEqualTo(402);
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
    }

    @Test
    void aRejectedBodyIsCreditedAndNotReplayable() {
        router.replyWith(answer("kap:5:0000", "kap:5:0001"));
        String header = payment(ANSWER_PRICE);

        postWith(QUESTIONS, header, "{\"question\":\"x\"}").expectStatus().isEqualTo(400);
        assertOneCreditNote(400);
        postWith(QUESTIONS, header, BODY).expectStatus().isEqualTo(402);
        assertThat(router.modelCalls()).isZero();
    }

    @Test
    void settledRunsNeitherCountAgainstNorAreRefusedByTheUnsettledDayBudget() {
        // The day's unsettled budget (100 by default) is already used up: settled runs still go through.
        redis.opsForValue().set(unsettledKey(), "1000");

        router.replyWith(answer("kap:1:0001", "kap:2:0002"));
        postPaid(QUESTIONS, ANSWER_PRICE, BODY).expectStatus().isEqualTo(422);
        router.replyWith(answer("kap:5:0000", "kap:5:0001"));
        postPaid(QUESTIONS, ANSWER_PRICE, BODY).expectStatus().isOk();
        router.replyWith("{\"summary\":\"S.\",\"citedChunkIds\":[\"kap:5:0000\"]}");
        getPaid(SUMMARY, SUMMARY_PRICE).expectStatus().isOk(); // generates
        getPaid(SUMMARY, SUMMARY_PRICE).expectStatus().isOk(); // cache hit

        assertThat(FACILITATOR.settleCallCount()).isEqualTo(4);
        assertThat(redis.opsForValue().get(unsettledKey())).isEqualTo("1000");

        // And without a pre-set counter, nothing creates one.
        redis.delete(unsettledKey());
        router.replyWith(answer("kap:5:0000", "kap:5:0001"));
        postPaid(QUESTIONS, ANSWER_PRICE, BODY).expectStatus().isOk();
        assertThat(redis.hasKey(unsettledKey())).isFalse();
    }

    @Test
    void aBurstOfFreshAuthorizationsFromOnePayerRunsAtMostTheInFlightCapAtOnce() {
        router.replyWithDelay(answer("kap:5:0000", "kap:5:0001"), Duration.ofMillis(600));
        List<IntSupplier> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String header = payment(ANSWER_PRICE);
            calls.add(() ->
                    postWith(QUESTIONS, header, BODY).returnResult().getStatus().value());
        }

        List<Integer> statuses = Concurrently.statuses(calls);

        assertThat(router.maxConcurrentModelCalls()).isLessThanOrEqualTo(2);
        assertThat(statuses).allMatch(status -> status == 200 || status == 429);
        assertThat(statuses).contains(429);
        long served = statuses.stream().filter(s -> s == 200).count();
        assertThat(router.modelCalls()).isEqualTo(Math.toIntExact(served));
        // Every request settled up front; every refused one is credited.
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(8);
        assertThat(creditNotes()).hasSize(8 - Math.toIntExact(served));
        assertThat(redis.hasKey(unsettledKey())).isFalse();
    }
}
