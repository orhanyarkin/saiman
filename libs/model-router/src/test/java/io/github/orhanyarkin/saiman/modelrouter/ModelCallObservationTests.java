package io.github.orhanyarkin.saiman.modelrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.modelrouter.testing.FakeChatModel;
import io.github.orhanyarkin.saiman.modelrouter.testing.FakeEmbeddingModel;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;

/** One {@code saiman.model.call} observation per model round trip, with cost and scope as attributes. */
class ModelCallObservationTests {

    private static final class Recorder implements ObservationHandler<Observation.Context> {
        final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onStop(Observation.Context context) {
            stopped.add(context);
        }

        List<Observation.Context> calls() {
            return stopped.stream()
                    .filter(c -> CostAdvisor.OBSERVATION.equals(c.getName()))
                    .toList();
        }
    }

    private static String low(Observation.Context c, String key) {
        return value(c.getLowCardinalityKeyValue(key));
    }

    private static String high(Observation.Context c, String key) {
        return value(c.getHighCardinalityKeyValue(key));
    }

    private static String value(KeyValue kv) {
        return kv == null ? "<absent>" : kv.getValue();
    }

    private static DefaultModelRouter router(ChatModel chat, long dayCap, Recorder recorder) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(recorder);
        RouterProperties base = RouterProperties.defaults().withDailyCapUsdMicros(dayCap);
        return new DefaultModelRouter(
                base,
                new ModelFactory() {
                    @Override
                    public ChatModel chatModel(RouterProperties.Route route) {
                        return chat;
                    }

                    @Override
                    public EmbeddingModel embeddingModel(RouterProperties.Embedding route) {
                        return new FakeEmbeddingModel(1536);
                    }
                },
                new InMemoryCostGuard(dayCap, new MutableClock(Instant.parse("2026-09-30T10:00:00Z"))),
                RouterMetrics.NOOP,
                new InMemoryScopedCostGuard(),
                registry);
    }

    @Test
    void aSuccessfulRoundTripIsOneObservationWithCostTokensAndScope() {
        var recorder = new Recorder();
        var client =
                router(new FakeChatModel("x", 1_000, 100), 700_000, recorder).chatClient(Tier.TIER0, DataClass.PUBLIC);

        client.prompt()
                .advisors(a -> a.param(RouterAdvisorParams.COST_SCOPE, "run-42"))
                .user("hi")
                .call()
                .content();

        assertThat(recorder.calls()).hasSize(1);
        Observation.Context call = recorder.calls().get(0);
        assertThat(low(call, "tier")).isEqualTo("tier0");
        assertThat(low(call, "model")).isEqualTo("gpt-5-nano");
        assertThat(low(call, "outcome")).isEqualTo("ok");
        assertThat(high(call, "tokens.in")).isEqualTo("1000");
        assertThat(high(call, "tokens.out")).isEqualTo("100");
        assertThat(high(call, "saiman.cost.usd_micros")).isEqualTo("150"); // 1000 * 0.1 + 100 * 0.5 per MTok
        assertThat(high(call, "saiman.cost.scope")).isEqualTo("run-42");
        // the scope is high cardinality on purpose: it must not be a low-cardinality (metric) tag
        assertThat(call.getLowCardinalityKeyValues().stream().map(KeyValue::getKey))
                .containsExactlyInAnyOrder("tier", "model", "outcome");
    }

    @Test
    void aCallRefusedByTheDailyCapIsAnObservationWithOutcomeCap() {
        var recorder = new Recorder();
        var model = new FakeChatModel("x");
        var client = router(model, 1, recorder).chatClient(Tier.TIER0, DataClass.PUBLIC);

        assertThatThrownBy(() -> client.prompt().user("hi").call().content())
                .isInstanceOf(DailyCapExceededException.class);

        assertThat(recorder.calls()).hasSize(1);
        assertThat(low(recorder.calls().get(0), "outcome")).isEqualTo("cap");
        assertThat(model.callCount()).isZero();
    }

    @Test
    void aCallRefusedByTheScopeBudgetIsAnObservationWithOutcomeScopeBudget() {
        var recorder = new Recorder();
        var client = router(new FakeChatModel("x"), 700_000, recorder).chatClient(Tier.TIER0, DataClass.PUBLIC);

        assertThatThrownBy(() -> client.prompt()
                        .advisors(a -> a.param(RouterAdvisorParams.COST_SCOPE, "run-1")
                                .param(RouterAdvisorParams.COST_SCOPE_BUDGET_USD_MICROS, 5L))
                        .user("hi")
                        .call()
                        .content())
                .isInstanceOf(ScopeBudgetExceededException.class);

        assertThat(low(recorder.calls().get(0), "outcome")).isEqualTo("scope_budget");
        assertThat(high(recorder.calls().get(0), "saiman.cost.scope")).isEqualTo("run-1");
    }

    @Test
    void aFailedCallRecordsTheErrorAndKeepsTheOutcomeError() {
        var recorder = new Recorder();
        ChatModel failing = new ChatModel() {
            @Override
            public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt p) {
                throw new IllegalStateException("boom");
            }
        };
        var client = router(failing, 700_000, recorder).chatClient(Tier.TIER0, DataClass.PUBLIC);

        assertThatThrownBy(() -> client.prompt().user("hi").call().content()).hasMessage("boom");

        Observation.Context call = recorder.calls().get(0);
        assertThat(low(call, "outcome")).isEqualTo("error");
        assertThat(call.getError()).isNotNull();
    }
}
