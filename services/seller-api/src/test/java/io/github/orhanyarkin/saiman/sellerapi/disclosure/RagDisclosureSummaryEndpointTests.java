package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.modelrouter.DailyCapExceededException;
import io.github.orhanyarkin.saiman.modelrouter.DataClass;
import io.github.orhanyarkin.saiman.modelrouter.Tier;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.FakeIngestServer;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.RagTestBase;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrievedChunk;
import io.github.orhanyarkin.x402.core.X402Headers;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /v1/disclosures/{ticker}/summary} with {@code seller.disclosures.source=rag}: the
 * summary is generated from retrieved chunks, cached by {@code (ticker, corpusVersion)}, and every
 * failure is a non-2xx that is never settled.
 */
class RagDisclosureSummaryEndpointTests extends RagTestBase {

    private static final String PRICE = "10000";
    private static final String URI = "/v1/disclosures/THYAO/summary";

    private static final RetrievedChunk C1 = FakeIngestServer.chunk("kap:5:0000", "THYAO", "CHUNKTEXTMARKER one");
    private static final RetrievedChunk C2 = FakeIngestServer.chunk("kap:5:0001", "THYAO", "CHUNKTEXTMARKER two");

    @BeforeEach
    void scriptHappyPath() {
        INGEST.retrieves(List.of(C1, C2), "v-initial");
        router.replyWith(reply("Summary one.", "kap:5:0000"));
    }

    private static String reply(String summary, String... ids) {
        StringBuilder json = new StringBuilder("{\"summary\":\"" + summary + "\",\"citedChunkIds\":[");
        for (int i = 0; i < ids.length; i++) {
            json.append(i == 0 ? "" : ",").append('"').append(ids[i]).append('"');
        }
        return json.append("]}").toString();
    }

    @Test
    void summaryIsGeneratedFromRetrievedChunksAndSettles() {
        INGEST.retrieves(List.of(C1, C2), "v-generate");

        getPaid(URI, PRICE)
                .expectStatus()
                .isOk()
                .expectHeader()
                .exists(X402Headers.PAYMENT_RESPONSE)
                .expectBody()
                .jsonPath("$.ticker")
                .isEqualTo("THYAO")
                .jsonPath("$.summary")
                .isEqualTo("Summary one.")
                .jsonPath("$.dataSource")
                .isEqualTo("kap-rag")
                .jsonPath("$.citations.length()")
                .isEqualTo(1)
                .jsonPath("$.citations[0].chunkId")
                .isEqualTo("kap:5:0000")
                .jsonPath("$.citations[0].sourceUrl")
                .isEqualTo("https://www.kap.org.tr/tr/Bildirim/5")
                .jsonPath("$.citations[0].retrievedAt")
                .isEqualTo("2026-09-29T08:00:00Z");

        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
        // Public KAP text only, so PUBLIC; the buyer supplies nothing to this endpoint.
        assertThat(router.lastTier()).isEqualTo(Tier.TIER1);
        assertThat(router.lastDataClass()).isEqualTo(DataClass.PUBLIC);
        assertThat(INGEST.lastRetrieveBody()).contains("\"topK\":6").contains("[\"THYAO\"]");
    }

    @Test
    void summaryIsCachedByCorpusVersionAndRefreshedWhenItChanges() {
        INGEST.retrieves(List.of(C1, C2), "v-cache-1");

        String first = getPaid(URI, PRICE)
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(router.modelCalls()).isEqualTo(1);

        // Same corpus version: served from Redis. The model is not asked again, even if it now
        // could not answer (retrieval still runs: its corpusVersion is the cache key).
        router.failWith(new DailyCapExceededException("cap"));
        String second = getPaid(URI, PRICE)
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(second).isEqualTo(first);
        assertThat(router.routerRequests()).isZero();
        assertThat(INGEST.retrieveCalls()).isEqualTo(2);
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(2);

        // A purge/supersession changes corpusVersion: the cached text can not be served any more.
        router.replyWith(reply("Summary two.", "kap:5:0001"));
        INGEST.retrieves(List.of(C2), "v-cache-2");
        getPaid(URI, PRICE)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.summary")
                .isEqualTo("Summary two.")
                .jsonPath("$.citations[0].chunkId")
                .isEqualTo("kap:5:0001");
        assertThat(router.modelCalls()).isEqualTo(1);
    }

    @Test
    void aFailedGenerationIsRememberedBrieflyButNeverCachedAsASummary() {
        INGEST.retrieves(List.of(C1, C2), "v-not-cached");
        router.failWith(new IllegalStateException("boom"));
        getPaid(URI, PRICE).expectStatus().isEqualTo(503);
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
        assertOneCreditNote(503);

        // Same corpus version: the failure is negative-cached, so the model is not asked again.
        router.replyWith(reply("Recovered.", "kap:5:0000"));
        getPaid(URI, PRICE).expectStatus().isEqualTo(503);
        assertThat(router.routerRequests()).isZero();

        // A new corpus version is a different key: generation works again.
        INGEST.retrieves(List.of(C1, C2), "v-not-cached-2");
        getPaid(URI, PRICE)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.summary")
                .isEqualTo("Recovered.");
    }

    @Test
    void summaryWithoutAnyValidCitationIs422AndCredited() {
        INGEST.retrieves(List.of(C1, C2), "v-no-citation");
        router.replyWith(reply("Summary.", "kap:1:0001"));

        String body = getPaid(URI, PRICE)
                .expectStatus()
                .isEqualTo(422)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
        assertOneCreditNote(422);
        assertThat(body).doesNotContain("CHUNKTEXTMARKER").doesNotContain("kap:1:0001");
    }

    @Test
    void emptyRetrievalIs404AndCredited() {
        INGEST.retrieves(List.of(), "v-empty");
        getPaid("/v1/disclosures/ZZZZZZ/summary", PRICE).expectStatus().isNotFound();
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
        assertOneCreditNote(404);
        assertThat(router.routerRequests()).isZero();
    }

    @Test
    void malformedModelOutputIs502AndCredited() {
        INGEST.retrieves(List.of(C1, C2), "v-malformed");
        router.replyWith("no json here");
        getPaid(URI, PRICE).expectStatus().isEqualTo(502);
        assertThat(FACILITATOR.settleCallCount()).isEqualTo(1);
        assertOneCreditNote(502);
    }
}
