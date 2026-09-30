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
 * F1/F3: the model runs before settlement, so a request that ends non-2xx after the model ran has
 * cost money and paid nothing. The starter keeps that request's nonce claim (no replay of the same
 * authorization) and the run guard bounds what fresh authorizations can do.
 */
class UnpaidRunControlsTests extends RagTestBase {

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

    private String unsettledCounter() {
        String value = redis.opsForValue().get("seller:runs:unsettled:" + LocalDate.now(ZoneOffset.UTC));
        return value == null ? "0" : value;
    }

    @Test
    void aWorkDone422KeepsTheClaimSoTheSameAuthorizationCanNotBuyAnotherModelRun() {
        router.replyWith(answer("kap:1:0001", "kap:2:0002")); // ungrounded: 422 after the model ran
        String header = payment(ANSWER_PRICE);

        postWith(QUESTIONS, header, BODY).expectStatus().isEqualTo(422);
        assertThat(router.modelCalls()).isEqualTo(1);

        postWith(QUESTIONS, header, BODY).expectStatus().isEqualTo(402);
        assertThat(router.modelCalls()).isEqualTo(1);
        assertThat(FACILITATOR.settleCallCount()).isZero();
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
    }

    @Test
    void aFailureBeforeAnythingWasSentToTheProviderStillReleasesTheClaim() {
        router.failWith(new DailyCapExceededException("cap"));
        String header = payment(ANSWER_PRICE);
        postWith(QUESTIONS, header, BODY).expectStatus().isEqualTo(503);

        // Nothing was spent, so the buyer may retry the very same authorization.
        router.replyWith(answer("kap:5:0000", "kap:5:0001"));
        postWith(QUESTIONS, header, BODY).expectStatus().isOk();
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
    }

    @Test
    void aRejectedBodyBeforeAnyModelRunStillReleasesTheClaim() {
        router.replyWith(answer("kap:5:0000", "kap:5:0001"));
        String header = payment(ANSWER_PRICE);

        postWith(QUESTIONS, header, "{\"question\":\"x\"}").expectStatus().isEqualTo(400);
        postWith(QUESTIONS, header, BODY).expectStatus().isOk();
    }

    @Test
    void settledRunsGiveTheirUnsettledSlotBackAndUnpaidOnesKeepIt() {
        router.replyWith(answer("kap:1:0001", "kap:2:0002"));
        postPaid(QUESTIONS, ANSWER_PRICE, BODY).expectStatus().isEqualTo(422);
        assertThat(unsettledCounter()).isEqualTo("1");

        router.replyWith(answer("kap:5:0000", "kap:5:0001"));
        postPaid(QUESTIONS, ANSWER_PRICE, BODY).expectStatus().isOk();
        // +1 at start, -1 at settlement: only the unpaid run is left.
        assertThat(unsettledCounter()).isEqualTo("1");

        router.replyWith("{\"summary\":\"S.\",\"citedChunkIds\":[\"kap:5:0000\"]}");
        // A summary served from the cache starts no run, so its settlement must not give a slot back.
        getPaid(SUMMARY, SUMMARY_PRICE).expectStatus().isOk(); // generates: +1 -1
        getPaid(SUMMARY, SUMMARY_PRICE).expectStatus().isOk(); // cache hit: no change
        assertThat(unsettledCounter()).isEqualTo("1");
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
        assertThat(router.modelCalls())
                .isEqualTo(
                        Math.toIntExact(statuses.stream().filter(s -> s == 200).count()));
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(router.modelCalls());
        assertThat(unsettledCounter()).isEqualTo("0");
    }
}
