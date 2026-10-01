package io.github.orhanyarkin.saiman.sellerapi.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.sellerapi.testsupport.SettlementTestBase;
import io.github.orhanyarkin.x402.core.X402Headers;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * {@code GET /internal/credit-notes/{paymentKey}} (ADR-0021, the ledger's corroboration read). Raw sockets, because
 * the JDK HTTP client refuses to set a {@code Host} header and the guard is all about that header.
 */
class CreditNoteLookupEndpointTests extends SettlementTestBase {

    private static final String SELLER_HOST = "seller-api:8081";

    private final SplittableRandom random = new SplittableRandom();

    @LocalServerPort
    private int port;

    @Test
    void aRecordedCreditNoteIsReturnedToTheComposeHostWithoutPayment() throws IOException {
        String key = randomKey();
        String tx = "0x" + "AB".repeat(32);
        insert(key, tx, 20_000);

        Response response = get("/internal/credit-notes/" + key, SELLER_HOST);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("cache-control")).contains("no-store");
        assertThat(response.headers()).doesNotContain(X402Headers.PAYMENT_REQUIRED.toLowerCase(Locale.ROOT));
        assertThat(response.body())
                .contains("\"paymentKey\":\"" + key + "\"")
                .contains("\"txHash\":\"" + tx + "\"")
                .contains("\"amountAtomic\":20000")
                .contains("\"httpStatus\":503")
                .contains("\"reasonCode\":\"handler_server_error\"")
                .contains("\"createdAt\":")
                .doesNotContain("payer")
                .doesNotContain("payTo");
        assertThat(get("/internal/credit-notes/" + key, "seller-api").status()).isEqualTo(200);
        assertThat(FACILITATOR.verifyCallCount()).isZero();
        assertThat(FACILITATOR.settleCallCount()).isZero();
    }

    @Test
    void anUnknownKeyIsATypedNotFoundProblem() throws IOException {
        Response response = get("/internal/credit-notes/" + randomKey(), SELLER_HOST);

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.header("content-type")).startsWith("application/problem+json");
        assertThat(response.body()).contains("\"type\":\"urn:saiman:seller-api:credit-note-not-found\"");
    }

    @Test
    void aMalformedKeyIsABadRequestThatEchoesNothing() throws IOException {
        String good = randomKey();
        for (String bad : new String[] {
            good.toUpperCase(Locale.ROOT),
            good.replace("0x036cbd53842c5426634e7929541ec2318f3dcf7e", "0x" + "11".repeat(20)),
            good.replace("eip155:84532", "eip155:8453"),
            good + "0",
            "eip155:84532",
            "x"
        }) {
            Response response = get("/internal/credit-notes/" + bad, SELLER_HOST);
            assertThat(response.status()).as(bad).isEqualTo(400);
            assertThat(response.body()).as(bad).doesNotContain(bad);
        }
    }

    @Test
    void anyOtherHostIsRefusedEvenForAnExistingRowAndWhateverThePathForm() throws IOException {
        String key = randomKey();
        String tx = "0x" + "cd".repeat(32);
        insert(key, tx, 10_000);

        for (String host : new String[] {
            "localhost:" + port,
            "localhost",
            "127.0.0.1:" + port,
            "evil.example",
            "seller-api:8081.evil.example",
            "seller-api:9999",
            "xseller-api"
        }) {
            Response response = get("/internal/credit-notes/" + key, host);
            assertThat(response.status()).as(host).isEqualTo(400);
            assertThat(response.body()).as(host).doesNotContain(tx).doesNotContain(key);
        }
        for (String path : new String[] {
            "/internal;x=1/credit-notes/" + key,
            "/%69nternal/credit-notes/" + key,
            "/internal/credit-notes/" + key + ";x=1"
        }) {
            Response response = get(path, "localhost:" + port);
            assertThat(response.status()).as(path).isNotEqualTo(200);
            assertThat(response.body()).as(path).doesNotContain(tx);
        }
    }

    @Test
    void thereIsNoListing() throws IOException {
        insert(randomKey(), "0x" + "ef".repeat(32), 10_000);

        assertThat(get("/internal/credit-notes", SELLER_HOST).status()).isEqualTo(404);
        assertThat(get("/internal/credit-notes/", SELLER_HOST).status()).isEqualTo(404);
    }

    // --- helpers ---

    private String randomKey() {
        return "eip155:84532:0x036cbd53842c5426634e7929541ec2318f3dcf7e:0x" + hex(20) + ":0x" + hex(32);
    }

    private String hex(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    private void insert(String key, String tx, long amount) {
        jdbc.sql("""
                        INSERT INTO credit_note
                            (payment_key, tx_hash, amount_atomic, pay_to, payer, http_status, reason_code)
                        VALUES (:key, :tx, :amount, :payTo, :payer, 503, 'handler_server_error')
                        """)
                .param("key", key)
                .param("tx", tx)
                .param("amount", amount)
                .param("payTo", PAY_TO)
                .param("payer", key.substring(key.lastIndexOf(":0x") - 42, key.lastIndexOf(":0x")))
                .update();
    }

    private record Response(int status, String headers, String body) {
        String header(String name) {
            int start = headers.indexOf("\r\n" + name + ":");
            if (start < 0) {
                return "";
            }
            int end = headers.indexOf("\r\n", start + 2);
            return headers.substring(start + name.length() + 3, end < 0 ? headers.length() : end)
                    .trim();
        }
    }

    private Response get(String path, String host) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nAccept: application/json\r\n"
                            + "Connection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            String raw = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            int end = raw.indexOf("\r\n\r\n");
            String head = end < 0 ? raw : raw.substring(0, end);
            String body = end < 0 ? "" : raw.substring(end + 4);
            int status = Integer.parseInt(head.substring(9, 12));
            return new Response(status, head.toLowerCase(Locale.ROOT), body);
        }
    }
}
