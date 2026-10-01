package io.github.orhanyarkin.saiman.evmrpc;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A local JSON-RPC endpoint whose answers a test scripts per request. Loopback only. */
final class StubRpcServer implements AutoCloseable {

    /** What to send back. */
    record Reply(int status, String body, String location) {
        static Reply json(String body) {
            return new Reply(200, body, null);
        }

        static Reply result(String resultJson) {
            return json("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + resultJson + "}");
        }

        static Reply status(int status) {
            return new Reply(status, "{}", null);
        }
    }

    @FunctionalInterface
    interface Handler {
        Reply handle(String method, JsonNode params, int callNumber);
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final List<String> methods = Collections.synchronizedList(new ArrayList<>());
    private final List<JsonNode> allParams = Collections.synchronizedList(new ArrayList<>());
    private volatile Handler handler;
    private int calls;

    StubRpcServer(Handler handler) {
        this.handler = handler;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            byte[] request = exchange.getRequestBody().readAllBytes();
            JsonNode node = JSON.readTree(request);
            String method = node.get("method").asString();
            methods.add(method);
            allParams.add(node.get("params"));
            int number;
            synchronized (this) {
                number = ++calls;
            }
            Reply reply = this.handler.handle(method, node.get("params"), number);
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (reply.location() != null) {
                exchange.getResponseHeaders().add("Location", reply.location());
            }
            exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<String> methods() {
        return List.copyOf(methods);
    }

    List<JsonNode> params() {
        return List.copyOf(allParams);
    }

    synchronized int calls() {
        return calls;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
