package io.github.orhanyarkin.saiman.sellerapi.disclosure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.apisecurity.testfixtures.TestTokens;
import io.github.orhanyarkin.saiman.modelrouter.DefaultModelRouter;
import io.github.orhanyarkin.saiman.modelrouter.InMemoryCostGuard;
import io.github.orhanyarkin.saiman.modelrouter.ModelFactory;
import io.github.orhanyarkin.saiman.modelrouter.RouterMetrics;
import io.github.orhanyarkin.saiman.modelrouter.RouterProperties;
import io.github.orhanyarkin.saiman.modelrouter.testing.FakeChatModel;
import io.github.orhanyarkin.saiman.modelrouter.testing.FakeEmbeddingModel;
import io.github.orhanyarkin.saiman.modelrouter.testing.TestRouters;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.Concurrently;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.FakeIngestServer;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.RagTestBase;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.RawHttp;
import io.github.orhanyarkin.x402.core.X402Headers;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * {@code POST /internal/v1/eval/questions} (ADR-0025): the paid answer service without x402, behind the {@code evals}
 * service token, the Host allowlist, the run guard and the router's day cap; it never settles and writes nothing.
 */
@RecordApplicationEvents
@ExtendWith(OutputCaptureExtension.class)
class EvalQuestionEndpointTests extends RagTestBase {

    private static final String PATH = "/internal/v1/eval/questions";
    private static final String HOST = "seller-api:8081";
    private static final String BODY = "{\"ticker\":\"THYAO\",\"question\":\"When did the board decide?\"}";
    private static final String CITING_BOTH =
            "{\"answer\":\"On 1 June 2023 [kap:5:0000].\",\"citedChunkIds\":[\"kap:5:0000\",\"kap:5:0001\"]}";

    @LocalServerPort
    private int port;

    @Autowired
    private ObservationRegistry observations;

    @Autowired
    private ApplicationEvents events;

    @BeforeEach
    void twoChunks() {
        INGEST.retrieves(
                List.of(
                        FakeIngestServer.chunk("kap:5:0000", "THYAO", "one"),
                        FakeIngestServer.chunk("kap:5:0001", "THYAO", "two")),
                "v-eval");
    }

    @Test
    void anAnsweredQuestionNeverSettlesRecordsOrPublishes(CapturedOutput output) {
        router.replyWith(CITING_BOTH);
        events.clear();

        // Even with a valid payment header attached, nothing payment-related happens.
        RawHttp.Response response = post(BODY, TestTokens.SERVICE_EVALS, HOST, payment("20000"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"outcome\":\"ANSWERED\"")
                .contains("\"answer\":\"On 1 June 2023 [kap:5:0000].\"")
                .contains("\"chunkId\":\"kap:5:0000\"")
                .contains("\"sourceUrl\":\"https://www.kap.org.tr/tr/Bildirim/5\"")
                .contains("\"publishedAt\":\"2023-06-01T10:00:00Z\"")
                .contains("\"modelCostUsdMicros\":0"); // the fake router emits no cost observation
        assertThat(response.headers()).doesNotContain(X402Headers.PAYMENT_RESPONSE.toLowerCase(java.util.Locale.ROOT));

        // The same prompt as the paid endpoint, with each excerpt's publication time (Istanbul).
        String user = router.chatModel().lastPrompt().getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.USER)
                .map(m -> m.getText())
                .findFirst()
                .orElseThrow();
        assertThat(user)
                .contains("<<<EXCERPT id=kap:5:0000>>>\ntitle: Test disclosure kap:5:0000\npublished: "
                        + "2023-06-01T13:00:00+03:00\n");

        assertThat(FACILITATOR.verifyCallCount()).isZero();
        assertThat(FACILITATOR.settleCallCount()).isZero();
        assertThat(count("settlement")).isZero();
        assertThat(count("credit_note")).isZero();
        assertThat(count("event_publication")).isZero();
        assertThat(events.stream()
                        .map(e -> e.getClass().getName())
                        .filter(n -> n.contains("x402") || n.contains(".payments.") || n.contains("Payment")))
                .isEmpty();
        assertThat(output.getAll()).doesNotContain(TestTokens.SERVICE_EVALS);
    }

    @Test
    void thePaidEndpointGetsTheSameDatedPrompt() {
        router.replyWith(CITING_BOTH);

        postPaid("/v1/disclosures/THYAO/questions", "20000", "{\"question\":\"When did the board decide?\"}")
                .expectStatus()
                .isOk();

        String user = router.chatModel().lastPrompt().getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.USER)
                .map(m -> m.getText())
                .findFirst()
                .orElseThrow();
        assertThat(user).contains("published: 2023-06-01T13:00:00+03:00");
    }

    @Test
    void onlyTheEvalsTokenFromTheComposeNetworkGetsIn() {
        router.replyWith(CITING_BOTH);

        assertThat(post(BODY, null, HOST, null).status()).isEqualTo(401);
        assertThat(post(BODY, TestTokens.UNKNOWN, HOST, null).status()).isEqualTo(401);
        assertThat(post(BODY, TestTokens.SERVICE_LEDGER, HOST, null).status()).isEqualTo(403);
        assertThat(post(BODY, TestTokens.READER, HOST, null).status()).isEqualTo(403);
        assertThat(post(BODY, TestTokens.OPERATOR, HOST, null).status()).isEqualTo(403);
        assertThat(router.routerRequests()).isZero();

        RawHttp.Response foreignHost = post(BODY, TestTokens.SERVICE_EVALS, "localhost:" + port, null);
        assertThat(foreignHost.status()).isEqualTo(400);
        assertThat(foreignHost.body()).contains("Host not allowed");
        assertThat(router.routerRequests()).isZero();

        assertThat(post(BODY, TestTokens.SERVICE_EVALS, HOST, null).status()).isEqualTo(200);
    }

    @Test
    void theInputBoundsAreThoseOfThePaidEndpoint() {
        for (String body : new String[] {
            "{\"ticker\":\"thyao\",\"question\":\"When did the board decide?\"}",
            "{\"ticker\":\"TOOLONGT\",\"question\":\"When did the board decide?\"}",
            "{\"ticker\":\"THYAO\",\"question\":\"ab\"}",
            "{\"ticker\":\"THYAO\",\"question\":\"   \"}",
            "{\"ticker\":\"THYAO\",\"question\":\"" + "x".repeat(501) + "\"}",
            "{\"ticker\":\"THYAO\"}",
            "{}"
        }) {
            RawHttp.Response response = post(body, TestTokens.SERVICE_EVALS, HOST, null);
            assertThat(response.status()).as(body).isEqualTo(400);
            assertThat(response.header("content-type")).startsWith("application/problem+json");
            assertThat(response.body()).as(body).contains("Malformed eval question");
        }
        assertThat(router.routerRequests()).isZero();
    }

    @Test
    void outcomesMapTheAnswerServiceResult() {
        router.replyWith("{\"answer\":\"Not in the excerpts.\",\"citedChunkIds\":[\"kap:9:0000\"]}");
        assertThat(post(BODY, TestTokens.SERVICE_EVALS, HOST, null).body())
                .contains("\"outcome\":\"NO_VALID_CITATIONS\"")
                .contains("\"answer\":\"Not in the excerpts.\"")
                .contains("\"citations\":[]");

        router.replyWith("not json");
        assertThat(post(BODY, TestTokens.SERVICE_EVALS, HOST, null).body()).contains("\"outcome\":\"ERROR\"");

        router.failWith(new IllegalStateException("provider down"));
        assertThat(post(BODY, TestTokens.SERVICE_EVALS, HOST, null).body()).contains("\"outcome\":\"ERROR\"");

        // Fewer than two usable excerpts: refused before any model call.
        router.replyWith(CITING_BOTH);
        INGEST.retrieves(List.of(FakeIngestServer.chunk("kap:5:0000", "THYAO", "one")), "v-one");
        assertThat(post(BODY, TestTokens.SERVICE_EVALS, HOST, null).body())
                .contains("\"outcome\":\"REFUSED\"")
                .contains("\"answer\":null");
        assertThat(router.modelCalls()).isZero();

        // A ticker that is not indexed: refused too.
        assertThat(post(
                                "{\"ticker\":\"GARAN\",\"question\":\"When did the board decide?\"}",
                                TestTokens.SERVICE_EVALS,
                                HOST,
                                null)
                        .body())
                .contains("\"outcome\":\"REFUSED\"");
    }

    @Test
    void theRouterDayCapIsReportedAsLlmCap() {
        FakeChatModel model = new FakeChatModel(CITING_BOTH);
        router.use(TestRouters.overInMemory(model, new FakeEmbeddingModel(1536), 1)); // 1 micro-dollar: always over

        RawHttp.Response response = post(BODY, TestTokens.SERVICE_EVALS, HOST, null);

        assertThat(response.body()).contains("\"outcome\":\"LLM_CAP\"").contains("\"modelCostUsdMicros\":0");
        assertThat(model.callCount()).isZero();
    }

    @Test
    void theCostIsTheRoutersOwnObservation() {
        FakeChatModel model = new FakeChatModel(CITING_BOTH, 1_000, 200);
        router.use(realRouter(model, new FakeEmbeddingModel(1536)));

        RawHttp.Response response = post(BODY, TestTokens.SERVICE_EVALS, HOST, null);

        assertThat(response.body()).contains("\"outcome\":\"ANSWERED\"");
        long cost = Long.parseLong(response.body().replaceAll("(?s).*\"modelCostUsdMicros\":(\\d+).*", "$1"));
        assertThat(cost).isPositive();
    }

    @Test
    void atMostOneEvalAnswerRunsAtATime() {
        router.replyWithDelay(CITING_BOTH, Duration.ofMillis(800));
        IntSupplier call =
                () -> post(BODY, TestTokens.SERVICE_EVALS, HOST, null).status();

        List<Integer> statuses = Concurrently.statuses(List.of(call, call, call));

        assertThat(statuses).containsOnly(200, 429).contains(200, 429);
        assertThat(router.maxConcurrentModelCalls()).isEqualTo(1);
    }

    @Test
    void theBodyCapOfThePaidApiAppliesToo() throws IOException {
        router.replyWith(CITING_BOTH);
        String big = "{\"ticker\":\"THYAO\",\"question\":\"" + "x".repeat(5 * 1024) + "\"}";
        Map<String, String> auth = Map.of("Authorization", TestTokens.bearer(TestTokens.SERVICE_EVALS));

        assertThat(post(big, TestTokens.SERVICE_EVALS, HOST, null).status()).isEqualTo(413);
        assertThat(RawHttp.postChunked(port, PATH, HOST, auth, big).status()).isEqualTo(413);
        assertThat(router.routerRequests()).isZero();
        assertThat(INGEST.retrieveCalls()).isZero();
    }

    @Test
    void theDailyEvalLimitIsA429BeforeAnyWork() {
        router.replyWith(CITING_BOTH);
        redis.opsForValue()
                .set("seller:runs:caller:day:evals:" + java.time.LocalDate.now(java.time.ZoneOffset.UTC), "100");

        RawHttp.Response response = post(BODY, TestTokens.SERVICE_EVALS, HOST, null);

        assertThat(response.status()).isEqualTo(429);
        assertThat(response.header("content-type")).startsWith("application/problem+json");
        assertThat(router.routerRequests()).isZero();
        assertThat(INGEST.retrieveCalls()).isZero();
    }

    private DefaultModelRouter realRouter(ChatModel chat, EmbeddingModel embedding) {
        ModelFactory factory = new ModelFactory() {
            @Override
            public ChatModel chatModel(RouterProperties.Route route) {
                return chat;
            }

            @Override
            public EmbeddingModel embeddingModel(RouterProperties.Embedding route) {
                return embedding;
            }
        };
        return new DefaultModelRouter(
                RouterProperties.defaults(),
                factory,
                new InMemoryCostGuard(RouterProperties.defaults().dailyCapUsdMicros(), Clock.systemUTC()),
                RouterMetrics.NOOP,
                null,
                observations);
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private RawHttp.Response post(String body, @Nullable String token, String host, @Nullable String payment) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        if (token != null) {
            headers.put("Authorization", TestTokens.bearer(token));
        }
        if (payment != null) {
            headers.put(X402Headers.PAYMENT_SIGNATURE, payment);
        }
        try {
            return RawHttp.exchange(port, "POST", PATH, host, headers, body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
