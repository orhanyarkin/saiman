package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.sellerapi.testsupport.Concurrently;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.FakeIngestServer;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.RagTestBase;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrievedChunk;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;

/** F4 (single-flight, negative cache), F6 (no paid embedding for unknown tickers), F7 (URLs), F8 (cache validation). */
class GroundingHardeningEndpointTests extends RagTestBase {

    private static final String SUMMARY = "/v1/disclosures/THYAO/summary";
    private static final String QUESTIONS = "/v1/disclosures/THYAO/questions";
    private static final String BODY = "{\"question\":\"What did the board decide?\"}";

    private static final RetrievedChunk C1 = FakeIngestServer.chunk("kap:5:0000", "THYAO", "CHUNKONE");
    private static final RetrievedChunk C2 = FakeIngestServer.chunk("kap:5:0001", "THYAO", "CHUNKTWO");

    @BeforeEach
    void script() {
        INGEST.retrieves(List.of(C1, C2), "v-hardening");
    }

    private static String summaryReply(String text) {
        return "{\"summary\":\"" + text + "\",\"citedChunkIds\":[\"kap:5:0000\"]}";
    }

    private static RetrievedChunk withUrl(RetrievedChunk chunk, String url) {
        return new RetrievedChunk(
                chunk.chunkId(),
                chunk.ticker(),
                chunk.source(),
                chunk.title(),
                url,
                chunk.publishedAt(),
                chunk.retrievedAt(),
                chunk.text(),
                chunk.rrfScore(),
                chunk.vectorRank(),
                chunk.lexicalRank());
    }

    // ---- F4 ----

    @Test
    void concurrentMissesForOneSummaryShareASingleModelCall() {
        router.replyWithDelay(summaryReply("Shared."), Duration.ofMillis(500));
        List<IntSupplier> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String header = payment("10000");
            calls.add(() -> client.get()
                    .uri(SUMMARY)
                    .header("PAYMENT-SIGNATURE", header)
                    .exchange()
                    .returnResult()
                    .getStatus()
                    .value());
        }

        List<Integer> statuses = Concurrently.statuses(calls);

        assertThat(statuses).containsOnly(200);
        assertThat(router.modelCalls()).isEqualTo(1);
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(6);
    }

    @Test
    void concurrentMissesForASummaryTheModelCanNotProduceCostOneModelCallToo() {
        router.replyWithDelay("no json at all", Duration.ofMillis(400));
        List<IntSupplier> calls = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String header = payment("10000");
            calls.add(() -> client.get()
                    .uri(SUMMARY)
                    .header("PAYMENT-SIGNATURE", header)
                    .exchange()
                    .returnResult()
                    .getStatus()
                    .value());
        }

        List<Integer> statuses = Concurrently.statuses(calls);

        assertThat(router.modelCalls()).isEqualTo(1);
        assertThat(statuses).containsOnly(502, 503);
        assertThat(FACILITATOR.settleCallCount()).isZero();
    }

    // ---- F6 ----

    @Test
    void anUnknownTickerOnTheSummaryMakesNoRetrievalCall() {
        getPaid("/v1/disclosures/ZZZZZZ/summary", "10000").expectStatus().isNotFound();

        assertThat(INGEST.tickerCalls()).isEqualTo(1);
        assertThat(INGEST.retrieveCalls()).isZero();
        assertThat(router.routerRequests()).isZero();
        assertThat(FACILITATOR.settleCallCount()).isZero();
    }

    // ---- F7 ----

    @Test
    void aChunkWithANonKapSourceUrlIsNeverShownToTheModelNorCited() {
        RetrievedChunk foreign =
                withUrl(FakeIngestServer.chunk("kap:5:0002", "THYAO", "EVILCHUNK"), "https://evil.example/x");
        INGEST.retrieves(List.of(C1, foreign, C2), "v-url");
        router.replyWith("{\"answer\":\"Text.\",\"citedChunkIds\":[\"kap:5:0002\",\"kap:5:0000\",\"kap:5:0001\"]}");

        String body = postPaid(QUESTIONS, "20000", BODY)
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).doesNotContain("kap:5:0002").doesNotContain("evil.example");
        String user = router.chatModel().lastPrompt().getInstructions().stream()
                .filter(message -> message.getMessageType() == MessageType.USER)
                .map(message -> message.getText())
                .findFirst()
                .orElseThrow();
        assertThat(user).doesNotContain("EVILCHUNK");
    }

    @Test
    void urlsInsideTheAnswerTextAreReplaced() {
        router.replyWith("{\"answer\":\"See https://evil.example/a and www.evil.example/b or ftp://x.example/c now.\","
                + "\"citedChunkIds\":[\"kap:5:0000\",\"kap:5:0001\"]}");

        postPaid(QUESTIONS, "20000", BODY)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.answer")
                .isEqualTo("See [link removed] and [link removed] or [link removed] now.");
    }

    @Test
    void urlsInsideTheSummaryTextAreReplacedToo() {
        router.replyWith(summaryReply("Read https://evil.example/a first."));

        getPaid(SUMMARY, "10000")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.summary")
                .isEqualTo("Read [link removed] first.");
    }

    // ---- F8 ----

    @Test
    void aCachedValueThatFailsValidationIsAMissNotAResponse() {
        router.replyWith(summaryReply("Fresh."));
        String key = DisclosureSummaryCache.key("THYAO", "v-hardening");

        for (String poisoned : List.of(
                "not json at all",
                "{\"ticker\":\"THYAO\",\"summary\":\"Buy at https://evil.example\",\"citations\":[{\"chunkId\":\"kap:5:0000\","
                        + "\"sourceUrl\":\"https://www.kap.org.tr/tr/Bildirim/5\",\"retrievedAt\":\"2026-09-29T08:00:00Z\"}],"
                        + "\"dataSource\":\"kap-rag\"}",
                "{\"ticker\":\"GARAN\",\"summary\":\"Wrong ticker.\",\"citations\":[{\"chunkId\":\"kap:5:0000\","
                        + "\"sourceUrl\":\"https://www.kap.org.tr/tr/Bildirim/5\",\"retrievedAt\":\"2026-09-29T08:00:00Z\"}],"
                        + "\"dataSource\":\"kap-rag\"}",
                "{\"ticker\":\"THYAO\",\"summary\":\"Bad citation URL.\",\"citations\":[{\"chunkId\":\"kap:5:0000\","
                        + "\"sourceUrl\":\"https://evil.example/x\",\"retrievedAt\":\"2026-09-29T08:00:00Z\"}],"
                        + "\"dataSource\":\"kap-rag\"}",
                "{\"ticker\":\"THYAO\",\"summary\":\"No citations.\",\"citations\":[],\"dataSource\":\"kap-rag\"}")) {
            redis.opsForValue().set(key, poisoned);
            int callsBefore = router.modelCalls();

            String body = getPaid(SUMMARY, "10000")
                    .expectStatus()
                    .isOk()
                    .expectBody(String.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(body).contains("Fresh.").doesNotContain("evil.example").doesNotContain("Wrong ticker");
            assertThat(router.modelCalls())
                    .as("a poisoned cache entry is a miss")
                    .isEqualTo(callsBefore + 1);
            redis.delete(key);
        }
    }

    @Test
    void aValidCachedValueIsStillAHit() {
        router.replyWith(summaryReply("First."));
        getPaid(SUMMARY, "10000").expectStatus().isOk();
        getPaid(SUMMARY, "10000").expectStatus().isOk();
        assertThat(router.modelCalls()).isEqualTo(1);
    }
}
