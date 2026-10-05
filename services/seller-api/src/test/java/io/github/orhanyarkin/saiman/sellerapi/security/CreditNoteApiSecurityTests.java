package io.github.orhanyarkin.saiman.sellerapi.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.orhanyarkin.saiman.apisecurity.testfixtures.TestTokens;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.RawHttp;
import io.github.orhanyarkin.saiman.sellerapi.testsupport.SettlementTestBase;
import io.github.orhanyarkin.x402.core.X402Headers;
import io.github.orhanyarkin.x402.testing.PaymentPayloads;
import java.io.IOException;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * ADR-0023 on seller-api: only {@code /internal/**} is behind Spring Security, and each internal route accepts exactly
 * one service caller. The paid {@code /v1/**} path never passes through the security chain.
 */
@ExtendWith(OutputCaptureExtension.class)
class CreditNoteApiSecurityTests extends SettlementTestBase {

    private static final String SELLER_HOST = "seller-api:8081";
    private static final String KEY =
            "eip155:84532:0x036cbd53842c5426634e7929541ec2318f3dcf7e:0x" + "11".repeat(20) + ":0x" + "22".repeat(32);

    @LocalServerPort
    private int port;

    @Test
    void onlyTheLedgerTokenReadsCreditNotes(CapturedOutput output) throws IOException {
        String path = "/internal/credit-notes/" + KEY;

        RawHttp.Response none = get(path, null);
        assertThat(none.status()).isEqualTo(401);
        assertThat(none.header("www-authenticate")).startsWith("bearer"); // RawHttp lower-cases the header block
        assertThat(none.header("content-type")).startsWith("application/problem+json");

        assertThat(get(path, TestTokens.UNKNOWN).status()).isEqualTo(401);
        assertThat(get(path, "not a token").status()).isEqualTo(401);
        assertThat(get(path, TestTokens.SERVICE_EVALS).status()).isEqualTo(403);
        assertThat(get(path, TestTokens.READER).status()).isEqualTo(403);
        assertThat(get(path, TestTokens.OPERATOR).status()).isEqualTo(403);

        // Authenticated as the ledger: the handler runs (no such row here, so its typed 404).
        RawHttp.Response ledger = get(path, TestTokens.SERVICE_LEDGER);
        assertThat(ledger.status()).isEqualTo(404);
        assertThat(ledger.body()).contains("urn:saiman:seller-api:credit-note-not-found");

        assertThat(output.getAll())
                .doesNotContain(TestTokens.SERVICE_LEDGER)
                .doesNotContain(TestTokens.SERVICE_EVALS)
                .doesNotContain(TestTokens.READER)
                .doesNotContain(TestTokens.OPERATOR)
                .doesNotContain(TestTokens.UNKNOWN);
    }

    @Test
    void aTokenInTheQueryStringIsIgnored() throws IOException {
        RawHttp.Response response =
                get("/internal/credit-notes/" + KEY + "?access_token=" + TestTokens.SERVICE_LEDGER, null);

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void anyOtherInternalPathIsDeniedEvenToAKnownServiceCaller() throws IOException {
        assertThat(get("/internal/anything", null).status()).isEqualTo(401);
        assertThat(get("/internal/anything", TestTokens.SERVICE_LEDGER).status())
                .isEqualTo(403);
        assertThat(get("/internal/anything", TestTokens.SERVICE_EVALS).status()).isEqualTo(403);
    }

    @Test
    void anEncodedPathFormDoesNotSkipAuthentication() throws IOException {
        for (String path : new String[] {
            "/%69nternal/credit-notes/" + KEY, "/internal/%63redit-notes/" + KEY, "//internal/credit-notes/" + KEY
        }) {
            RawHttp.Response response = get(path, null);
            assertThat(response.status()).as(path).isIn(400, 401);
        }
    }

    @Test
    void eachInternalRouteAcceptsOnlyItsMethod() throws IOException {
        Map<String, String> ledger = Map.of("Authorization", TestTokens.bearer(TestTokens.SERVICE_LEDGER));
        Map<String, String> evals = Map.of("Authorization", TestTokens.bearer(TestTokens.SERVICE_EVALS));
        for (String method : new String[] {"HEAD", "OPTIONS", "PUT", "POST", "DELETE"}) {
            assertThat(RawHttp.exchange(port, method, "/internal/credit-notes/" + KEY, SELLER_HOST, ledger, null)
                            .status())
                    .as(method)
                    .isEqualTo(403);
        }
        for (String method : new String[] {"HEAD", "OPTIONS", "PUT", "GET", "DELETE"}) {
            assertThat(RawHttp.exchange(port, method, "/internal/v1/eval/questions", SELLER_HOST, evals, null)
                            .status())
                    .as(method)
                    .isEqualTo(403);
        }
    }

    @Test
    void theFirewallRefusesOddPaidPathFormsBeforeX402() throws IOException {
        for (String path : new String[] {"/v1/disclosures/THYAO/summary;x=1", "/v1//disclosures/THYAO/summary"}) {
            RawHttp.Response response = RawHttp.exchange(
                    port,
                    "GET",
                    path,
                    "localhost:" + port,
                    Map.of(X402Headers.PAYMENT_SIGNATURE, PaymentPayloads.header(codec, newPayload())),
                    null);
            assertThat(response.status()).as(path).isEqualTo(400);
        }
        assertThat(FACILITATOR.verifyCallCount()).isZero();
        assertThat(FACILITATOR.settleCallCount()).isZero();
        assertThat(settlementRows()).isZero();
    }

    @Test
    void thePaidPathMatchesNoSecurityChain() throws IOException {
        // A paid request with a (wrong-route) bearer token is still answered by x402 alone: 200, settled, and none of
        // the response headers Spring Security's chain writes.
        RawHttp.Response paid = RawHttp.exchange(
                port,
                "GET",
                "/v1/disclosures/THYAO/summary",
                "localhost:" + port,
                Map.of(
                        X402Headers.PAYMENT_SIGNATURE,
                        PaymentPayloads.header(codec, newPayload()),
                        "Authorization",
                        TestTokens.bearer(TestTokens.UNKNOWN)),
                null);
        assertThat(paid.status()).isEqualTo(200);
        assertThat(paid.header(X402Headers.PAYMENT_RESPONSE.toLowerCase(java.util.Locale.ROOT)))
                .isNotEmpty();
        assertThat(paid.headers()).doesNotContain("x-content-type-options").doesNotContain("x-frame-options");

        // Unpaid: the x402 challenge, not a 401 from Spring Security.
        RawHttp.Response unpaid =
                RawHttp.exchange(port, "GET", "/v1/disclosures/THYAO/summary", "localhost:" + port, Map.of(), null);
        assertThat(unpaid.status()).isEqualTo(402);
        assertThat(unpaid.header("www-authenticate")).isEmpty();

        // The internal path does go through it.
        assertThat(get("/internal/credit-notes/" + KEY, null).headers()).contains("x-content-type-options");
    }

    private RawHttp.Response get(String path, @Nullable String token) throws IOException {
        Map<String, String> headers = token == null ? Map.of() : Map.of("Authorization", TestTokens.bearer(token));
        return RawHttp.exchange(port, "GET", path, SELLER_HOST, headers, null);
    }
}
