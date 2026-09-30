package io.github.orhanyarkin.saiman.modelrouter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A local OpenAI-compatible server for wire tests: records requests, answers per request by a function. */
final class StubOpenAiServer implements AutoCloseable {

    /** What the stub saw: the requested model, whether it streams, and the raw body. */
    record Request(String model, boolean stream, String body) {}

    /** The stub's answer: HTTP status, JSON (or SSE) body, and an artificial delay. */
    record Reply(int status, String body, long delayMillis) {
        static Reply ok(String body) {
            return new Reply(200, body, 0);
        }

        static Reply status(int status) {
            return new Reply(status, "{\"error\":{\"message\":\"stub says " + status + "\",\"type\":\"stub\"}}", 0);
        }
    }

    private static final Pattern MODEL = Pattern.compile("\"model\"\\s*:\\s*\"([^\"]+)\"");

    final List<Request> requests = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    StubOpenAiServer(Function<Request, Reply> handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = MODEL.matcher(body);
            var request = new Request(m.find() ? m.group(1) : "?", body.contains("\"stream\":true"), body);
            requests.add(request);
            Reply reply = handler.apply(request);
            try {
                if (reply.delayMillis() > 0) {
                    Thread.sleep(reply.delayMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, reply, request.stream());
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    long hits(String model) {
        return requests.stream().filter(r -> r.model().equals(model)).count();
    }

    private static void respond(HttpExchange exchange, Reply reply, boolean stream) throws IOException {
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        boolean sse = stream && reply.status() == 200;
        exchange.getResponseHeaders().add("Content-Type", sse ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---- canned bodies ----

    static String completion(String model, String text, int in, int out) {
        return "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"" + model + "\","
                + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"" + text + "\"}}],"
                + "\"usage\":{\"prompt_tokens\":" + in + ",\"completion_tokens\":" + out + ",\"total_tokens\":"
                + (in + out) + "}}";
    }

    static String toolCall(String model, String tool, String argumentsJson, int in, int out) {
        return "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"" + model + "\","
                + "\"choices\":[{\"index\":0,\"finish_reason\":\"tool_calls\","
                + "\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_1\","
                + "\"type\":\"function\",\"function\":{\"name\":\"" + tool + "\",\"arguments\":\""
                + argumentsJson.replace("\"", "\\\"") + "\"}}]}}],"
                + "\"usage\":{\"prompt_tokens\":" + in + ",\"completion_tokens\":" + out + ",\"total_tokens\":"
                + (in + out) + "}}";
    }

    static String streamed(String model, String text, int in, int out) {
        String head = "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"" + model
                + "\",\"choices\":";
        return head + "[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"" + text
                + "\"},\"finish_reason\":null}]}\n\n" + head
                + "[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + head + "[],\"usage\":{\"prompt_tokens\":" + in + ",\"completion_tokens\":" + out
                + ",\"total_tokens\":" + (in + out) + "}}\n\ndata: [DONE]\n\n";
    }
}
