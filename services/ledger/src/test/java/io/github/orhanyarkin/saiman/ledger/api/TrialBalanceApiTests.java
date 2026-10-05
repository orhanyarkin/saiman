package io.github.orhanyarkin.saiman.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.apisecurity.testfixtures.TestTokens;
import io.github.orhanyarkin.saiman.ledger.LedgerIntegrationTest;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentFact;
import io.github.orhanyarkin.saiman.ledger.payment.PaymentLedgerService;
import io.github.orhanyarkin.saiman.ledger.payment.TestPayment;
import io.github.orhanyarkin.saiman.shared.payments.PaymentTopics;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code GET /api/v1/ledger/trial-balance} and the request guard on a real Tomcat. Guard cases write the request
 * bytes over a socket, because HTTP clients normalise paths and refuse to set a foreign {@code Host}.
 */
@LedgerIntegrationTest
class TrialBalanceApiTests {

    private static final String PATH = "/api/v1/ledger/trial-balance";

    @Autowired
    private RestTestClient client;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private PaymentLedgerService ledger;

    @LocalServerPort
    private int port;

    @Test
    void trialBalanceIsABareArrayOfIntegerRows() {
        TestPayment payment = TestPayment.random(new SplittableRandom(), 20_000);
        ledger.record(PaymentFact.of(payment.buyerSettled()), PaymentTopics.SETTLED);
        String expense = "buyer:" + payment.authorization().payer().toLowerCase(Locale.ROOT) + ":expense:data";

        List<Map<String, Object>> rows = client.get()
                .uri(PATH)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .returnResult()
                .getResponseBody();

        assertThat(rows).isNotEmpty();
        assertThat(rows.getFirst())
                .containsOnlyKeys("account", "book", "type", "asset", "decimals", "debit", "credit", "balance");
        assertThat(rows)
                .filteredOn(row -> expense.equals(row.get("account")))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("debit")).isEqualTo(20_000);
                    assertThat(row.get("credit")).isEqualTo(0);
                    assertThat(row.get("balance")).isEqualTo(20_000);
                    assertThat(row.get("asset")).isEqualTo("USDC");
                    assertThat(row.get("decimals")).isEqualTo(6);
                });
        long sum = rows.stream()
                .filter(row -> "USDC".equals(row.get("asset")))
                .mapToLong(row -> ((Number) row.get("balance")).longValue())
                .sum();
        assertThat(sum).isZero();
    }

    /**
     * Each posting is at most 2^53-1, but 1100 of them on one account exceed a {@code long}: the totals are summed
     * as numeric and returned as JSON integers, never a 500. A separate asset keeps the USDC rows untouched.
     */
    @Test
    void trialBalanceSurvivesSumsBeyondLong() {
        String asset = "BIG" + Math.abs(new SplittableRandom().nextInt(1_000_000));
        int entries = 1100;
        transactions.executeWithoutResult(tx -> {
            jdbc.sql("""
                            INSERT INTO account (code, book, type, asset, decimals)
                            VALUES ('platform:bigsum:a', 'PLATFORM', 'ASSET', :asset, 0),
                                   ('platform:bigsum:b', 'PLATFORM', 'SUSPENSE', :asset, 0)
                            """).param("asset", asset).update();
            jdbc.sql("""
                            WITH e AS (
                                INSERT INTO journal_entry (id, book, kind, description, effective_at)
                                SELECT gen_random_uuid(), 'PLATFORM', 'ADJUSTMENT', 'sum beyond long', now()
                                  FROM generate_series(1, :entries)
                                RETURNING id)
                            INSERT INTO posting (entry_id, account_code, side, amount_atomic, asset, decimals)
                            SELECT e.id, a.code, a.side, 9007199254740991, :asset, 0
                              FROM e CROSS JOIN (VALUES ('platform:bigsum:a', 'DEBIT'),
                                                        ('platform:bigsum:b', 'CREDIT')) AS a (code, side)
                            """).param("entries", entries).param("asset", asset).update();
        });
        BigInteger expected = BigInteger.valueOf(9_007_199_254_740_991L).multiply(BigInteger.valueOf(entries));
        assertThat(expected).isGreaterThan(BigInteger.valueOf(Long.MAX_VALUE));

        List<Map<String, Object>> rows = client.get()
                .uri(PATH)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .returnResult()
                .getResponseBody();

        assertThat(rows)
                .filteredOn(row -> asset.equals(row.get("asset")))
                .hasSize(2)
                .anySatisfy(row -> {
                    assertThat(row.get("account")).isEqualTo("platform:bigsum:a");
                    assertThat(new BigInteger(row.get("debit").toString())).isEqualTo(expected);
                    assertThat(new BigInteger(row.get("balance").toString())).isEqualTo(expected);
                })
                .anySatisfy(row -> assertThat(new BigInteger(row.get("balance").toString()))
                        .isEqualTo(expected.negate()));
    }

    @Test
    void foreignHostIsRefused() throws IOException {
        assertThat(status("GET " + PATH, "evil.example", "")).isEqualTo(400);
        assertThat(status("GET " + PATH, "localhost.evil.example", "")).isEqualTo(400);
        assertThat(status("GET " + PATH, "localhost:" + port, "")).isEqualTo(200);
        assertThat(status("GET " + PATH, "ledger:8082", "")).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api;x=1/v1/ledger/trial-balance",
                "/%61pi/v1/ledger/trial-balance",
                "//api/v1/ledger/trial-balance",
                "/api/v1/./ledger/trial-balance"
            })
    void nonCanonicalPathsAreRefused(String path) throws IOException {
        assertThat(status("GET " + path, "localhost", "")).isEqualTo(400);
    }

    @Test
    void stateChangesNeedJsonAndTheCsrfHeader() throws IOException {
        assertThat(status("POST " + PATH, "localhost", "Content-Type: application/json\r\n"))
                .isEqualTo(403);
        assertThat(status("POST " + PATH, "localhost", LedgerApiGuardFilter.CSRF_HEADER + ": 1\r\n"))
                .isEqualTo(403);
        // Past the guard, the security chain denies every write to the read-only trial balance (ADR-0023; was 405).
        assertThat(status(
                        "POST " + PATH,
                        "localhost",
                        "Content-Type: application/json\r\n" + LedgerApiGuardFilter.CSRF_HEADER + ": 1\r\n"))
                .isEqualTo(403);
    }

    @Test
    void oversizedOrChunkedBodiesAreRefused() throws IOException {
        String headers = "Content-Type: application/json\r\n" + LedgerApiGuardFilter.CSRF_HEADER + ": 1\r\n";
        String big = "{}" + " ".repeat(LedgerApiGuardFilter.MAX_BODY_BYTES);
        assertThat(raw("POST " + PATH + " HTTP/1.1\r\nHost: localhost\r\n" + AUTHORIZATION + headers
                        + "Content-Length: " + big.length() + "\r\nConnection: close\r\n\r\n" + big))
                .isEqualTo(413);
        assertThat(raw("POST " + PATH + " HTTP/1.1\r\nHost: localhost\r\n" + AUTHORIZATION + headers
                        + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n2\r\n{}\r\n0\r\n\r\n"))
                .isEqualTo(413);
    }

    /** The guard tests are about the guard, so they authenticate: auth-ordering cases are in LedgerApiSecurityTests. */
    private static final String AUTHORIZATION = "Authorization: " + TestTokens.bearer(TestTokens.OPERATOR) + "\r\n";

    private int status(String requestLine, String host, String extraHeaders) throws IOException {
        String body = requestLine.startsWith("POST") ? "{}" : "";
        return raw(requestLine + " HTTP/1.1\r\nHost: " + host + "\r\n" + AUTHORIZATION + extraHeaders
                + "Content-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body);
    }

    /** Writes the request bytes as given and returns the response status code. */
    private int raw(String request) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String statusLine = in.readLine();
            assertThat(statusLine).as("status line").isNotNull().startsWith("HTTP/1.1 ");
            return Integer.parseInt(statusLine.substring(9, 12));
        }
    }
}
