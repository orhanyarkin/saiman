package io.github.orhanyarkin.saiman.ingest.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.ingest.IngestIntegrationTests;
import io.github.orhanyarkin.saiman.ingest.RecordingEmbeddingModel;
import io.github.orhanyarkin.saiman.ingest.pipeline.IngestJob;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrieveResponse;
import io.github.orhanyarkin.saiman.shared.retrieval.RetrievedChunk;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(OutputCaptureExtension.class)
class RetrievalTests extends IngestIntegrationTests {

    @Autowired
    private IngestJob job;

    @Autowired
    private RetrievalRepository repository;

    @Autowired
    private RestTestClient client;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void index() {
        job.run();
    }

    private RetrieveResponse retrieve(String query, List<String> tickers, int topK) {
        RetrieveResponse response = client.post()
                .uri("/internal/v1/retrieve")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", query, "tickers", tickers, "topK", topK))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(RetrieveResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    /**
     * The vector leg as production runs it: inside one transaction with relaxed iterative HNSW scans
     * (see {@code HybridRetriever}). Without that setting a ticker-filtered HNSW query is approximate and can
     * return far fewer rows than the endpoint does, or the same rows when the planner happens to pick an exact scan,
     * so an expectation computed outside the transaction is flaky.
     */
    private List<String> vectorLeg(float[] embedding, List<String> tickers) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            repository.enableIterativeScan();
            return repository.vectorLeg(embedding, tickers);
        });
    }

    private String textOf(String chunkId) {
        return jdbc.sql("SELECT content FROM chunk WHERE id = :id")
                .param("id", chunkId)
                .query(String.class)
                .single();
    }

    // --- Turkish lexical leg ------------------------------------------------------------------

    @Test
    void uppercaseQueryFindsLowercaseTurkishText() {
        // "yolları" appears in the superseded 1093000 and in 1101500; only the latter is retrievable
        assertThat(repository.lexicalLeg("YOLLARI", List.of())).containsExactly("kap:1101500:0000");
    }

    @Test
    void dottedCapitalIMatchesIstanbul() {
        assertThat(repository.lexicalLeg("İSTANBUL", List.of())).contains("kap:1100000:0000");
    }

    @Test
    void plainCapitalIMatchesDotlessIsparta() {
        assertThat(repository.lexicalLeg("ISPARTA", List.of()))
                .allSatisfy(id -> assertThat(id).startsWith("kap:1093500:"));
        assertThat(repository.lexicalLeg("ISPARTA", List.of())).isNotEmpty();
    }

    @Test
    void naturalLanguageQuestionsAreOrQueriesNotAllOrNothing() {
        assertThat(repository.lexicalLeg("Hangi şirket yolları görüşmesini tamamladı?", List.of()))
                .contains("kap:1101500:0000");
    }

    @Test
    void queryOperatorsInUserTextAreInert() {
        assertThat(repository.lexicalLeg("'; DROP TABLE chunk; -- & | ! ( ) :*", List.of()))
                .isEmpty();
        assertThat(count("chunk")).isPositive();
    }

    @Test
    void orQueryKeepsOnlyWordsAndCapsTheirNumber() {
        assertThat(RetrievalRepository.orQuery("a, b; or c!")).isEqualTo("a or b or c");
        assertThat(RetrievalRepository.orQuery("...")).isEmpty();
        String many = String.join(" ", java.util.Collections.nCopies(100, "kelime"));
        assertThat(RetrievalRepository.orQuery(many).split(" or ")).hasSize(32);
    }

    // --- hybrid retrieval ---------------------------------------------------------------------

    @Test
    void identicalTextRanksFirstInBothLegs() {
        String text = textOf("kap:1101500:0000");

        RetrieveResponse response = retrieve(text, List.of(), 5);

        RetrievedChunk top = response.chunks().get(0);
        assertThat(top.chunkId()).isEqualTo("kap:1101500:0000");
        assertThat(top.vectorRank()).isEqualTo(1);
        assertThat(top.lexicalRank()).isNotNull();
        assertThat(top.rrfScore()).isGreaterThan(1.0 / 61);
        assertThat(top.sourceUrl()).isEqualTo("https://www.kap.org.tr/tr/Bildirim/1101500");
        assertThat(top.source()).isEqualTo("kap");
        assertThat(top.ticker()).isEqualTo("THYAO");
        assertThat(top.text()).isEqualTo(text);
        assertThat(top.retrievedAt()).isNotNull().isBeforeOrEqualTo(Instant.now());
        assertThat(top.publishedAt()).isEqualTo(Instant.parse("2023-07-15T13:45:00Z")); // 16:45 Istanbul
    }

    @Test
    void aVectorOnlyHitIsStillReturnedWithoutALexicalRank() {
        // no shared word after stemming and stop-word removal -> only the vector leg can find something
        RetrieveResponse response = retrieve("zzzz qqqq", List.of(), 5);

        assertThat(response.chunks()).isNotEmpty();
        assertThat(response.chunks())
                .allSatisfy(c -> assertThat(c.lexicalRank()).isNull());
        assertThat(response.chunks()).allSatisfy(c -> assertThat(c.vectorRank()).isNotNull());
    }

    @Test
    void supersededAndBlockedDocumentsNeverSurface() {
        // querying with the exact text of the superseded disclosures must not return them
        String superseded = jdbc.sql("SELECT content FROM chunk WHERE id = 'kap:1093000:0000'")
                .query(String.class)
                .single();
        RetrieveResponse response = retrieve(superseded, List.of(), 20);

        assertThat(response.chunks())
                .extracting(RetrievedChunk::chunkId)
                .noneMatch(id -> id.startsWith("kap:1093000:")
                        || id.startsWith("kap:1096000:")
                        || id.startsWith("kap:1100500:"));
        assertThat(response.chunks()).extracting(RetrievedChunk::chunkId).contains("kap:1100000:0000");
    }

    @Test
    void tickerFilterRestrictsBothLegs() {
        RetrieveResponse asels = retrieve("sözleşme teslimat radar yolları", List.of("ASELS"), 20);
        RetrieveResponse thyao = retrieve("sözleşme teslimat radar yolları", List.of("THYAO"), 20);

        assertThat(asels.chunks()).isNotEmpty().allMatch(c -> c.ticker().equals("ASELS"));
        assertThat(thyao.chunks()).isNotEmpty().allMatch(c -> c.ticker().equals("THYAO"));
    }

    @Test
    void topKLimitsAndWatermarkIsTheNewestIndexedPublication() {
        RetrieveResponse response = retrieve("yönetim kurulu", List.of(), 2);

        assertThat(response.chunks()).hasSize(2);
        // newest INDEXED disclosure is ASELS 1103000 (10.08.2023 09:00 Istanbul)
        assertThat(response.corpusWatermark()).isEqualTo(Instant.parse("2023-08-10T06:00:00Z"));
    }

    @Test
    void anUnavailableEmbeddingRouteAnswers503WithoutLeakingTheQuery(CapturedOutput output) {
        embeddings.failing(true);

        client.post()
                .uri("/internal/v1/retrieve")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", "GIZLI-SORGU-ZZQ", "tickers", List.of(), "topK", 3))
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String.class)
                .value(body -> assertThat(body).doesNotContain("GIZLI-SORGU-ZZQ"));
        assertThat(output.getAll()).doesNotContain("GIZLI-SORGU-ZZQ");
    }

    @Test
    void queryTextIsNotLogged(CapturedOutput output) {
        retrieve("MAHREM-ARAMA-ZZQ", List.of(), 3);

        assertThat(output.getAll()).doesNotContain("MAHREM-ARAMA-ZZQ");
    }

    // --- recency leg (ADR-0025) ----------------------------------------------------------------

    @Test
    void recencyLegRanksIndexedDocumentsNewestFirstWithOneChunkEach() {
        List<String> newestFirst = jdbc.sql("""
                        SELECT id FROM source_document
                        WHERE status = 'INDEXED' AND ticker = 'THYAO'
                        ORDER BY published_at DESC, id
                        """).query(String.class).list();

        List<String> leg = repository.recencyLeg(List.of("THYAO"));

        assertThat(newestFirst).hasSizeGreaterThan(1);
        assertThat(leg)
                .containsExactlyElementsOf(
                        newestFirst.stream().map(id -> id + ":0000").toList());
        assertThat(repository.recencyLeg(List.of())).isEmpty();
        assertThat(repository.recencyLeg(List.of("NOSUCH"))).isEmpty();
    }

    @Test
    void recencyIntentWithTickersFusesThreeLegs() {
        String query = "THYAO son açıklamalar";
        List<String> tickers = List.of("THYAO");
        float[] embedding = RecordingEmbeddingModel.vector(query);
        List<String> expected = RrfFusion.fuse(
                        vectorLeg(embedding, tickers),
                        repository.lexicalLeg(query, tickers),
                        repository.recencyLeg(tickers),
                        HybridRetriever.DEFAULT_RECENCY_WEIGHT,
                        RrfFusion.DEFAULT_K,
                        20)
                .stream()
                .map(RrfFusion.Fused::id)
                .toList();

        RetrieveResponse response = retrieve(query, tickers, 20);

        assertThat(response.chunks()).extracting(RetrievedChunk::chunkId).containsExactlyElementsOf(expected);
    }

    @Test
    void withoutRecencyIntentOrWithoutTickersTheResultIsTheTwoLegResult() {
        for (var request : List.of(
                Map.entry("THYAO açıklamaları sonuç", List.of("THYAO")), // "sonuç" is not "son"
                Map.entry("THYAO son açıklamalar", List.<String>of()))) { // intent, but no ticker scope
            String query = request.getKey();
            List<String> tickers = request.getValue();
            float[] embedding = RecordingEmbeddingModel.vector(query);
            List<String> twoLeg = RrfFusion.fuse(
                            vectorLeg(embedding, tickers),
                            repository.lexicalLeg(query, tickers),
                            RrfFusion.DEFAULT_K,
                            20)
                    .stream()
                    .map(RrfFusion.Fused::id)
                    .toList();

            RetrieveResponse response = retrieve(query, tickers, 20);

            assertThat(response.chunks()).extracting(RetrievedChunk::chunkId).containsExactlyElementsOf(twoLeg);
        }
    }

    // --- request validation --------------------------------------------------------------------

    @Test
    void invalidRequestsAnswer400ProblemDetails() {
        String tooLong = "x".repeat(501);
        List<Map<String, Object>> bad = List.of(
                Map.of("query", "", "tickers", List.of(), "topK", 3),
                Map.of("query", "   ", "tickers", List.of(), "topK", 3),
                Map.of("query", tooLong, "tickers", List.of(), "topK", 3),
                Map.of("query", "ok", "tickers", List.of("thy"), "topK", 3),
                Map.of("query", "ok", "tickers", List.of("THYAO'; --"), "topK", 3),
                Map.of(
                        "query",
                        "ok",
                        "tickers",
                        List.of("AAA", "BBB", "CCC", "DDD", "EEE", "FFF", "GGG", "HHH", "III", "JJJ", "KKK"),
                        "topK",
                        3),
                Map.of("query", "ok", "tickers", List.of(), "topK", 0),
                Map.of("query", "ok", "tickers", List.of(), "topK", 21),
                Map.of("tickers", List.of(), "topK", 3),
                Map.of("query", "ok", "topK", 3));
        for (Map<String, Object> body : bad) {
            client.post()
                    .uri("/internal/v1/retrieve")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectHeader()
                    .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        }
    }

    @Test
    void malformedJsonAnswers400() {
        client.post()
                .uri("/internal/v1/retrieve")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{not json")
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    // --- chunk lookup and tickers --------------------------------------------------------------

    @Test
    void chunkLookupReturnsTheChunkOrNotFound() {
        client.get()
                .uri("/internal/v1/chunks/kap:1101500:0000")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.chunkId")
                .isEqualTo("kap:1101500:0000")
                .jsonPath("$.ticker")
                .isEqualTo("THYAO");
        client.get()
                .uri("/internal/v1/chunks/kap:1101500:0099")
                .exchange()
                .expectStatus()
                .isNotFound();
        // superseded documents are not addressable either
        client.get()
                .uri("/internal/v1/chunks/kap:1093000:0000")
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "kap:1:12",
                "kap:12345678901:0001",
                "kap:1:0001/../x",
                "kap:1:0001' OR '1'='1",
                "kap:1:0001;DROP TABLE chunk",
                "KAP:1:0001",
                "../../etc/passwd",
                "kap:%:0001",
                ""
            })
    void malformedChunkIdsAreRejectedBeforeTouchingTheDatabase(String id) {
        client.get()
                .uri("/internal/v1/chunks/{id}", id)
                .exchange()
                .expectStatus()
                .value(status -> assertThat(status).isIn(400, 404));
        client.get()
                .uri("/internal/v1/chunks/{id}", id)
                .exchange()
                .expectStatus()
                .value(status -> assertThat(status).isNotEqualTo(200));
    }

    @Test
    void strictlyMalformedIdsGiveProblemDetail400() {
        client.get()
                .uri("/internal/v1/chunks/kap:1:12")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        client.get()
                .uri("/internal/v1/chunks/{id}", "kap:1:0001' OR '1'='1")
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void tickersListDocumentAndChunkCountsOfIndexedDocumentsOnly() {
        client.get()
                .uri("/internal/v1/tickers")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].ticker")
                .isEqualTo("ASELS")
                .jsonPath("$[0].documents")
                .isEqualTo(2)
                .jsonPath("$[1].ticker")
                .isEqualTo("THYAO")
                .jsonPath("$[1].documents")
                .isEqualTo(3); // 1093500, 1100000, 1101500
    }
}
