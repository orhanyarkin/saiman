package io.github.orhanyarkin.x402.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@code buy --method=POST --json=...}: the body and content type reach the server, the JSON body
 * is never echoed by the command itself, and bad option combinations are rejected before any
 * request is sent. A plain 200 server is enough: the payment interceptor only acts on a 402 and
 * its re-send of the body is covered by the starter's own tests.
 */
class BuyCommandMethodTest {

    // The well-known "cow" test private key (keccak256("cow")); never a real wallet key.
    private static final String COW_PRIVATE_KEY = "0xc85ef7d79691fe79573b1a7064c19c1a9819ebdbd1faaab1a8ec92344438aaf4";
    private static final String PAY_TO = "0x209693Bc6afc0C5328bA36FaF03C514EF312287C";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ConsoleBuyerApplication.class)
            .withPropertyValues(
                    "x402.client.private-key=" + COW_PRIVATE_KEY,
                    "x402.client.max-amount-per-request=1000000",
                    "x402.client.allowed-pay-to=" + PAY_TO);

    @Test
    void postSendsTheJsonBodyWithAJsonContentType() {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = server(method, contentType, body);
        try {
            runner.run(context -> {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ByteArrayOutputStream err = new ByteArrayOutputStream();
                boolean ok = context.getBean(BuyCommand.class)
                        .run(
                                url(server),
                                "POST",
                                "{\"question\":\"hello\"}",
                                new PrintStream(out, true, StandardCharsets.UTF_8),
                                new PrintStream(err, true, StandardCharsets.UTF_8));
                assertThat(ok).isTrue();
                assertThat(method.get()).isEqualTo("POST");
                assertThat(contentType.get()).startsWith("application/json");
                assertThat(body.get()).isEqualTo("{\"question\":\"hello\"}");
                assertThat(out.toString(StandardCharsets.UTF_8)).contains("status: 200");
                assertThat(out.toString(StandardCharsets.UTF_8)).doesNotContain("question");
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void defaultMethodIsGetWithoutABody() {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = server(method, contentType, body);
        try {
            runner.run(context -> {
                boolean ok = context.getBean(BuyCommand.class)
                        .run(url(server), new PrintStream(new ByteArrayOutputStream()), System.err);
                assertThat(ok).isTrue();
                assertThat(method.get()).isEqualTo("GET");
                assertThat(body.get()).isEmpty();
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void invalidMethodAndJsonWithGetAreRejectedWithoutARequest() {
        runner.run(context -> {
            BuyCommand command = context.getBean(BuyCommand.class);
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);
            PrintStream out = new PrintStream(new ByteArrayOutputStream());

            assertThat(command.run("http://127.0.0.1:1/x", "DELETE", null, out, errStream))
                    .isFalse();
            assertThat(command.run("http://127.0.0.1:1/x", "GET", "{\"a\":1}", out, errStream))
                    .isFalse();
            assertThat(err.toString(StandardCharsets.UTF_8))
                    .contains("--method")
                    .contains("--json");
        });
    }

    @Test
    void jsonFileBodyIsReadAsUtf8AndSentVerbatim(@TempDir Path dir) throws IOException {
        // Apostrophes and Turkish letters are exactly what breaks shell / Gradle --args quoting.
        String json = "{\"question\":\"İstanbul'da şirket ne açıkladı?\"}";
        Path file = dir.resolve("body.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = server(method, contentType, body);
        try {
            runner.run(context -> {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                boolean ok = context.getBean(BuyCommand.class)
                        .run(
                                url(server),
                                "POST",
                                BuyCommand.resolveJsonBody(null, file.toString(), dir),
                                new PrintStream(out, true, StandardCharsets.UTF_8),
                                System.err);
                assertThat(ok).isTrue();
                assertThat(body.get()).isEqualTo(json);
                assertThat(contentType.get()).startsWith("application/json");
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void jsonBodyOptionsAreValidatedWithoutEchoingFileContent(@TempDir Path dir) throws IOException {
        Path big = dir.resolve("big.json");
        Files.writeString(big, "FILE-CONTENT-MARKER".repeat(BuyCommand.MAX_JSON_FILE_BYTES));
        Path badUtf8 = dir.resolve("bad.json");
        Files.write(badUtf8, new byte[] {'{', (byte) 0xC3, (byte) 0x28, '}'});
        Path fine = dir.resolve("fine.json");
        Files.writeString(fine, "{}");

        assertThat(BuyCommand.resolveJsonBody("{\"a\":1}", null)).isEqualTo("{\"a\":1}");
        assertThat(BuyCommand.resolveJsonBody(null, null)).isNull();
        assertThat(BuyCommand.resolveJsonBody(null, fine.toString(), dir)).isEqualTo("{}");

        assertThatThrownBy(() -> BuyCommand.resolveJsonBody("{}", fine.toString(), dir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not both");
        assertThatThrownBy(() -> BuyCommand.resolveJsonBody(
                        null, dir.resolve("missing.json").toString(), dir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("regular file");
        assertThatThrownBy(() -> BuyCommand.resolveJsonBody(null, dir.toString(), dir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("regular file");
        assertThatThrownBy(() -> BuyCommand.resolveJsonBody(null, big.toString(), dir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("16384")
                .hasMessageNotContaining("FILE-CONTENT-MARKER");
        assertThatThrownBy(() -> BuyCommand.resolveJsonBody(null, badUtf8.toString(), dir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UTF-8");
    }

    @Test
    void jsonFileRefusesPathsOutsideTheWorkingDirectoryAndSensitiveNames(@TempDir Path dir, @TempDir Path outside)
            throws IOException {
        Files.writeString(outside.resolve("body.json"), "{}");
        Files.createDirectories(dir.resolve("secrets"));
        Files.writeString(dir.resolve("secrets/body.json"), "{}");
        Files.createDirectories(dir.resolve(".hidden"));
        Files.writeString(dir.resolve(".hidden/body.json"), "{}");
        Files.writeString(dir.resolve(".env.json"), "{}");
        Files.writeString(dir.resolve("buyer.key"), "{}");
        Files.writeString(dir.resolve("cert.PEM"), "{}");
        Files.createSymbolicLink(dir.resolve("link.json"), outside.resolve("body.json"));

        for (String refused : new String[] {
            outside.resolve("body.json").toString(),
            "../" + outside.getFileName() + "/body.json",
            "secrets/body.json",
            ".hidden/body.json",
            ".env.json",
            "buyer.key",
            "cert.PEM",
            "link.json"
        }) {
            assertThatThrownBy(() -> BuyCommand.resolveJsonBody(null, refused, dir))
                    .as(refused)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        Files.writeString(dir.resolve("ok.json"), "{}");
        assertThat(BuyCommand.resolveJsonBody(null, "ok.json", dir)).isEqualTo("{}");
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/echo";
    }

    private static HttpServer server(
            AtomicReference<String> method, AtomicReference<String> contentType, AtomicReference<String> body) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/echo", exchange -> {
                method.set(exchange.getRequestMethod());
                contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
