package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import io.github.orhanyarkin.saiman.sellerapi.testsupport.FakeIngestServer;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.RagTestBase;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrievedChunk;
import java.util.List;
import org.junit.jupiter.api.Test;

/** KAP issuer text (citation title and excerpt) and model prose are link-scrubbed; sourceUrl is not. */
class CitationScrubbingEndpointTests extends RagTestBase {

    private static RetrievedChunk hostile(String id) {
        RetrievedChunk base = FakeIngestServer.chunk(id, "THYAO", "x");
        return new RetrievedChunk(
                base.chunkId(),
                base.ticker(),
                base.source(),
                "Pay //evil.example/t or javascript:alert(1)",
                base.sourceUrl(),
                base.publishedAt(),
                base.retrievedAt(),
                "Tutar 59.368.579,- Euro, A.Ş. 20.06.2016; send to evil.com/pay or [x](http://evil) or data:text/html,hi",
                base.rrfScore(),
                base.vectorRank(),
                base.lexicalRank());
    }

    @Test
    void answerCitationTitleExcerptAndTextAreScrubbedButSourceUrlIsUntouched() {
        INGEST.retrieves(List.of(hostile("kap:5:0000"), hostile("kap:5:0001")), "v-scrub");
        router.replyWith(
                "{\"answer\":\"See [x](http://evil) //evil/p evil.com/pay.\",\"citedChunkIds\":[\"kap:5:0000\",\"kap:5:0001\"]}");

        postPaid("/v1/disclosures/THYAO/questions", "20000", "{\"question\":\"What did the board decide?\"}")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.answer")
                .value(v -> org.assertj.core.api.Assertions.assertThat(v.toString())
                        .doesNotContain("evil")
                        .contains("[link removed]"))
                .jsonPath("$.citations[0].title")
                .isEqualTo("Pay [link removed] or [link removed]")
                .jsonPath("$.citations[0].excerpt")
                .value(v -> org.assertj.core.api.Assertions.assertThat(v.toString())
                        .doesNotContain("evil")
                        .doesNotContain("data:")
                        .contains("59.368.579,- Euro, A.Ş. 20.06.2016"))
                .jsonPath("$.citations[0].sourceUrl")
                .isEqualTo("https://www.kap.org.tr/tr/Bildirim/5");
    }
}
