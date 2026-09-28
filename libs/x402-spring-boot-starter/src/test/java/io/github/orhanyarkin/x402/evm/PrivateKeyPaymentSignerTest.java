package io.github.orhanyarkin.x402.evm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.x402.core.Eip3009Authorization;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

class PrivateKeyPaymentSignerTest {

    // The well-known "cow" test private key (keccak256("cow")); see Eip712MailVectorTest.
    private static final String COW_PRIVATE_KEY = "0xc85ef7d79691fe79573b1a7064c19c1a9819ebdbd1faaab1a8ec92344438aaf4";
    private static final String COW_ADDRESS = "0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826";

    @Test
    void derivesTheKnownAddressFromTheCowPrivateKey() {
        PrivateKeyPaymentSigner signer = new PrivateKeyPaymentSigner(COW_PRIVATE_KEY);
        assertThat(signer.address()).isEqualToIgnoringCase(COW_ADDRESS);
    }

    @Test
    void acceptsAPrivateKeyWithoutTheHexPrefix() {
        PrivateKeyPaymentSigner signer = new PrivateKeyPaymentSigner(COW_PRIVATE_KEY.substring(2));
        assertThat(signer.address()).isEqualToIgnoringCase(COW_ADDRESS);
    }

    @Test
    void toStringNeverContainsThePrivateKey() {
        PrivateKeyPaymentSigner signer = new PrivateKeyPaymentSigner(COW_PRIVATE_KEY);

        String rendered = signer.toString();

        assertThat(rendered).doesNotContain(COW_PRIVATE_KEY).doesNotContain(COW_PRIVATE_KEY.substring(2));
        assertThat(rendered).contains(COW_ADDRESS);
    }

    @Test
    void signedAuthorizationRecoversToOwnAddress() {
        PrivateKeyPaymentSigner signer = new PrivateKeyPaymentSigner(COW_PRIVATE_KEY);
        Eip3009Authorization authorization = new Eip3009Authorization(
                signer.address(),
                "0x209693Bc6afc0C5328bA36FaF03C514EF312287C",
                "10000",
                "1740672089",
                "1740672154",
                Eip3009TypedData.randomNonce());

        String signature = signer.signTransferWithAuthorization(authorization);

        assertThat(Eip3009TypedData.recoverSigner(authorization, signature)).isEqualToIgnoringCase(signer.address());
    }

    @ParameterizedTest
    @NullAndEmptySource
    void rejectsNullOrEmptyPrivateKey(String privateKey) {
        assertThatIllegalArgumentException().isThrownBy(() -> new PrivateKeyPaymentSigner(privateKey));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-hex-at-all", "0xzz", "0x", "12 34"})
    void rejectsMalformedPrivateKeysWithoutEchoingThem(String malformed) {
        assertThatThrownBy(() -> new PrivateKeyPaymentSigner(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(malformed));
    }

    @Test
    void rejectsAZeroPrivateKeyWithoutEchoingIt() {
        String zero = "0x" + "0".repeat(64);
        assertThatThrownBy(() -> new PrivateKeyPaymentSigner(zero))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(zero));
    }

    @Test
    void acceptsOneBelowTheCurveOrder() {
        BigInteger n = Sign.CURVE_PARAMS.getN();
        String hex = "0x" + Numeric.toHexStringNoPrefixZeroPadded(n.subtract(BigInteger.ONE), 64);
        assertThatCode(() -> new PrivateKeyPaymentSigner(hex)).doesNotThrowAnyException();
    }

    @Test
    void rejectsExactlyTheCurveOrder() {
        BigInteger n = Sign.CURVE_PARAMS.getN();
        String hex = "0x" + Numeric.toHexStringNoPrefixZeroPadded(n, 64);
        assertThatThrownBy(() -> new PrivateKeyPaymentSigner(hex)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOneBeyondTheCurveOrder() {
        BigInteger n = Sign.CURVE_PARAMS.getN();
        String hex = "0x" + Numeric.toHexStringNoPrefixZeroPadded(n.add(BigInteger.ONE), 64);
        assertThatThrownBy(() -> new PrivateKeyPaymentSigner(hex)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTheMaximumPossible256BitValue() {
        // 2^256 - 1: 64 hex 'f' characters, far above the curve order.
        String allOnes = "0x" + "f".repeat(64);
        assertThatThrownBy(() -> new PrivateKeyPaymentSigner(allOnes)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSixtyThreeOrSixtyFiveHexDigitKeys() {
        assertThatThrownBy(() -> new PrivateKeyPaymentSigner("0x" + "1".repeat(63)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PrivateKeyPaymentSigner("0x" + "1".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
