package io.github.orhanyarkin.x402.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Round-trips {@link X402Codec} against the x402 v2 spec's example payloads, vendored under
 * {@code src/test/resources/spec} (see the NOTICE file there for source and commit).
 */
class X402CodecTest {

    private final X402Codec codec = new X402Codec();

    @Test
    void roundTripsThePaymentRequiredSpecExample() throws IOException {
        String json = readSpecFixture("payment-required.json");
        PaymentRequired decoded = codec.decodePaymentRequired(base64(json));

        assertThat(decoded.x402Version()).isEqualTo(2);
        assertThat(decoded.error()).isEqualTo("PAYMENT-SIGNATURE header is required");
        assertThat(decoded.resource().url()).isEqualTo("https://api.example.com/premium-data");
        assertThat(decoded.resource().tags()).containsExactly("market-data", "finance");
        assertThat(decoded.accepts()).hasSize(1);

        PaymentRequirements requirements = decoded.accepts().get(0);
        assertThat(requirements.scheme()).isEqualTo(TestnetAssets.SCHEME_EXACT);
        assertThat(requirements.network()).isEqualTo(TestnetAssets.NETWORK);
        assertThat(requirements.amount()).isEqualTo("10000");
        assertThat(requirements.asset()).isEqualTo(TestnetAssets.USDC_ADDRESS);
        assertThat(requirements.payTo()).isEqualTo("0x209693Bc6afc0C5328bA36FaF03C514EF312287C");
        assertThat(requirements.maxTimeoutSeconds()).isEqualTo(60);
        assertThat(requirements.extraString("name")).isEqualTo("USDC");
        assertThat(requirements.extraString("version")).isEqualTo("2");

        // Re-encoding and re-decoding must reproduce the same object (field-for-field).
        String reEncoded = codec.encodePaymentRequired(decoded);
        PaymentRequired roundTripped = codec.decodePaymentRequired(reEncoded);
        assertThat(roundTripped).isEqualTo(decoded);
    }

    @Test
    void decodePaymentRequiredToleratesUnknownProperties() {
        // A hypothetical future field, or a different (newer) seller/facilitator implementation.
        String json = """
                {"x402Version":2,"resource":{"url":"https://api.example.com/x"},
                 "accepts":[{"scheme":"exact","network":"eip155:84532","amount":"1","asset":"0x036CbD53842c5426634e7929541eC2318f3dCF7e","payTo":"0x209693Bc6afc0C5328bA36FaF03C514EF312287C","maxTimeoutSeconds":60}],
                 "somethingNewSellersAddedLater":"ignored"}
                """;
        assertThat(codec.decodePaymentRequired(base64(json)).x402Version()).isEqualTo(2);
    }

    @Test
    void roundTripsThePaymentPayloadSpecExample() throws IOException {
        String json = readSpecFixture("payment-payload.json");
        PaymentPayload decoded = codec.decodePaymentPayload(base64(json));

        assertThat(decoded.x402Version()).isEqualTo(2);
        assertThat(decoded.accepted().network()).isEqualTo(TestnetAssets.NETWORK);
        assertThat(decoded.payload().signature())
                .isEqualTo(
                        "0x2d6a7588d6acca505cbf0d9a4a227e0c52c6c34008c8e8986a1283259764173608a2ce6496642e377d6da8dbbf5836e9bd15092f9ecab05ded3d6293af148b571c");

        Eip3009Authorization authorization = decoded.payload().authorization();
        assertThat(authorization.from()).isEqualTo("0x857b06519E91e3A54538791bDbb0E22373e36b66");
        assertThat(authorization.to()).isEqualTo("0x209693Bc6afc0C5328bA36FaF03C514EF312287C");
        assertThat(authorization.value()).isEqualTo("10000");
        assertThat(authorization.validAfter()).isEqualTo("1740672089");
        assertThat(authorization.validBefore()).isEqualTo("1740672154");
        assertThat(authorization.nonce())
                .isEqualTo("0xf3746613c2d920b5fdabc0856f2aeb2d4f88ee6037b8cc5d04a71a4462f13480");

        String reEncoded = codec.encodePaymentPayload(decoded);
        assertThat(codec.decodePaymentPayload(reEncoded)).isEqualTo(decoded);
    }

    @Test
    void decodePaymentPayloadRejectsUnknownProperties() {
        // The one strict decode: PAYMENT-SIGNATURE is client-submitted, payment-critical input.
        String json = """
                {"x402Version":2,
                 "accepted":{"scheme":"exact","network":"eip155:84532","amount":"1","asset":"0x036CbD53842c5426634e7929541eC2318f3dCF7e","payTo":"0x209693Bc6afc0C5328bA36FaF03C514EF312287C","maxTimeoutSeconds":60},
                 "payload":{"signature":"0x2d6a7588d6acca505cbf0d9a4a227e0c52c6c34008c8e8986a1283259764173608a2ce6496642e377d6da8dbbf5836e9bd15092f9ecab05ded3d6293af148b571c",
                            "authorization":{"from":"0x857b06519E91e3A54538791bDbb0E22373e36b66","to":"0x209693Bc6afc0C5328bA36FaF03C514EF312287C","value":"1","validAfter":"0","validBefore":"9999999999","nonce":"0xf3746613c2d920b5fdabc0856f2aeb2d4f88ee6037b8cc5d04a71a4462f13480"}},
                 "somethingAttackerAdded":"x"}
                """;
        assertThatThrownBy(() -> codec.decodePaymentPayload(base64(json))).isInstanceOf(X402CodecException.class);
    }

    @Test
    void roundTripsSuccessfulSettlementResponseSpecExample() throws IOException {
        String json = readSpecFixture("settlement-response-success.json");
        SettlementResponse decoded = codec.decodeSettlementResponse(base64(json));

        assertThat(decoded.success()).isTrue();
        assertThat(decoded.errorReason()).isNull();
        assertThat(decoded.network()).isEqualTo(TestnetAssets.NETWORK);
        assertThat(decoded.transaction())
                .isEqualTo("0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");

        assertThat(codec.decodeSettlementResponse(codec.encodeSettlementResponse(decoded)))
                .isEqualTo(decoded);
    }

    @Test
    void roundTripsFailedSettlementResponseSpecExample() throws IOException {
        String json = readSpecFixture("settlement-response-failure.json");
        SettlementResponse decoded = codec.decodeSettlementResponse(base64(json));

        assertThat(decoded.success()).isFalse();
        assertThat(decoded.errorReason()).isEqualTo("insufficient_funds");
        assertThat(decoded.transaction()).isEmpty();
    }

    @Test
    void decodeSettlementResponseToleratesUnknownProperties() {
        // A /settle decode failure is an ambiguous money outcome, so this must not throw.
        String json = """
                {"success":true,"transaction":"0x1","network":"eip155:84532","errorMessage":null,
                 "extensionResponses":{"bazaar":{"ok":true}},"aFieldThisVersionDoesNotKnowAbout":42}
                """;
        SettlementResponse decoded = codec.decodeSettlementResponse(base64(json));
        assertThat(decoded.success()).isTrue();
        assertThat(decoded.extensionResponses()).containsKey("bazaar");
    }

    @Test
    void readJsonDecodesVerifyResponseFacilitatorBodies() throws IOException {
        String successJson = readSpecFixture("verify-response-success.json");
        VerifyResponse success = codec.readJson(successJson, VerifyResponse.class);
        assertThat(success.isValid()).isTrue();
        assertThat(success.invalidReason()).isNull();
        assertThat(success.payer()).isEqualTo("0x857b06519E91e3A54538791bDbb0E22373e36b66");

        String errorJson = readSpecFixture("verify-response-error.json");
        VerifyResponse error = codec.readJson(errorJson, VerifyResponse.class);
        assertThat(error.isValid()).isFalse();
        assertThat(error.invalidReason()).isEqualTo("insufficient_funds");
    }

    @Test
    void readJsonToleratesUnknownPropertiesLikeFacilitatorBodiesGenerally() {
        String json = """
                {"isValid":true,"payer":"0x1","fromAFutureFacilitatorVersion":true}
                """;
        assertThat(codec.readJson(json, VerifyResponse.class).isValid()).isTrue();
    }

    @Test
    void writeJsonThenReadJsonRoundTrips() {
        VerifyResponse original =
                new VerifyResponse(false, "insufficient_funds", "balance too low", "0x1", null, null, null);
        String json = codec.writeJson(original);
        assertThat(codec.readJson(json, VerifyResponse.class)).isEqualTo(original);
    }

    @Test
    void writeJsonOmitsNullFields() {
        VerifyResponse response = new VerifyResponse(true, null, null, null, null, null, null);
        String json = codec.writeJson(response);
        assertThat(json).doesNotContain("invalidReason").doesNotContain("null");
    }

    @Test
    void rejectsDuplicateJsonKeys() {
        String json =
                base64("{\"success\":true,\"success\":false,\"transaction\":\"0x1\",\"network\":\"eip155:84532\"}");
        assertThatThrownBy(() -> codec.decodeSettlementResponse(json)).isInstanceOf(X402CodecException.class);
    }

    @Test
    void rejectsScalarCoercionOfWireStrings() {
        // `value` is a wire string (AssetAmount/Eip3009Authorization); a bare JSON number must
        // not be silently coerced into it.
        String json = base64(
                "{\"x402Version\":2,\"accepted\":{\"scheme\":\"exact\",\"network\":\"eip155:84532\",\"amount\":\"1\",\"asset\":\"0x036CbD53842c5426634e7929541eC2318f3dCF7e\",\"payTo\":\"0x209693Bc6afc0C5328bA36FaF03C514EF312287C\",\"maxTimeoutSeconds\":60},"
                        + "\"payload\":{\"signature\":\"0x2d6a7588d6acca505cbf0d9a4a227e0c52c6c34008c8e8986a1283259764173608a2ce6496642e377d6da8dbbf5836e9bd15092f9ecab05ded3d6293af148b571c\","
                        + "\"authorization\":{\"from\":\"0x857b06519E91e3A54538791bDbb0E22373e36b66\",\"to\":\"0x209693Bc6afc0C5328bA36FaF03C514EF312287C\",\"value\":10000,\"validAfter\":\"0\",\"validBefore\":\"9999999999\",\"nonce\":\"0xf3746613c2d920b5fdabc0856f2aeb2d4f88ee6037b8cc5d04a71a4462f13480\"}}}");
        assertThatThrownBy(() -> codec.decodePaymentPayload(json)).isInstanceOf(X402CodecException.class);
    }

    @Test
    void rejectsFloatWhereAnIntIsExpected() {
        String json = base64(
                "{\"x402Version\":2.5,\"resource\":{\"url\":\"https://x\"},"
                        + "\"accepts\":[{\"scheme\":\"exact\",\"network\":\"eip155:84532\",\"amount\":\"1\",\"asset\":\"0x036CbD53842c5426634e7929541eC2318f3dCF7e\",\"payTo\":\"0x209693Bc6afc0C5328bA36FaF03C514EF312287C\",\"maxTimeoutSeconds\":60}]}");
        assertThatThrownBy(() -> codec.decodePaymentRequired(json)).isInstanceOf(X402CodecException.class);
    }

    @Test
    void rejectsOversizedDecodedInput() {
        String huge = "x".repeat(X402Codec.MAX_DECODED_BYTES + 1);
        String tooBig = base64("{\"success\":true,\"transaction\":\"" + huge + "\",\"network\":\"eip155:84532\"}");
        assertThatThrownBy(() -> codec.decodeSettlementResponse(tooBig)).isInstanceOf(X402CodecException.class);
    }

    @Test
    void rejectsOversizedEncodedInputBeforeDecodingBase64() {
        // A header longer than MAX_ENCODED_CHARS is rejected outright -- it should never reach
        // the base64 decoder at all, even if (as here) it isn't valid base64 in the first place.
        String tooLong = "A".repeat(X402Codec.MAX_ENCODED_CHARS + 1);
        assertThatThrownBy(() -> codec.decodeSettlementResponse(tooLong)).isInstanceOf(X402CodecException.class);
    }

    @Test
    void rejectsInvalidBase64() {
        assertThatThrownBy(() -> codec.decodeSettlementResponse("not-valid-base64!!!"))
                .isInstanceOf(X402CodecException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void rejectsEmptyOrBlankHeaderValues(String blank) {
        assertThatThrownBy(() -> codec.decodePaymentPayload(blank.isEmpty() ? blank : base64(blank)))
                .isInstanceOf(X402CodecException.class);
    }

    @Test
    void decodeErrorMessageNamesOnlyThePropertyPathNeverAValue() {
        String secretLikeSignature = "0xDEADBEEF_this_looks_like_a_signature_but_is_not_valid_json";
        String malformed = base64("{\"signature\":\"" + secretLikeSignature + "\", not valid json");

        assertThatThrownBy(() -> codec.decodePaymentPayload(malformed))
                .isInstanceOf(X402CodecException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain(secretLikeSignature);
                    assertThat(e.getMessage()).doesNotContain(malformed);
                });
    }

    @Test
    void decodeExceptionDoesNotChainTheUnderlyingJacksonCauseAnywhereInItsStackTrace() {
        // The cause chain (not just getMessage()) must not leak input either: a naive
        // `log.error("failed", e)` call prints the full chain, including every cause's message.
        String secretMarker = "SHOULD-NEVER-APPEAR-IN-STACK-TRACE-0xCAFEBABE";
        String malformed = base64("{\"" + secretMarker + "\": not valid json at all !!!");

        X402CodecException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                X402CodecException.class, () -> codec.decodePaymentPayload(malformed));

        assertThat(thrown.getCause()).isNull();

        StringWriter stringWriter = new StringWriter();
        thrown.printStackTrace(new PrintWriter(stringWriter));
        assertThat(stringWriter.toString()).doesNotContain(secretMarker);
    }

    private static String readSpecFixture(String name) throws IOException {
        try (InputStream in = X402CodecTest.class.getResourceAsStream("/spec/" + name)) {
            if (in == null) {
                throw new IOException("spec fixture not found on classpath: spec/" + name);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            in.transferTo(buffer);
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    private static String base64(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void unknownPropertyNamesCannotInjectIntoTheMessage() {
        String marker = "FORGED_LOG_LINE";
        String hostileName = "X\r\n2026-09-28 ERROR " + marker + "\u001b[31m" + "A".repeat(10_000);
        String json =
                "{\"" + hostileName.replace("\r", "\\r").replace("\n", "\\n").replace("\u001b", "\\u001b")
                        + "\":1,\"x402Version\":2}";

        X402CodecException e = org.junit.jupiter.api.Assertions.assertThrows(
                X402CodecException.class, () -> codec.decodePaymentPayload(base64(json)));

        assertThat(e.getMessage()).matches("[\\x20-\\x7E]{0,200}").doesNotContain(marker);
        assertThat(e.getCause()).isNull();
    }
}
