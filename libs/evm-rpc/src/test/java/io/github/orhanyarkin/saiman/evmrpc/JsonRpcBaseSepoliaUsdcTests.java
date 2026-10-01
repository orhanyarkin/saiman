package io.github.orhanyarkin.saiman.evmrpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.saiman.evmrpc.StubRpcServer.Reply;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class JsonRpcBaseSepoliaUsdcTests {

    private static final String USDC = "0x036CbD53842c5426634e7929541eC2318f3dCF7e";
    private static final String OTHER_TOKEN = "0x1111111111111111111111111111111111111111";
    private static final String PAYER = "0x1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa";
    private static final String PAYEE = "0x2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb";
    private static final String NONCE = "0x" + "ab".repeat(32);
    private static final String TX = "0x" + "cd".repeat(32);
    private static final String CHAIN_OK = "\"0x14a34\"";

    private static String topicAddress(String address) {
        return "\"0x" + "0".repeat(24) + address.substring(2) + "\"";
    }

    private static String word(long value) {
        return "0x" + String.format("%064x", value);
    }

    private static ChainProperties props(String url, int chunk) {
        return new ChainProperties(
                url,
                List.of("sepolia.base.org"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                1000,
                chunk,
                3,
                Duration.ofMillis(5),
                1_000_000);
    }

    private static StubRpcServer stub(StubRpcServer.Handler rest) {
        return new StubRpcServer((method, params, n) ->
                method.equals("eth_chainId") ? Reply.result(CHAIN_OK) : rest.handle(method, params, n));
    }

    private static String receiptJson(String status, String logs) {
        return "{\"status\":\"" + status + "\",\"blockNumber\":\"0x64\",\"transactionHash\":\"" + TX + "\",\"logs\":["
                + logs + "]}";
    }

    private static String transferLog(String contract, long value) {
        return "{\"address\":\"" + contract + "\",\"topics\":[\"" + JsonRpcBaseSepoliaUsdc.TRANSFER_TOPIC + "\","
                + topicAddress(PAYER) + "," + topicAddress(PAYEE) + "],\"data\":\"" + word(value) + "\"}";
    }

    private static String authorizationLog(String contract) {
        return "{\"address\":\"" + contract + "\",\"topics\":[\"" + JsonRpcBaseSepoliaUsdc.AUTHORIZATION_USED_TOPIC
                + "\"," + topicAddress(PAYER) + ",\"" + NONCE + "\"],\"data\":\"0x\",\"transactionHash\":\"" + TX
                + "\"}";
    }

    @Test
    void selectorsAndTopicsAreComputedAndMatchTheKnownValues() {
        assertThat(JsonRpcBaseSepoliaUsdc.AUTHORIZATION_STATE_SELECTOR).isEqualTo("0xe94a0102");
        assertThat(JsonRpcBaseSepoliaUsdc.TRANSFER_TOPIC)
                .isEqualTo("0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef");
        assertThat(JsonRpcBaseSepoliaUsdc.AUTHORIZATION_USED_TOPIC)
                .isEqualTo("0x98de503528ee59b575ef0c0a2576a82497bfc029a5685b209e9ec333479b10a5");
    }

    @Test
    void aDifferentChainFailsStartup() {
        try (StubRpcServer server = new StubRpcServer((m, p, n) -> Reply.result("\"0x1\""))) {
            assertThatThrownBy(() -> new JsonRpcBaseSepoliaUsdc(props(server.url(), 1000)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not Base Sepolia");
        }
    }

    @Test
    void anUnreachableNodeAtStartupDefersTheChainCheckAndStaysClosedUntilItPasses() {
        try (StubRpcServer server = new StubRpcServer((m, p, n) -> {
            if (m.equals("eth_chainId")) {
                return n <= 3 ? Reply.status(503) : Reply.result(CHAIN_OK);
            }
            return Reply.result("{\"number\":\"0x10\",\"timestamp\":\"0x20\"}");
        })) {
            JsonRpcBaseSepoliaUsdc client = new JsonRpcBaseSepoliaUsdc(props(server.url(), 1000));
            // The first block() call re-runs the check (now answered) and then reads the block.
            assertThat(client.block(BlockTag.SAFE)).isEqualTo(new ChainBlock(16, 32));
        }
    }

    @Test
    void readsBlocksForBothTags() {
        try (StubRpcServer server =
                stub((m, p, n) -> Reply.result("{\"number\":\"0x1b4\",\"timestamp\":\"0x6553f100\"}"))) {
            JsonRpcBaseSepoliaUsdc client = new JsonRpcBaseSepoliaUsdc(props(server.url(), 1000));
            assertThat(client.block(BlockTag.LATEST)).isEqualTo(new ChainBlock(436, 0x6553f100L));
            client.block(BlockTag.SAFE);
            assertThat(server.params().stream()
                            .filter(p -> p.size() == 2)
                            .map(p -> p.get(0).asString()))
                    .containsExactly("latest", "safe");
        }
    }

    @Test
    void authorizationStateSendsSelectorPaddedAuthorizerNonceAndBlock() {
        try (StubRpcServer server = stub((m, p, n) -> Reply.result("\"" + word(1) + "\""))) {
            JsonRpcBaseSepoliaUsdc client = new JsonRpcBaseSepoliaUsdc(props(server.url(), 1000));
            assertThat(client.authorizationState(
                            PAYER.toUpperCase(java.util.Locale.ROOT).replace("0X", "0x"), NONCE, 255))
                    .isTrue();
            JsonNode params = server.params().get(server.methods().indexOf("eth_call"));
            assertThat(params.get(0).get("to").asString()).isEqualTo(USDC);
            assertThat(params.get(0).get("data").asString())
                    .isEqualTo("0xe94a0102" + "0".repeat(24) + PAYER.substring(2) + "ab".repeat(32));
            assertThat(params.get(1).asString()).isEqualTo("0xff");
        }
    }

    @Test
    void authorizationStateFalseAndMalformedAnswers() {
        List<String> answers = new ArrayList<>(List.of(word(0), word(2), "0x01"));
        try (StubRpcServer server = stub((m, p, n) -> Reply.result("\"" + answers.removeFirst() + "\""))) {
            JsonRpcBaseSepoliaUsdc client = new JsonRpcBaseSepoliaUsdc(props(server.url(), 1000));
            assertThat(client.authorizationState(PAYER, NONCE, 1)).isFalse();
            assertThatThrownBy(() -> client.authorizationState(PAYER, NONCE, 1))
                    .isInstanceOf(ChainUnavailableException.class);
            assertThatThrownBy(() -> client.authorizationState(PAYER, NONCE, 1))
                    .isInstanceOf(ChainUnavailableException.class);
        }
    }

    @Test
    void receiptKeepsOnlyLogsFromTheUsdcContract() {
        String logs = String.join(
                ",",
                transferLog(OTHER_TOKEN, 999),
                transferLog(USDC.toLowerCase(java.util.Locale.ROOT), 10_000),
                authorizationLog(OTHER_TOKEN),
                authorizationLog(USDC));
        try (StubRpcServer server = stub((m, p, n) -> Reply.result(receiptJson("0x1", logs)))) {
            JsonRpcBaseSepoliaUsdc client = new JsonRpcBaseSepoliaUsdc(props(server.url(), 1000));
            UsdcReceipt receipt = client.receipt(TX).orElseThrow();
            assertThat(receipt.succeeded()).isTrue();
            assertThat(receipt.blockNumber()).isEqualTo(100);
            assertThat(receipt.txHash()).isEqualTo(TX);
            assertThat(receipt.transfers()).containsExactly(new UsdcTransfer(PAYER, PAYEE, 10_000));
            assertThat(receipt.authorizationsUsed()).containsExactly(PAYER + ":" + NONCE);
        }
    }

    @Test
    void receiptReportsAFailedStatusAndAMissingTransaction() {
        List<String> answers = new ArrayList<>(List.of(receiptJson("0x0", ""), "null"));
        try (StubRpcServer server = stub((m, p, n) -> Reply.result(answers.removeFirst()))) {
            JsonRpcBaseSepoliaUsdc client = new JsonRpcBaseSepoliaUsdc(props(server.url(), 1000));
            assertThat(client.receipt(TX).orElseThrow().succeeded()).isFalse();
            assertThat(client.receipt(TX)).isEqualTo(Optional.empty());
        }
    }

    @Test
    void receiptWithAValueAboveLongMaxIsRejected() {
        String huge = "{\"address\":\"" + USDC + "\",\"topics\":[\"" + JsonRpcBaseSepoliaUsdc.TRANSFER_TOPIC + "\","
                + topicAddress(PAYER) + "," + topicAddress(PAYEE) + "],\"data\":\"0x" + "0".repeat(47) + "1"
                + "0".repeat(16)
                + "\"}";
        try (StubRpcServer server = stub((m, p, n) -> Reply.result(receiptJson("0x1", huge)))) {
            JsonRpcBaseSepoliaUsdc client = new JsonRpcBaseSepoliaUsdc(props(server.url(), 1000));
            assertThatThrownBy(() -> client.receipt(TX))
                    .isInstanceOf(ChainUnavailableException.class)
                    .hasMessage("chain RPC response malformed");
        }
    }

    @Test
    void getLogsCoversTheRangeExactlyInBoundedChunks() {
        record Range(long from, long to) {}
        for (long[] range : new long[][] {{1, 2500}, {0, 999}, {5, 5}, {10, 3009}, {7, 1006}}) {
            try (StubRpcServer server = stub((m, p, n) -> Reply.result("[]"))) {
                JsonRpcBaseSepoliaUsdc client = new JsonRpcBaseSepoliaUsdc(props(server.url(), 1000));
                assertThat(client.findAuthorizationTx(PAYER, NONCE, range[0], range[1]))
                        .isEmpty();
                List<Range> chunks = new ArrayList<>();
                for (int i = 0; i < server.methods().size(); i++) {
                    if (server.methods().get(i).equals("eth_getLogs")) {
                        JsonNode filter = server.params().get(i).get(0);
                        chunks.add(new Range(
                                Long.parseLong(
                                        filter.get("fromBlock").asString().substring(2), 16),
                                Long.parseLong(filter.get("toBlock").asString().substring(2), 16)));
                        assertThat(filter.get("address").asString()).isEqualTo(USDC);
                        assertThat(filter.get("topics").get(0).asString())
                                .isEqualTo(JsonRpcBaseSepoliaUsdc.AUTHORIZATION_USED_TOPIC);
                        assertThat(filter.get("topics").get(1).asString())
                                .isEqualTo("0x" + "0".repeat(24) + PAYER.substring(2));
                        assertThat(filter.get("topics").get(2).asString()).isEqualTo(NONCE);
                    }
                }
                long expectedNext = range[0];
                for (Range chunk : chunks) {
                    assertThat(chunk.from()).isEqualTo(expectedNext);
                    assertThat(chunk.to() - chunk.from() + 1).isBetween(1L, 1000L);
                    expectedNext = chunk.to() + 1;
                }
                assertThat(expectedNext).isEqualTo(range[1] + 1);
            }
        }
    }

    @Test
    void getLogsStopsAtTheFirstChunkThatHasTheTransaction() {
        try (StubRpcServer server =
                stub((m, p, n) -> n == 2 ? Reply.result("[]") : Reply.result("[" + authorizationLog(USDC) + "]"))) {
            JsonRpcBaseSepoliaUsdc client = new JsonRpcBaseSepoliaUsdc(props(server.url(), 10));
            assertThat(client.findAuthorizationTx(PAYER, NONCE, 100, 199)).contains(TX);
            assertThat(server.methods().stream().filter("eth_getLogs"::equals).count())
                    .isEqualTo(2);
        }
    }

    @Test
    void anOverWideLogRangeIsRefused() {
        try (StubRpcServer server = stub((m, p, n) -> Reply.result("[]"))) {
            ChainProperties narrow = new ChainProperties(
                    server.url(),
                    List.of("sepolia.base.org"),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    1000,
                    1000,
                    3,
                    Duration.ofMillis(1),
                    5000);
            JsonRpcBaseSepoliaUsdc client = new JsonRpcBaseSepoliaUsdc(narrow);
            assertThatThrownBy(() -> client.findAuthorizationTx(PAYER, NONCE, 0, 5000))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void a429IsRetriedAndThenSucceeds() {
        try (StubRpcServer server = stub(
                (m, p, n) -> n == 2 ? Reply.status(429) : Reply.result("{\"number\":\"0x1\",\"timestamp\":\"0x2\"}"))) {
            JsonRpcBaseSepoliaUsdc client = new JsonRpcBaseSepoliaUsdc(props(server.url(), 1000));
            assertThat(client.block(BlockTag.LATEST)).isEqualTo(new ChainBlock(1, 2));
            assertThat(server.methods().stream()
                            .filter("eth_getBlockByNumber"::equals)
                            .count())
                    .isEqualTo(2);
        }
    }

    @Test
    void persistent5xxExhaustsTheRetriesAndSurfacesAsUnavailable() {
        try (StubRpcServer server = stub((m, p, n) -> Reply.status(503))) {
            JsonRpcBaseSepoliaUsdc client = new JsonRpcBaseSepoliaUsdc(props(server.url(), 1000));
            assertThatThrownBy(() -> client.block(BlockTag.LATEST)).isInstanceOf(ChainUnavailableException.class);
            assertThat(server.methods().stream()
                            .filter("eth_getBlockByNumber"::equals)
                            .count())
                    .isEqualTo(3);
        }
    }

    @Test
    void aJsonRpcErrorIsNotRetriedAndItsTextIsNotEchoed() {
        try (StubRpcServer server = stub((m, p, n) -> Reply.json(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"secret-detail\"}}"))) {
            JsonRpcBaseSepoliaUsdc client = new JsonRpcBaseSepoliaUsdc(props(server.url(), 1000));
            assertThatThrownBy(() -> client.block(BlockTag.SAFE))
                    .isInstanceOf(ChainUnavailableException.class)
                    .hasMessage("chain RPC returned an error");
            assertThat(server.methods().stream()
                            .filter("eth_getBlockByNumber"::equals)
                            .count())
                    .isEqualTo(1);
        }
    }

    @Test
    void anOversizedResponseIsRejected() {
        String padding = "x".repeat(JsonRpcBaseSepoliaUsdc.MAX_RESPONSE_BYTES + 10);
        try (StubRpcServer server = stub((m, p, n) -> Reply.result("\"" + padding + "\""))) {
            JsonRpcBaseSepoliaUsdc client = new JsonRpcBaseSepoliaUsdc(props(server.url(), 1000));
            assertThatThrownBy(() -> client.block(BlockTag.LATEST))
                    .isInstanceOf(ChainUnavailableException.class)
                    .hasMessage("chain RPC response too large");
        }
    }

    @Test
    void malformedJsonAndMissingResultAreUnavailable() {
        List<Reply> replies = new ArrayList<>(
                List.of(Reply.json("not json"), Reply.json("{\"jsonrpc\":\"2.0\",\"id\":1}"), Reply.result("[]")));
        try (StubRpcServer server = stub((m, p, n) -> replies.removeFirst())) {
            JsonRpcBaseSepoliaUsdc client = new JsonRpcBaseSepoliaUsdc(props(server.url(), 1000));
            for (int i = 0; i < 3; i++) {
                assertThatThrownBy(() -> client.block(BlockTag.LATEST)).isInstanceOf(ChainUnavailableException.class);
            }
        }
    }

    @Test
    void redirectsAreNotFollowed() {
        try (StubRpcServer target = new StubRpcServer((m, p, n) -> Reply.result(CHAIN_OK));
                StubRpcServer redirecting = new StubRpcServer((m, p, n) -> new Reply(302, "", target.url() + "/"))) {
            // Startup cannot verify the chain (the redirect is an error answer), so the check is deferred...
            JsonRpcBaseSepoliaUsdc client = new JsonRpcBaseSepoliaUsdc(props(redirecting.url(), 1000));
            // ...and stays closed: the redirect target never receives a request.
            assertThatThrownBy(() -> client.block(BlockTag.LATEST)).isInstanceOf(ChainUnavailableException.class);
            assertThat(target.calls()).isZero();
        }
    }

    @Test
    void malformedArgumentsAreRejectedBeforeAnyRequest() {
        try (StubRpcServer server = stub((m, p, n) -> Reply.result("null"))) {
            JsonRpcBaseSepoliaUsdc client = new JsonRpcBaseSepoliaUsdc(props(server.url(), 1000));
            int before = server.calls();
            assertThatThrownBy(() -> client.receipt("0x12")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.authorizationState("nope", NONCE, 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(server.calls()).isEqualTo(before);
        }
    }

    private static String indexedLog(String body, long index) {
        return body.substring(0, body.length() - 1) + ",\"logIndex\":\"0x" + Long.toHexString(index) + "\"}";
    }

    @Test
    void receiptExposesTheLogIndexOfEveryTransferAndAuthorization() {
        String logs = String.join(
                ",",
                indexedLog(transferLog(USDC, 10_000), 7),
                indexedLog(authorizationLog(USDC), 8),
                indexedLog(transferLog(USDC, 5), 9));
        try (StubRpcServer server = stub((m, p, n) -> Reply.result(receiptJson("0x1", logs)))) {
            JsonRpcBaseSepoliaUsdc client = new JsonRpcBaseSepoliaUsdc(props(server.url(), 1000));
            UsdcReceipt receipt = client.receipt(TX).orElseThrow();
            assertThat(receipt.transfers())
                    .containsExactly(new UsdcTransfer(PAYER, PAYEE, 10_000, 7), new UsdcTransfer(PAYER, PAYEE, 5, 9));
            assertThat(receipt.authorizationUses()).containsExactly(new UsdcAuthorizationUse(PAYER, NONCE, 8));
            assertThat(receipt.authorizationsUsed()).containsExactly(PAYER + ":" + NONCE);
        }
    }

    @Test
    void findAuthorizationTxDoesNotTrustTheNodesFilter() {
        String otherNonce = "0x" + "11".repeat(32);
        String wrongNonce = authorizationLog(USDC).replace(NONCE, otherNonce);
        String wrongAuthorizer = authorizationLog(USDC).replace(PAYER.substring(2), PAYEE.substring(2));
        String wrongEvent = authorizationLog(USDC)
                .replace(JsonRpcBaseSepoliaUsdc.AUTHORIZATION_USED_TOPIC, JsonRpcBaseSepoliaUsdc.TRANSFER_TOPIC);
        String removed = authorizationLog(USDC).replace("\"data\":", "\"removed\":true,\"data\":");
        String bad = String.join(",", wrongNonce, wrongAuthorizer, wrongEvent, removed);
        try (StubRpcServer server = stub((m, p, n) -> Reply.result("[" + bad + "]"))) {
            JsonRpcBaseSepoliaUsdc client = new JsonRpcBaseSepoliaUsdc(props(server.url(), 1000));
            assertThat(client.findAuthorizationTx(PAYER, NONCE, 100, 199)).isEmpty();
        }
        // The genuine log after the bad ones is the one returned.
        String genuine = authorizationLog(USDC).replace(TX, "0x" + "ee".repeat(32));
        try (StubRpcServer server = stub((m, p, n) -> Reply.result("[" + bad + "," + genuine + "]"))) {
            JsonRpcBaseSepoliaUsdc client = new JsonRpcBaseSepoliaUsdc(props(server.url(), 1000));
            assertThat(client.findAuthorizationTx(PAYER, NONCE, 100, 199)).contains("0x" + "ee".repeat(32));
        }
    }

    @Test
    void jsonRpcRateLimitCodesAreTransientAndRetried() {
        for (int code : new int[] {-32005, -32016}) {
            try (StubRpcServer server = stub((m, p, n) -> n == 2
                    ? Reply.json("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":" + code
                            + ",\"message\":\"secret-detail\"}}")
                    : Reply.result("{\"number\":\"0x1\",\"timestamp\":\"0x2\"}"))) {
                JsonRpcBaseSepoliaUsdc client = new JsonRpcBaseSepoliaUsdc(props(server.url(), 1000));
                assertThat(client.block(BlockTag.LATEST)).isEqualTo(new ChainBlock(1, 2));
                assertThat(server.methods().stream()
                                .filter("eth_getBlockByNumber"::equals)
                                .count())
                        .isEqualTo(2);
            }
        }
    }

    @Test
    void aPersistentRateLimitExhaustsTheRetriesWithoutEchoingTheMessage() {
        try (StubRpcServer server = stub((m, p, n) -> Reply.json(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32005,\"message\":\"secret-detail\"}}"))) {
            JsonRpcBaseSepoliaUsdc client = new JsonRpcBaseSepoliaUsdc(props(server.url(), 1000));
            assertThatThrownBy(() -> client.block(BlockTag.LATEST))
                    .isInstanceOf(ChainUnavailableException.class)
                    .hasMessageNotContaining("secret-detail");
            assertThat(server.methods().stream()
                            .filter("eth_getBlockByNumber"::equals)
                            .count())
                    .isEqualTo(3);
        }
    }

    @Test
    void aRateLimitMessageWithoutTheCodeIsNotTreatedAsTransient() {
        try (StubRpcServer server = stub((m, p, n) -> Reply.json(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"rate limit exceeded\"}}"))) {
            JsonRpcBaseSepoliaUsdc client = new JsonRpcBaseSepoliaUsdc(props(server.url(), 1000));
            assertThatThrownBy(() -> client.block(BlockTag.LATEST)).isInstanceOf(ChainUnavailableException.class);
            assertThat(server.methods().stream()
                            .filter("eth_getBlockByNumber"::equals)
                            .count())
                    .isEqualTo(1);
        }
    }
}
