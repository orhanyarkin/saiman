package io.github.orhanyarkin.x402.evm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.x402.core.Eip3009Authorization;
import io.github.orhanyarkin.x402.core.PaymentPayload;
import io.github.orhanyarkin.x402.core.X402Codec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

class Eip3009TypedDataTest {

    // Two well-known Ethereum test private keys (keccak256("cow") and keccak256("bob")), used
    // only to exercise signing math -- never real funds, never mainnet (rule 1, CLAUDE.md).
    private static final PrivateKeyPaymentSigner PAYER =
            new PrivateKeyPaymentSigner("0xc85ef7d79691fe79573b1a7064c19c1a9819ebdbd1faaab1a8ec92344438aaf4");
    private static final PrivateKeyPaymentSigner OTHER_SIGNER =
            new PrivateKeyPaymentSigner("0x38e47a7b719dce63662aeaf43440326f551b8a7ee198cee35cb5d517f2d296a2");

    private static Eip3009Authorization sampleAuthorization() {
        return new Eip3009Authorization(
                PAYER.address(),
                "0x209693Bc6afc0C5328bA36FaF03C514EF312287C",
                "10000",
                "1740672089",
                "1740672154",
                Eip3009TypedData.randomNonce());
    }

    @Test
    void randomNonceIsThirtyTwoBytesAndVariesBetweenCalls() {
        String first = Eip3009TypedData.randomNonce();
        String second = Eip3009TypedData.randomNonce();

        assertThat(first).startsWith("0x").hasSize(66); // 0x + 64 hex chars
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void signThenRecoverReturnsTheSignerAddress() {
        Eip3009Authorization authorization = sampleAuthorization();
        String signature = PAYER.signTransferWithAuthorization(authorization);

        String recovered = Eip3009TypedData.recoverSigner(authorization, signature);

        assertThat(recovered).isEqualToIgnoringCase(PAYER.address());
        assertThat(Eip3009TypedData.verify(authorization, signature)).isTrue();
    }

    @Test
    void verifyReturnsFalseRatherThanThrowingForAWrongSigner() {
        Eip3009Authorization authorization = sampleAuthorization();
        String signature = OTHER_SIGNER.signTransferWithAuthorization(authorization);

        assertThat(Eip3009TypedData.verify(authorization, signature)).isFalse();
    }

    @Test
    void verifyReturnsFalseRatherThanThrowingForAMalformedSignature() {
        assertThat(Eip3009TypedData.verify(sampleAuthorization(), "0xnotasignature"))
                .isFalse();
    }

    @Test
    void differentAuthorizationsProduceDifferentDigests() {
        Eip3009Authorization a = sampleAuthorization();
        Eip3009Authorization b = sampleAuthorization(); // different random nonce

        assertThat(Eip3009TypedData.digest(a)).isNotEqualTo(Eip3009TypedData.digest(b));
    }

    @ParameterizedTest
    @MethodSource("fieldTamperers")
    void tamperingWithAnyFieldChangesTheRecoveredAddress(String label, UnaryOperator<Eip3009Authorization> tamper) {
        Eip3009Authorization original = sampleAuthorization();
        String signature = PAYER.signTransferWithAuthorization(original);

        Eip3009Authorization tampered = tamper.apply(original);

        String originalRecovered = Eip3009TypedData.recoverSigner(original, signature);
        String tamperedRecovered = Eip3009TypedData.recoverSigner(tampered, signature);

        assertThat(tamperedRecovered).as(label).isNotEqualTo(originalRecovered);
    }

    static Stream<Arguments> fieldTamperers() {
        return Stream.of(
                Arguments.of("from", (UnaryOperator<Eip3009Authorization>) a -> new Eip3009Authorization(
                        OTHER_SIGNER.address(), a.to(), a.value(), a.validAfter(), a.validBefore(), a.nonce())),
                Arguments.of("to", (UnaryOperator<Eip3009Authorization>) a -> new Eip3009Authorization(
                        a.from(), OTHER_SIGNER.address(), a.value(), a.validAfter(), a.validBefore(), a.nonce())),
                Arguments.of("value", (UnaryOperator<Eip3009Authorization>) a -> new Eip3009Authorization(
                        a.from(), a.to(), "10001", a.validAfter(), a.validBefore(), a.nonce())),
                Arguments.of("validAfter", (UnaryOperator<Eip3009Authorization>) a -> new Eip3009Authorization(
                        a.from(), a.to(), a.value(), "1740672090", a.validBefore(), a.nonce())),
                Arguments.of("validBefore", (UnaryOperator<Eip3009Authorization>) a ->
                        new Eip3009Authorization(a.from(), a.to(), a.value(), a.validAfter(), "1740672155", a.nonce())),
                Arguments.of("nonce", (UnaryOperator<Eip3009Authorization>) a -> new Eip3009Authorization(
                        a.from(), a.to(), a.value(), a.validAfter(), a.validBefore(), Eip3009TypedData.randomNonce())));
    }

    @Test
    void tamperingWithTheSignatureChangesTheRecoveredAddress() {
        Eip3009Authorization authorization = sampleAuthorization();
        String signature = PAYER.signTransferWithAuthorization(authorization);

        // Flip a nibble in the middle of r, keeping length and recovery id intact.
        int flipIndex = 10;
        char original = signature.charAt(flipIndex);
        char flipped = original == '0' ? '1' : '0';
        String tamperedSignature = signature.substring(0, flipIndex) + flipped + signature.substring(flipIndex + 1);

        String originalRecovered = Eip3009TypedData.recoverSigner(authorization, signature);

        // A single flipped bit in r/s almost always produces either a different recoverable
        // address, or a signature this class's own low-s/range checks reject outright -- both
        // outcomes prove tampering broke the signature; only "recovers to the same address"
        // would be a real failure.
        try {
            String tamperedRecovered = Eip3009TypedData.recoverSigner(authorization, tamperedSignature);
            assertThat(tamperedRecovered).isNotEqualTo(originalRecovered);
        } catch (IllegalArgumentException expected) {
            // Recovery failed outright: also an acceptable proof that tampering broke it.
        }
    }

    @Test
    void toSignatureHexAndParseSignatureHexRoundTrip() {
        Eip3009Authorization authorization = sampleAuthorization();
        String signatureHex = PAYER.signTransferWithAuthorization(authorization);

        Sign.SignatureData parsed = Eip3009TypedData.parseSignatureHex(signatureHex);

        assertThat(Eip3009TypedData.toSignatureHex(parsed)).isEqualTo(signatureHex);
    }

    @Test
    void parseSignatureHexRejectsWrongLength() {
        assertThatThrownBy(() -> Eip3009TypedData.parseSignatureHex("0x1234"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseSignatureHexRejectsInvalidRecoveryByte() {
        String withBadV = PAYER.signTransferWithAuthorization(sampleAuthorization());
        String tampered = withBadV.substring(0, withBadV.length() - 2) + "1a"; // v = 26, not 27/28
        assertThatThrownBy(() -> Eip3009TypedData.parseSignatureHex(tampered))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseSignatureHexRejectsHighS() {
        // s = n - 1: in range for a "raw" ECDSA signature, but above n/2, so it must be rejected
        // as non-canonical (the low-s malleability guard both this class and USDC's FiatTokenV2
        // enforce). r is an arbitrary valid value (1); v is 27.
        String r = "0000000000000000000000000000000000000000000000000000000000000001";
        String highS = "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364140"; // n - 1
        String signature = "0x" + r + highS + "1b";
        assertThatThrownBy(() -> Eip3009TypedData.parseSignatureHex(signature))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lower half");
    }

    @Test
    void signerAlwaysEmitsLowSAndAValidRecoveryByteAcrossManyAuthorizations() {
        // 256 independently-signed, independently-random authorizations: parseSignatureHex
        // itself enforces v in {27, 28} and s in [1, n/2], so simply not throwing here across
        // every iteration *is* the assertion that the signer always emits canonical signatures.
        for (int i = 0; i < 256; i++) {
            Eip3009Authorization authorization = new Eip3009Authorization(
                    PAYER.address(),
                    "0x209693Bc6afc0C5328bA36FaF03C514EF312287C",
                    Long.toString(i + 1),
                    "0",
                    "9999999999",
                    Eip3009TypedData.randomNonce());
            String signatureHex = PAYER.signTransferWithAuthorization(authorization);
            Eip3009TypedData.parseSignatureHex(signatureHex);
        }
    }

    @Test
    void matchesTheX402SpecPaymentPayloadKnownAnswer() throws IOException {
        // The x402 v2 spec's own exact-EVM PaymentPayload example (see
        // src/test/resources/spec/NOTICE for source and commit).
        String json = readSpecFixture("payment-payload.json");
        PaymentPayload payload = new X402Codec()
                .decodePaymentPayload(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)));
        Eip3009Authorization authorization = payload.payload().authorization();
        String signature = payload.payload().signature();

        assertThat(Eip3009TypedData.recoverSigner(authorization, signature))
                .isEqualToIgnoringCase(authorization.from())
                .isEqualToIgnoringCase("0x857b06519E91e3A54538791bDbb0E22373e36b66");

        assertThat(Numeric.toHexString(Eip3009TypedData.domainSeparator(authorization)))
                .isEqualTo("0x71f17a3b2ff373b803d70a5a07c046c1a2bc8e89c09ef722fcb047abe94c9818");
        assertThat(Numeric.toHexString(Eip3009TypedData.transferWithAuthorizationTypeHash(authorization)))
                .isEqualTo("0x7c7c6cdb67a18743f49ec6fa9b35f50d52ed05cbed4cc592e13b44501c1a2267");
    }

    private static String readSpecFixture(String name) throws IOException {
        try (InputStream in = Eip3009TypedDataTest.class.getResourceAsStream("/spec/" + name)) {
            if (in == null) {
                throw new IOException("spec fixture not found on classpath: spec/" + name);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            in.transferTo(buffer);
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
