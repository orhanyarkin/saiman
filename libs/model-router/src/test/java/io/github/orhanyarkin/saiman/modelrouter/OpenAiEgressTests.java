package io.github.orhanyarkin.saiman.modelrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.orhanyarkin.saiman.modelrouter.testing.FakeEmbeddingModel;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Where the OpenAI client is allowed to send the API key and prompts: only to the fixed base URL,
 * never to a URL taken from the environment; and what a failing call may reveal.
 */
@ExtendWith(OutputCaptureExtension.class)
class OpenAiEgressTests {

    private static final String KEY = "sk-planted-egress-KEY-1234567890";

    private static RouterProperties.OpenAi credentials(String key, int retries) {
        return new RouterProperties.OpenAi(key, retries, Duration.ofSeconds(5));
    }

    @Test
    void optionsCarryTheFixedBaseUrlAndTheCostLimits() {
        var factory = new OpenAiModelFactory(new RouterProperties.OpenAi(KEY + "\n", 1, Duration.ofSeconds(30)));
        var defaults = RouterProperties.defaults();
        var route = defaults.routes().get(Tier.TIER1);

        var chat = factory.chatOptions(route);
        var embedding = factory.embeddingOptions(defaults.embedding());

        assertThat(chat.getBaseUrl()).isEqualTo("https://api.openai.com/v1");
        assertThat(embedding.getBaseUrl()).isEqualTo("https://api.openai.com/v1");
        assertThat(chat.getMaxCompletionTokens()).isEqualTo(route.maxCompletionTokens());
        assertThat(chat.getMaxRetries()).isEqualTo(1);
        assertThat(chat.getTimeout()).hasSeconds(30);
        assertThat(embedding.getMaxRetries()).isEqualTo(1);
        assertThat(chat.getStreamOptions().includeUsage()).isTrue();
        assertThat(chat.getApiKey()).isEqualTo(KEY); // trailing newline of a hand-made secret file removed
    }

    @Test
    void theApiKeyIsRequiredAndBlankCountsAsMissing() {
        var route = RouterProperties.defaults().routes().get(Tier.TIER0);
        for (String key : new String[] {null, "", "  \n"}) {
            var factory = new OpenAiModelFactory(new RouterProperties.OpenAi(key, 1, Duration.ofSeconds(5)));
            assertThatThrownBy(() -> factory.chatOptions(route)).isInstanceOf(RequestNotSentException.class);
        }
    }

    /**
     * Spring AI's OpenAI setup reads OPENAI_BASE_URL / AZURE_OPENAI_BASE_URL when no base URL is set.
     * A child JVM with those variables pointing at a local server, and a local proxy standing in for the
     * network, must show the call going to api.openai.com (via the proxy) and nothing reaching the
     * server named in the environment.
     */
    @Test
    void baseUrlEnvironmentVariablesAreNeverUsed() throws Exception {
        var envServerHits = new AtomicInteger();
        var proxyRequestLines = new CopyOnWriteArrayList<String>();
        try (var envServer = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
                var proxy = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> acceptAll(envServer, socket -> envServerHits.incrementAndGet()));
            Thread.ofVirtual().start(() -> acceptAll(proxy, socket -> refuseTunnel(socket, proxyRequestLines)));

            String java =
                    Path.of(System.getProperty("java.home"), "bin", "java").toString();
            var command = new java.util.ArrayList<>(List.of(
                    java,
                    "-Dhttp.proxyHost=127.0.0.1",
                    "-Dhttp.proxyPort=" + proxy.getLocalPort(),
                    "-Dhttps.proxyHost=127.0.0.1",
                    "-Dhttps.proxyPort=" + proxy.getLocalPort(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    EgressProbeMain.class.getName(),
                    KEY));
            var builder = new ProcessBuilder(command).redirectErrorStream(true);
            String envUrl = "http://127.0.0.1:" + envServer.getLocalPort();
            builder.environment().put("OPENAI_BASE_URL", envUrl);
            builder.environment().put("AZURE_OPENAI_BASE_URL", envUrl);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(output).contains("probe finished").doesNotContain(KEY);
            assertThat(envServerHits).hasValue(0);
            assertThat(proxyRequestLines).anyMatch(line -> line.startsWith("CONNECT api.openai.com:443"));
        }
    }

    private static void acceptAll(ServerSocket server, java.util.function.Consumer<Socket> handler) {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                handler.accept(socket);
            } catch (IOException closed) {
                return;
            }
        }
    }

    private static void refuseTunnel(Socket socket, List<String> requestLines) {
        try {
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String line = reader.readLine();
            if (line != null) {
                requestLines.add(line);
            }
            socket.getOutputStream()
                    .write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
        } catch (IOException ignored) {
            // the client hung up
        }
    }

    @Test
    void aRejectedKeyNeverAppearsInExceptionsOrOutput(CapturedOutput output) throws Exception {
        var seenAuthorization = new CopyOnWriteArrayList<String>();
        HttpServer stub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        stub.createContext("/", exchange -> {
            seenAuthorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = ("{\"error\":{\"message\":\"Incorrect API key provided: sk-pla*****7890.\","
                            + "\"type\":\"invalid_request_error\",\"code\":\"invalid_api_key\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        try {
            String baseUrl = "http://127.0.0.1:" + stub.getAddress().getPort() + "/v1";
            RouterProperties base = RouterProperties.defaults();
            var props = new RouterProperties(
                    base.routes(),
                    base.embedding(),
                    base.dailyCapUsdMicros(),
                    base.prices(),
                    credentials(KEY + "\n", 0),
                    null);
            var router = new DefaultModelRouter(
                    props,
                    new OpenAiModelFactory(props.openai(), baseUrl),
                    new InMemoryCostGuard(1_000_000, java.time.Clock.systemUTC()),
                    RouterMetrics.NOOP);

            Throwable chatFailure = catchThrowable(() -> router.chatClient(Tier.TIER0, DataClass.PUBLIC)
                    .prompt()
                    .user("hello")
                    .call()
                    .content());
            Throwable embedFailure =
                    catchThrowable(() -> router.embeddingModel(DataClass.PUBLIC).embed(List.of("hello")));

            assertThat(seenAuthorization).isNotEmpty().allMatch(("Bearer " + KEY)::equals);
            for (Throwable failure : List.of(chatFailure, embedFailure)) {
                assertThat(failure).isNotNull();
                for (Throwable t = failure; t != null; t = t.getCause()) {
                    assertThat(String.valueOf(t.getMessage())).doesNotContain(KEY);
                    assertThat(t.toString()).doesNotContain(KEY);
                }
            }
            assertThat(output.getAll()).doesNotContain(KEY);
            assertThat(props.toString()).doesNotContain(KEY);
        } finally {
            stub.stop(0);
        }
    }

    /** ChatClient seeds requests with the model's default options; they must be OpenAI options (was a ClassCastException). */
    @Test
    void aChatClientCallCompletesAgainstAnOpenAiCompatibleServer() throws Exception {
        HttpServer stub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        stub.createContext("/", exchange -> {
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
        try {
            String baseUrl = "http://127.0.0.1:" + stub.getAddress().getPort() + "/v1";
            RouterProperties base = RouterProperties.defaults();
            var props = new RouterProperties(
                    base.routes(),
                    base.embedding(),
                    base.dailyCapUsdMicros(),
                    base.prices(),
                    credentials(KEY, 0),
                    null);
            var router = new DefaultModelRouter(
                    props,
                    new OpenAiModelFactory(props.openai(), baseUrl),
                    new InMemoryCostGuard(1_000_000, java.time.Clock.systemUTC()),
                    RouterMetrics.NOOP);

            String answer = router.chatClient(Tier.TIER0, DataClass.PUBLIC)
                    .prompt()
                    .user("ping")
                    .call()
                    .content();

            assertThat(answer).isEqualTo("pong");
        } finally {
            stub.stop(0);
        }
    }

    private static Throwable catchThrowable(Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            return t;
        }
        throw new AssertionError("expected the call to fail");
    }

    @Test
    void fakeEmbeddingFixtureReportsNoModelSoItIsPricedAtTheRoutePrice() {
        var response = new FakeEmbeddingModel(8)
                .call(new org.springframework.ai.embedding.EmbeddingRequest(List.of("x"), null));
        assertThat(response.getMetadata().getModel()).isEmpty();
    }
}
