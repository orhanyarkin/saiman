package io.github.orhanyarkin.saiman.modelrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.shared.money.Money;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Fallback inside the OpenAI adapter, on the wire against a stub: only for connect/timeout/429/5xx, never
 * for another 4xx, a breaker per route, priced at the dearer of the two models.
 */
class OpenAiFallbackTests {

    private static final String PRIMARY = "gpt-5-nano";
    private static final String BACKUP = "gpt-5-mini";

    private static RouterProperties props(Duration timeout, RouterProperties.@Nullable Fallback fallback) {
        RouterProperties base = RouterProperties.defaults();
        var routes = new EnumMap<Tier, RouterProperties.Route>(Tier.class);
        routes.putAll(base.routes());
        RouterProperties.Route tier0 = base.routes().get(Tier.TIER0);
        routes.put(
                Tier.TIER0,
                new RouterProperties.Route(
                        tier0.provider(),
                        tier0.model(),
                        tier0.maxCompletionTokens(),
                        Set.of(DataClass.PUBLIC),
                        tier0.region(),
                        tier0.reasoningEffort(),
                        fallback));
        return new RouterProperties(
                routes,
                base.embedding(),
                base.dailyCapUsdMicros(),
                base.prices(),
                new RouterProperties.OpenAi("sk-test-key", 0, timeout),
                null);
    }

    private static DefaultModelRouter router(
            RouterProperties p, StubOpenAiServer stub, CostGuard guard, CircuitBreakerConfig breaker) {
        return new DefaultModelRouter(
                p, new OpenAiModelFactory(p.openai(), stub.baseUrl(), breaker), guard, RouterMetrics.NOOP);
    }

    private static RouterProperties.Fallback backup() {
        return new RouterProperties.Fallback(BACKUP, 3000, "low");
    }

    private static CostGuard guard() {
        return new InMemoryCostGuard(700_000, Clock.systemUTC());
    }

    private static ChatClient tier0(DefaultModelRouter router) {
        return router.chatClient(Tier.TIER0, DataClass.PUBLIC);
    }

    private static String ask(ChatClient client) {
        return client.prompt().user("ping").call().content();
    }

    private static StubOpenAiServer stub(
            java.util.function.Function<StubOpenAiServer.Request, StubOpenAiServer.Reply> f) throws Exception {
        return new StubOpenAiServer(f);
    }

    private static StubOpenAiServer.Reply primaryFails(StubOpenAiServer.Request r, int status) {
        return r.model().equals(PRIMARY)
                ? StubOpenAiServer.Reply.status(status)
                : StubOpenAiServer.Reply.ok(StubOpenAiServer.completion(r.model(), "from-backup", 10, 5));
    }

    @Test
    void aServerErrorFallsBackToTheBackupModel() throws Exception {
        try (var stub = stub(r -> primaryFails(r, 503))) {
            var p = props(Duration.ofSeconds(5), backup());

            String answer = ask(tier0(router(p, stub, guard(), OpenAiFailoverChatModel.defaultBreakerConfig())));

            assertThat(answer).isEqualTo("from-backup");
            assertThat(stub.hits(PRIMARY)).isEqualTo(1);
            assertThat(stub.hits(BACKUP)).isEqualTo(1);
            // the backup's own reasoning effort is on the wire, and a completion limit is always sent (the lower of
            // the backup's and the primary's seeded limit)
            assertThat(stub.requests.get(1).body())
                    .contains("\"reasoning_effort\":\"low\"")
                    .contains("max_completion_tokens");
        }
    }

    @Test
    void aRateLimitFallsBackToTheBackupModel() throws Exception {
        try (var stub = stub(r -> primaryFails(r, 429))) {
            var p = props(Duration.ofSeconds(5), backup());

            assertThat(ask(tier0(router(p, stub, guard(), OpenAiFailoverChatModel.defaultBreakerConfig()))))
                    .isEqualTo("from-backup");
        }
    }

    @Test
    void aTimeoutFallsBackToTheBackupModel() throws Exception {
        try (var stub = stub(r -> r.model().equals(PRIMARY)
                ? new StubOpenAiServer.Reply(200, StubOpenAiServer.completion(PRIMARY, "slow", 1, 1), 3_000)
                : StubOpenAiServer.Reply.ok(StubOpenAiServer.completion(r.model(), "from-backup", 10, 5)))) {
            var p = props(Duration.ofMillis(500), backup());

            assertThat(ask(tier0(router(p, stub, guard(), OpenAiFailoverChatModel.defaultBreakerConfig()))))
                    .isEqualTo("from-backup");
        }
    }

    @Test
    void aConnectFailureFallsBackToTheBackupModel() {
        // nothing listens on the port: the primary fails to connect, and so does the backup (same server), so the
        // backup's failure propagates with the primary's attached
        var p = props(Duration.ofSeconds(2), backup());
        var router = new DefaultModelRouter(
                p, new OpenAiModelFactory(p.openai(), "http://127.0.0.1:1/v1"), guard(), RouterMetrics.NOOP);

        assertThatThrownBy(() -> ask(tier0(router)))
                .satisfies(e -> assertThat(e.getSuppressed()).isNotEmpty());
    }

    @Test
    void aClientErrorOtherThan429NeverFallsBack() throws Exception {
        for (int status : new int[] {400, 401, 404, 422}) {
            try (var stub = stub(r -> primaryFails(r, status))) {
                var p = props(Duration.ofSeconds(5), backup());

                assertThatThrownBy(() ->
                                ask(tier0(router(p, stub, guard(), OpenAiFailoverChatModel.defaultBreakerConfig()))))
                        .isInstanceOf(RuntimeException.class);

                assertThat(stub.hits(BACKUP)).as("backup hits after %d", status).isZero();
            }
        }
    }

    @Test
    void aStreamThatFailsBeforeAnyContentFallsBack() throws Exception {
        try (var stub = stub(r -> r.model().equals(PRIMARY)
                ? StubOpenAiServer.Reply.status(503)
                : StubOpenAiServer.Reply.ok(StubOpenAiServer.streamed(r.model(), "from-backup", 10, 5)))) {
            var p = props(Duration.ofSeconds(5), backup());

            List<String> chunks = tier0(router(p, stub, guard(), OpenAiFailoverChatModel.defaultBreakerConfig()))
                    .prompt()
                    .user("ping")
                    .stream()
                    .content()
                    .collectList()
                    .block();

            assertThat(String.join("", chunks)).isEqualTo("from-backup");
            assertThat(stub.hits(BACKUP)).isEqualTo(1);
        }
    }

    @Test
    void anAnsweredCallNeverReachesTheBackup() throws Exception {
        try (var stub =
                stub(r -> StubOpenAiServer.Reply.ok(StubOpenAiServer.completion(r.model(), "primary-ok", 10, 5)))) {
            var p = props(Duration.ofSeconds(5), backup());

            assertThat(ask(tier0(router(p, stub, guard(), OpenAiFailoverChatModel.defaultBreakerConfig()))))
                    .isEqualTo("primary-ok");
            assertThat(stub.hits(BACKUP)).isZero();
        }
    }

    @Test
    void anOpenBreakerSkipsThePrimaryUntilItProbesAgain() throws Exception {
        var breaker = CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofHours(1))
                .build();
        try (var stub = stub(r -> primaryFails(r, 503))) {
            var p = props(Duration.ofSeconds(5), backup());
            var client = tier0(router(p, stub, guard(), breaker));

            ask(client);
            ask(client); // second failure opens the breaker
            ask(client);
            ask(client);

            assertThat(stub.hits(PRIMARY)).isEqualTo(2);
            assertThat(stub.hits(BACKUP)).isEqualTo(4);
        }
    }

    @Test
    void theReservationIsPricedAtTheDearerOfTheTwoRoutes() throws Exception {
        var estimates = new java.util.ArrayList<Long>();
        CostGuard recording = new CostGuard() {
            final InMemoryCostGuard delegate = new InMemoryCostGuard(700_000, Clock.systemUTC());

            @Override
            public Reservation reserve(Money estimate) {
                estimates.add(estimate.atomicUnits());
                return delegate.reserve(estimate);
            }

            @Override
            public void settle(Reservation reservation, Money actual) {
                delegate.settle(reservation, actual);
            }

            @Override
            public Money todayTotal() {
                return delegate.todayTotal();
            }
        };
        try (var stub = stub(r -> StubOpenAiServer.Reply.ok(StubOpenAiServer.completion(r.model(), "ok", 1, 1)))) {
            var breaker = OpenAiFailoverChatModel.defaultBreakerConfig();
            ask(tier0(router(props(Duration.ofSeconds(5), null), stub, recording, breaker)));
            ask(tier0(router(props(Duration.ofSeconds(5), backup()), stub, recording, breaker)));
        }

        // gpt-5-mini (3000 tokens) costs more than gpt-5-nano (2000 tokens): the backed-up route reserves more
        assertThat(estimates).hasSize(2);
        assertThat(estimates.get(1)).isGreaterThan(estimates.get(0));
    }

    @Test
    void theFallbackAnswerIsPricedAtTheFallbackModel() throws Exception {
        var day = new InMemoryCostGuard(700_000, Clock.systemUTC());
        try (var stub = stub(r -> r.model().equals(PRIMARY)
                ? StubOpenAiServer.Reply.status(503)
                : StubOpenAiServer.Reply.ok(
                        StubOpenAiServer.completion(BACKUP, "from-backup", 1_000_000, 1_000_000)))) {
            var p = props(Duration.ofSeconds(5), backup());

            ask(tier0(router(p, stub, day, OpenAiFailoverChatModel.defaultBreakerConfig())));

            var price = p.prices().get(BACKUP);
            assertThat(day.todayTotal()).isEqualTo(CostCalculator.cost(price, 1_000_000, 1_000_000));
        }
    }

    @Test
    void aDataClassTheRouteDoesNotAllowIsRefusedBeforeAnythingIsSent() throws Exception {
        try (var stub = stub(r -> primaryFails(r, 503))) {
            var p = props(Duration.ofSeconds(5), backup()); // the route (and so its fallback) allows PUBLIC only
            var router = router(p, stub, guard(), OpenAiFailoverChatModel.defaultBreakerConfig());

            assertThatThrownBy(() -> router.chatClient(Tier.TIER0, DataClass.INTERNAL))
                    .isInstanceOf(DataClassViolationException.class)
                    .hasMessageContaining("tier TIER0");
            assertThat(stub.requests).isEmpty();
        }
    }

    @Test
    void aFallbackModelWithoutAPriceFailsStartup() {
        var p = props(Duration.ofSeconds(5), new RouterProperties.Fallback("gpt-unpriced", 100, null));

        assertThatThrownBy(() ->
                        new DefaultModelRouter(p, new OpenAiModelFactory(p.openai()), guard(), RouterMetrics.NOOP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no price");
    }

    @Test
    void aFallbackWithoutATokenLimitFailsStartup() {
        var p = props(Duration.ofSeconds(5), new RouterProperties.Fallback(BACKUP, 0, null));

        assertThatThrownBy(() ->
                        new DefaultModelRouter(p, new OpenAiModelFactory(p.openai()), guard(), RouterMetrics.NOOP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fallback.max-completion-tokens");
    }
}
