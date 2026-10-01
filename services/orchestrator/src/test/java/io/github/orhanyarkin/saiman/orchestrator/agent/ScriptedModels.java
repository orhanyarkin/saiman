package io.github.orhanyarkin.saiman.orchestrator.agent;

import io.github.orhanyarkin.saiman.modelrouter.CostGuard;
import io.github.orhanyarkin.saiman.modelrouter.DefaultModelRouter;
import io.github.orhanyarkin.saiman.modelrouter.ModelFactory;
import io.github.orhanyarkin.saiman.modelrouter.RouterMetrics;
import io.github.orhanyarkin.saiman.modelrouter.RouterProperties;
import io.github.orhanyarkin.saiman.modelrouter.ScopedCostGuard;
import io.github.orhanyarkin.saiman.modelrouter.testing.FakeEmbeddingModel;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The test harness for the agents: a {@link ScriptedChatModel} behind the <b>real</b> model router,
 * so data classes, cost scopes, the daily cap, per-round-trip pricing and the {@code
 * saiman.model.call} observation all behave as in production while no key or network is involved.
 *
 * <ul>
 *   <li><b>Unit tests</b>: {@link #router} builds a {@link DefaultModelRouter} over the scripted model
 *       with the library's default routes and prices, {@code require-cost-scope=true} and the given
 *       guards and observation registry (register a {@link ModelCallTap} on that registry to get
 *       model-call events). See {@code AgentPipelineTests}.
 *   <li><b>Spring tests</b> (T4b-2 end to end): {@code @Import(ScriptedModels.Config.class)} replaces
 *       only the router's {@link ModelFactory}; the router itself, its in-memory guards
 *       ({@code saiman.router.cost-guard=memory} in the test config), the observation registry and
 *       {@link ModelCallTap} are the application's. Autowire {@link ScriptedChatModel} to script a run
 *       and call {@link ScriptedChatModel#reset()} before each test. Mark a test-local {@code
 *       ResearchPipeline} {@code @Primary} only if the test must bypass the agents.
 * </ul>
 *
 * Scripting a run: {@code model.then(Reply.text(planJson), Reply.toolCall("disclosureSummary",
 * "{\"ticker\":\"THYAO\"}"), Reply.text("notes kap:1:0001"), Reply.text(riskJson),
 * Reply.text(synthesisJson))}; an adversarial model uses {@link ScriptedChatModel#otherwise}.
 */
public final class ScriptedModels {

    private ScriptedModels() {}

    /** The real router over {@code model}, scopes required, every route answered by the script. */
    public static DefaultModelRouter router(
            ScriptedChatModel model,
            ObservationRegistry observations,
            CostGuard dayGuard,
            ScopedCostGuard scopes,
            long maxScopeBudgetUsdMicros) {
        RouterProperties base = RouterProperties.defaults();
        RouterProperties properties = new RouterProperties(
                base.routes(),
                base.embedding(),
                base.dailyCapUsdMicros(),
                base.prices(),
                base.openai(),
                "memory",
                true,
                maxScopeBudgetUsdMicros);
        return new DefaultModelRouter(properties, factory(model), dayGuard, RouterMetrics.NOOP, scopes, observations);
    }

    /** A model factory whose chat models are the script, one view per route model. */
    public static ModelFactory factory(ScriptedChatModel model) {
        return new ModelFactory() {
            @Override
            public ChatModel chatModel(RouterProperties.Route route) {
                return model.forRoute(route.model());
            }

            @Override
            public EmbeddingModel embeddingModel(RouterProperties.Embedding route) {
                return new FakeEmbeddingModel(route.dimensions());
            }
        };
    }

    /** Puts the scripted model behind the application's auto-configured router. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        ScriptedChatModel scriptedChatModel() {
            return new ScriptedChatModel();
        }

        @Bean
        ModelFactory scriptedModelFactory(ScriptedChatModel model) {
            return factory(model);
        }
    }
}
