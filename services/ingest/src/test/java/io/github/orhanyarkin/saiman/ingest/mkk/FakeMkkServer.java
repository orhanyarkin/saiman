package io.github.orhanyarkin.saiman.ingest.mkk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stand-in for the MKK KAP API test environment (real response shapes, synthetic content). It
 * emulates the windowed {@code /disclosures} listing, checks the Basic credential, and can queue
 * one-off failures per path. Requests are recorded without the credential.
 */
public final class FakeMkkServer implements AutoCloseable {

    /** A canned reply. */
    public record Reply(int status, String body, Map<String, String> headers) {
        public static Reply status(int status) {
            return new Reply(status, "{}", Map.of());
        }

        public static Reply retryAfter(int status, int seconds) {
            return new Reply(status, "{}", Map.of("Retry-After", Integer.toString(seconds)));
        }
    }

    private record Listed(long companyId, long index, String cls, String type, String title) {}

    private static final Pattern QUERY_PARAM = Pattern.compile("(\\w+)=(\\d+)");

    private final HttpServer server;
    private final String expectedCredentials;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<String> userAgents = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Deque<Reply>> oneOff = new HashMap<>();
    private final Map<String, Reply> permanent = new HashMap<>();
    private final Map<Long, String> details = new HashMap<>();
    private final List<Listed> listed = new ArrayList<>();
    private volatile String membersJson = "[]";
    private volatile String blockedJson = "[]";
    private volatile long lastIndex = 0;
    private volatile long window = 3000;

    private FakeMkkServer(String expectedCredentials) throws IOException {
        this.expectedCredentials = expectedCredentials;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    public static FakeMkkServer start(String expectedCredentials) {
        try {
            return new FakeMkkServer(expectedCredentials);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/vyk";
    }

    public synchronized void reset() {
        requests.clear();
        userAgents.clear();
        oneOff.clear();
        permanent.clear();
        details.clear();
        listed.clear();
        membersJson = "[]";
        blockedJson = "[]";
        lastIndex = 0;
        window = 3000;
    }

    public void members(String json) {
        this.membersJson = json;
    }

    public void blocked(String json) {
        this.blockedJson = json;
    }

    public void lastIndex(long value) {
        this.lastIndex = value;
    }

    public void window(long value) {
        this.window = value;
    }

    public synchronized void list(long companyId, long index, String cls, String type, String title) {
        listed.add(new Listed(companyId, index, cls, type, title));
    }

    public synchronized void detail(long index, String json) {
        details.put(index, json);
    }

    /** The next request for {@code path} gets this reply (queued in order), then normal behaviour resumes. */
    public synchronized void enqueue(String path, Reply reply) {
        oneOff.computeIfAbsent(path, p -> new ArrayDeque<>()).add(reply);
    }

    public synchronized void always(String path, Reply reply) {
        permanent.put(path, reply);
    }

    public synchronized void clearAlways(String path) {
        permanent.remove(path);
    }

    /** {@code "GET /path?query"} lines, in arrival order. */
    public List<String> requests() {
        synchronized (requests) {
            return new ArrayList<>(requests);
        }
    }

    public long countRequests(String pathPrefix) {
        return requests().stream().filter(r -> r.contains(pathPrefix)).count();
    }

    public List<String> userAgents() {
        synchronized (userAgents) {
            return new ArrayList<>(userAgents);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath().replaceFirst("^/api/vyk", "");
            String query = exchange.getRequestURI().getRawQuery();
            requests.add(exchange.getRequestMethod() + " " + path + (query == null ? "" : "?" + query));
            userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (!("Basic " + expectedCredentials).equals(auth)) {
                send(exchange, Reply.status(401));
                return;
            }
            Reply scripted = scripted(path);
            if (scripted != null) {
                send(exchange, scripted);
                return;
            }
            send(exchange, new Reply(200, route(path, query), Map.of()));
        } catch (RuntimeException e) {
            send(exchange, Reply.status(500));
        }
    }

    private synchronized Reply scripted(String path) {
        Deque<Reply> queue = oneOff.get(path);
        if (queue != null && !queue.isEmpty()) {
            return queue.poll();
        }
        return permanent.get(path);
    }

    private synchronized String route(String path, String query) {
        if (path.equals("/lastDisclosureIndex")) {
            return "{\"lastDisclosureIndex\":\"" + lastIndex + "\"}";
        }
        if (path.equals("/members")) {
            return membersJson;
        }
        if (path.equals("/blockedDisclosures")) {
            return blockedJson;
        }
        if (path.equals("/disclosures")) {
            Map<String, Long> params = new HashMap<>();
            Matcher m = QUERY_PARAM.matcher(query == null ? "" : query);
            while (m.find()) {
                params.put(m.group(1), Long.parseLong(m.group(2)));
            }
            long from = params.getOrDefault("disclosureIndex", 0L);
            long company = params.getOrDefault("companyId", 0L);
            StringBuilder out = new StringBuilder("[");
            for (Listed l : listed) {
                if (l.companyId == company && l.index >= from && l.index < from + window) {
                    if (out.length() > 1) {
                        out.append(',');
                    }
                    out.append("{\"disclosureIndex\":%d,\"disclosureType\":\"%s\",\"disclosureClass\":\"%s\","
                                    .formatted(l.index, l.type, l.cls))
                            .append(
                                    "\"subReportIds\":[],\"title\":\"%s\",\"companyId\":%d,\"acceptedDataFileTypes\":[\"html\"]}"
                                            .formatted(l.title, l.companyId));
                }
            }
            return out.append(']').toString();
        }
        Matcher detail = Pattern.compile("/disclosureDetail/(\\d+)").matcher(path);
        if (detail.matches()) {
            String json = details.get(Long.parseLong(detail.group(1)));
            if (json == null) {
                throw new IllegalStateException("no such disclosure");
            }
            return json;
        }
        throw new IllegalStateException("unknown path");
    }

    private static void send(HttpExchange exchange, Reply reply) throws IOException {
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        exchange.sendResponseHeaders(reply.status(), body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
