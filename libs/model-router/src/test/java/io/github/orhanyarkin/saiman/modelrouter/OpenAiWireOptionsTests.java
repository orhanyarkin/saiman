package io.github.orhanyarkin.saiman.modelrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.function.FunctionToolCallback;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What actually leaves the process: the request body and headers a stub OpenAI server sees. */
class OpenAiWireOptionsTests {

    private static final String KEY = "sk-wire-options-KEY-1234567890";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> headers = new CopyOnWriteArrayList<>();
    private HttpServer stub;
    private RouterProperties.Route route;
    private ChatModel model;
    private OpenAiModelFactory factory;

    @BeforeEach
    void start() throws IOException {
        stub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        stub.createContext("/", exchange -> {
            bodies.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            headers.add(Map.of(
                    "x-evil",
                    String.valueOf(exchange.getRequestHeaders().getFirst("X-Evil")),
                    "authorization",
                    String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"))));
            byte[] body = ("{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\","
                            + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
                            + "\"message\":{\"role\":\"assistant\",\"content\":\"pong\"}}],"
                            + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1,\"total_tokens\":4}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        String baseUrl = "http://127.0.0.1:" + stub.getAddress().getPort() + "/v1";
        var credentials = new RouterProperties.OpenAi(KEY, 0, Duration.ofSeconds(5));
        route = RouterProperties.defaults().routes().get(Tier.TIER1);
        factory = new OpenAiModelFactory(credentials, baseUrl);
        model = factory.chatModel(route);
    }

    @AfterEach
    void stop() {
        stub.stop(0);
    }

    private JsonNode send(ChatOptions options) {
        model.call(new Prompt("ping", options));
        assertThat(bodies).hasSize(1);
        return bodies.getFirst();
    }

    private void assertRouteLimitsOnWire(JsonNode body) {
        assertThat(body.path("model").asString()).isEqualTo(route.model());
        assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(route.maxCompletionTokens());
        assertThat(body.has("n")).isFalse();
        assertThat(body.has("max_tokens")).isFalse();
    }

    @Test
    void genericOptionsKeepTheRouteLimitsAndTheCallersTemperature() {
        JsonNode body = send(ChatOptions.builder().temperature(0.3).build());

        assertRouteLimitsOnWire(body);
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.3);
    }

    @Test
    void openAiOptionsWithoutALimitStillGetTheRouteLimitAndReasoningEffort() {
        JsonNode body = send(OpenAiChatOptions.builder().build());

        assertRouteLimitsOnWire(body);
        if (route.reasoningEffort() != null) {
            assertThat(body.path("reasoning_effort").asString()).isEqualTo(route.reasoningEffort());
        }
    }

    @Test
    void aCallerSuppliedOtherModelIsNeverSent() {
        JsonNode body = send(OpenAiChatOptions.builder().model("gpt-5").build());

        assertRouteLimitsOnWire(body);
    }

    @Test
    void aLowerCallerLimitIsHonouredAndAHigherOneClamped() {
        int lower = route.maxCompletionTokens() / 2;
        assertThat(send(OpenAiChatOptions.builder().maxCompletionTokens(lower).build())
                        .path("max_completion_tokens")
                        .asInt())
                .isEqualTo(lower);
        bodies.clear();

        JsonNode clamped = send(OpenAiChatOptions.builder()
                .maxCompletionTokens(route.maxCompletionTokens() * 50)
                .build());
        assertThat(clamped.path("max_completion_tokens").asInt()).isEqualTo(route.maxCompletionTokens());
        bodies.clear();

        JsonNode generic = send(ChatOptions.builder()
                .maxTokens(route.maxCompletionTokens() * 50)
                .build());
        assertRouteLimitsOnWire(generic);
    }

    @Test
    void multipleChoicesExtraBodyAndCustomHeadersAreRejectedAndNothingIsSent() {
        var bad = List.of(
                OpenAiChatOptions.builder().n(3).build(),
                OpenAiChatOptions.builder().extraBody(Map.of("evil", true)).build(),
                OpenAiChatOptions.builder().customHeaders(Map.of("X-Evil", "1")).build(),
                OpenAiChatOptions.builder().baseUrl("https://evil.example/v1").build(),
                OpenAiChatOptions.builder().apiKey("sk-other").build());

        for (OpenAiChatOptions options : bad) {
            assertThatThrownBy(() -> model.call(new Prompt("ping", options)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("sk-other");
        }
        assertThat(bodies).isEmpty();
    }

    @Test
    void theRoutesOwnDefaultsPassThroughUnchanged() {
        // ChatClient seeds requests with the model's default options: baseUrl and apiKey equal the route's
        var seeded = factory.chatOptions(route);

        JsonNode body = send(seeded);

        assertRouteLimitsOnWire(body);
        assertThat(headers.getFirst().get("authorization")).isEqualTo("Bearer " + KEY);
        assertThat(headers.getFirst().get("x-evil")).isEqualTo("null");
    }

    record Lookup(String ticker) {}

    @Test
    void toolCallbacksSurviveForGenericAndOpenAiOptions() {
        var tool = FunctionToolCallback.<Lookup, String>builder("lookup_ticker", in -> "ok")
                .description("look a ticker up")
                .inputType(Lookup.class)
                .build();

        JsonNode generic = send(DefaultToolCallingChatOptions.builder()
                .toolCallbacks(List.of(tool))
                .build());
        bodies.clear();
        JsonNode openAi =
                send(OpenAiChatOptions.builder().toolCallbacks(List.of(tool)).build());

        for (JsonNode body : List.of(generic, openAi)) {
            assertThat(body.path("tools")).hasSize(1);
            assertThat(body.path("tools").get(0).path("function").path("name").asString())
                    .isEqualTo("lookup_ticker");
            assertRouteLimitsOnWire(body);
        }
    }

    @Test
    void streamingRebuildsTheOptionsToo() {
        assertThatThrownBy(() -> model.stream(new Prompt(
                                "ping", OpenAiChatOptions.builder().n(2).build()))
                        .blockLast())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(bodies).isEmpty();
    }
}
