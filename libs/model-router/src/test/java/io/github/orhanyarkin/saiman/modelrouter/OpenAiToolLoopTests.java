package io.github.orhanyarkin.saiman.modelrouter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.shared.money.Money;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

/**
 * The whole path on the wire: Spring AI's tool-calling loop over the OpenAI adapter. Proves that the
 * tool definitions reach the provider (the model's options must be tool-capable), and that every round
 * trip of the loop is charged under the same cost scope.
 */
class OpenAiToolLoopTests {

    private static final class Lookup {
        final AtomicInteger calls = new AtomicInteger();

        @SuppressWarnings({"UnusedMethod", "EffectivelyPrivate"}) // invoked reflectively through @Tool
        @Tool(description = "looks something up")
        public String lookup(String q) {
            calls.incrementAndGet();
            return "found " + q;
        }
    }

    @Test
    void aToolCallingRunOverTheWireIsChargedPerRoundTripUnderOneScope() throws Exception {
        try (var stub = new StubOpenAiServer(r -> r.body().contains("\"role\":\"tool\"")
                ? StubOpenAiServer.Reply.ok(StubOpenAiServer.completion("gpt-5-nano", "all done", 1_000, 100))
                : StubOpenAiServer.Reply.ok(
                        StubOpenAiServer.toolCall("gpt-5-nano", "lookup", "{\"q\":\"x\"}", 1_000, 100)))) {
            RouterProperties base = RouterProperties.defaults();
            var props = new RouterProperties(
                    base.routes(),
                    base.embedding(),
                    base.dailyCapUsdMicros(),
                    base.prices(),
                    new RouterProperties.OpenAi("sk-test-key", 0, Duration.ofSeconds(5)),
                    null,
                    true,
                    200_000);
            var scoped = new InMemoryScopedCostGuard();
            var day = new InMemoryCostGuard(700_000, Clock.systemUTC());
            var router = new DefaultModelRouter(
                    props,
                    new OpenAiModelFactory(props.openai(), stub.baseUrl()),
                    day,
                    RouterMetrics.NOOP,
                    scoped,
                    io.micrometer.observation.ObservationRegistry.NOOP);
            var tools = new Lookup();

            String answer = router.chatClient(Tier.TIER0, DataClass.PUBLIC)
                    .prompt()
                    .advisors(a -> a.param(RouterAdvisorParams.COST_SCOPE, "run-wire"))
                    .tools(tools)
                    .user("find x")
                    .call()
                    .content();

            assertThat(answer).isEqualTo("all done");
            assertThat(tools.calls).hasValue(1);
            assertThat(stub.requests).hasSize(2);
            assertThat(stub.requests.get(0).body()).contains("\"tools\"").contains("lookup");
            assertThat(stub.requests.get(0).body()).contains("max_completion_tokens");
            // two round trips at 1000 in / 100 out on gpt-5-nano: 150 micros each
            assertThat(scoped.spent("run-wire")).isEqualTo(Money.usdMicros(300));
            assertThat(day.todayTotal()).isEqualTo(Money.usdMicros(300));
        }
    }
}
