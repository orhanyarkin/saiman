package io.github.orhanyarkin.x402.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TestnetAssetsTest {

    private static final String PAY_TO = "0x209693Bc6afc0C5328bA36FaF03C514EF312287C";

    // Ethereum mainnet -- deliberately not a substring or superstring of TestnetAssets.NETWORK
    // ("eip155:84532": note "eip155:8453" -- Base mainnet -- IS a substring of it), so
    // message-content assertions below are unambiguous.
    private static final String REJECTED_NETWORK = "eip155:1";
    private static final String REJECTED_ASSET = "0x0000000000000000000000000000000000dEaD";

    private static PaymentRequirements supported() {
        return supported(Map.of("name", TestnetAssets.USDC_NAME, "version", TestnetAssets.USDC_VERSION));
    }

    private static PaymentRequirements supported(Map<String, Object> extra) {
        return new PaymentRequirements(
                TestnetAssets.SCHEME_EXACT,
                TestnetAssets.NETWORK,
                "10000",
                TestnetAssets.USDC_ADDRESS,
                PAY_TO,
                60,
                extra);
    }

    @Test
    void acceptsTheSupportedRequirements() {
        assertThatCode(() -> TestnetAssets.requireSupported(supported())).doesNotThrowAnyException();
    }

    @Test
    void acceptsTheAssetAddressRegardlessOfCase() {
        PaymentRequirements requirements = new PaymentRequirements(
                TestnetAssets.SCHEME_EXACT,
                TestnetAssets.NETWORK,
                "10000",
                TestnetAssets.USDC_ADDRESS.toLowerCase(Locale.ROOT),
                PAY_TO,
                60,
                Map.of("name", TestnetAssets.USDC_NAME, "version", TestnetAssets.USDC_VERSION));
        assertThatCode(() -> TestnetAssets.requireSupported(requirements)).doesNotThrowAnyException();
    }

    @Test
    void acceptsAbsentAssetTransferMethodAndPaymentFlow() {
        // Already covered by supported() (extra has no such keys), asserted explicitly for clarity.
        assertThat(supported().extraString("assetTransferMethod")).isNull();
        assertThat(supported().extraString("paymentFlow")).isNull();
        assertThatCode(() -> TestnetAssets.requireSupported(supported())).doesNotThrowAnyException();
    }

    @Test
    void acceptsExplicitDefaultAssetTransferMethodAndPaymentFlow() {
        PaymentRequirements requirements = supported(Map.of(
                "name",
                TestnetAssets.USDC_NAME,
                "version",
                TestnetAssets.USDC_VERSION,
                "assetTransferMethod",
                "eip3009",
                "paymentFlow",
                "authorization"));
        assertThatCode(() -> TestnetAssets.requireSupported(requirements)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAnyOtherScheme() {
        PaymentRequirements requirements = new PaymentRequirements(
                "upto",
                TestnetAssets.NETWORK,
                "10000",
                TestnetAssets.USDC_ADDRESS,
                PAY_TO,
                60,
                Map.of("name", TestnetAssets.USDC_NAME, "version", TestnetAssets.USDC_VERSION));
        assertThatThrownBy(() -> TestnetAssets.requireSupported(requirements))
                .isInstanceOf(UnsupportedPaymentException.class);
    }

    @Test
    void rejectsAnyOtherNetwork() {
        PaymentRequirements requirements = new PaymentRequirements(
                TestnetAssets.SCHEME_EXACT,
                REJECTED_NETWORK,
                "10000",
                TestnetAssets.USDC_ADDRESS,
                PAY_TO,
                60,
                Map.of("name", TestnetAssets.USDC_NAME, "version", TestnetAssets.USDC_VERSION));
        assertThatThrownBy(() -> TestnetAssets.requireSupported(requirements))
                .isInstanceOf(UnsupportedPaymentException.class)
                .hasMessageContaining(TestnetAssets.NETWORK);
    }

    @Test
    void rejectsAnyOtherAsset() {
        PaymentRequirements requirements = new PaymentRequirements(
                TestnetAssets.SCHEME_EXACT,
                TestnetAssets.NETWORK,
                "10000",
                REJECTED_ASSET,
                PAY_TO,
                60,
                Map.of("name", TestnetAssets.USDC_NAME, "version", TestnetAssets.USDC_VERSION));
        assertThatThrownBy(() -> TestnetAssets.requireSupported(requirements))
                .isInstanceOf(UnsupportedPaymentException.class);
    }

    @Test
    void rejectsMismatchedExtraNameOrVersion() {
        assertThatThrownBy(() -> TestnetAssets.requireSupported(supported(Map.of("name", "Not USDC", "version", "2"))))
                .isInstanceOf(UnsupportedPaymentException.class);
        assertThatThrownBy(() -> TestnetAssets.requireSupported(supported(Map.of("name", "USDC", "version", "3"))))
                .isInstanceOf(UnsupportedPaymentException.class);
    }

    @Test
    void rejectsMissingExtraNameOrVersion() {
        assertThatThrownBy(() -> TestnetAssets.requireSupported(supported(Map.of())))
                .isInstanceOf(UnsupportedPaymentException.class);
    }

    @Test
    void rejectsAnyOtherAssetTransferMethodOrPaymentFlow() {
        assertThatThrownBy(() -> TestnetAssets.requireSupported(supported(Map.of(
                        "name", TestnetAssets.USDC_NAME,
                        "version", TestnetAssets.USDC_VERSION,
                        "assetTransferMethod", "permit2"))))
                .isInstanceOf(UnsupportedPaymentException.class);
        assertThatThrownBy(() -> TestnetAssets.requireSupported(supported(Map.of(
                        "name", TestnetAssets.USDC_NAME,
                        "version", TestnetAssets.USDC_VERSION,
                        "paymentFlow", "escrow"))))
                .isInstanceOf(UnsupportedPaymentException.class);
    }

    @Test
    void acceptsTheUpfrontPaymentFlowButNoUnknownOrNonStringFlow() {
        assertThatCode(() -> TestnetAssets.requireSupported(supported(Map.of(
                        "name", TestnetAssets.USDC_NAME,
                        "version", TestnetAssets.USDC_VERSION,
                        "paymentFlow", "upfront"))))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> TestnetAssets.requireSupported(supported(Map.of(
                        "name", TestnetAssets.USDC_NAME,
                        "version", TestnetAssets.USDC_VERSION,
                        "paymentFlow", "UPFRONT"))))
                .isInstanceOf(UnsupportedPaymentException.class);
        assertThatThrownBy(() -> TestnetAssets.requireSupported(supported(Map.of(
                        "name", TestnetAssets.USDC_NAME,
                        "version", TestnetAssets.USDC_VERSION,
                        "paymentFlow", 1))))
                .isInstanceOf(UnsupportedPaymentException.class);
    }

    @Test
    void rejectionMessagesNeverEchoTheRejectedNetworkOrAsset() {
        PaymentRequirements rejectedNetwork = new PaymentRequirements(
                TestnetAssets.SCHEME_EXACT,
                REJECTED_NETWORK,
                "10000",
                TestnetAssets.USDC_ADDRESS,
                PAY_TO,
                60,
                Map.of("name", TestnetAssets.USDC_NAME, "version", TestnetAssets.USDC_VERSION));
        assertThatThrownBy(() -> TestnetAssets.requireSupported(rejectedNetwork))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(REJECTED_NETWORK));

        PaymentRequirements rejectedAsset = new PaymentRequirements(
                TestnetAssets.SCHEME_EXACT,
                TestnetAssets.NETWORK,
                "10000",
                REJECTED_ASSET,
                PAY_TO,
                60,
                Map.of("name", TestnetAssets.USDC_NAME, "version", TestnetAssets.USDC_VERSION));
        assertThatThrownBy(() -> TestnetAssets.requireSupported(rejectedAsset))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(REJECTED_ASSET));
    }
}
