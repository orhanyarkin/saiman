package io.github.orhanyarkin.x402.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.orhanyarkin.x402.core.TestnetAssets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlaintextHostAllowlistTest {

    @Test
    void emptyByDefaultOnAnyNetwork() {
        assertThat(PlaintextHostAllowlist.requireValidAndNormalize(List.of(), "eip155:8453"))
                .isEmpty();
        assertThat(PlaintextHostAllowlist.requireValidAndNormalize(null, TestnetAssets.NETWORK))
                .isEmpty();
    }

    @Test
    void exactHostNamesAreLowercased() {
        assertThat(PlaintextHostAllowlist.requireValidAndNormalize(
                        List.of("Seller-API", "seller-api.saiman.internal", "10.0.0.5"), TestnetAssets.NETWORK))
                .containsExactly("seller-api", "seller-api.saiman.internal", "10.0.0.5");
    }

    @ParameterizedTest
    @ValueSource(strings = {"eip155:8453", "eip155:1", "eip155:84531", "base-sepolia", ""})
    void aNonEmptyListIsRefusedOffTheBaseSepoliaTestnet(String network) {
        assertThatThrownBy(() -> PlaintextHostAllowlist.requireValidAndNormalize(List.of("seller-api"), network))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base Sepolia");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "*",
                "*.seller-api",
                "seller-*",
                ".seller-api",
                "seller-api.",
                "seller..api",
                "seller-api:8081",
                "http://seller-api",
                "seller-api/v1",
                "10.0.0.0/8",
                "user@seller-api",
                " seller-api",
                "seller-api ",
                "",
                "   ",
                "-seller",
                "seller-",
                "sellér-api",
                "[::1]"
            })
    void anythingButAnExactHostNameIsRefusedWithoutEchoingIt(String entry) {
        assertThatThrownBy(() -> PlaintextHostAllowlist.requireValidAndNormalize(
                        Arrays.asList("seller-api", entry), TestnetAssets.NETWORK))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> {
                    if (!entry.isBlank()) {
                        assertThat(e.getMessage()).doesNotContain(entry);
                    }
                });
    }

    @Test
    void aNullEntryIsRefused() {
        assertThatThrownBy(() -> PlaintextHostAllowlist.requireValidAndNormalize(
                        Arrays.asList("seller-api", null), TestnetAssets.NETWORK))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void containsMatchesOnlyTheWholeHostCaseInsensitively() {
        List<String> allowed = List.of("seller-api");
        assertThat(PlaintextHostAllowlist.contains(allowed, "SELLER-API")).isTrue();
        assertThat(PlaintextHostAllowlist.contains(allowed, "seller-api.evil.com"))
                .isFalse();
        assertThat(PlaintextHostAllowlist.contains(allowed, "evilseller-api")).isFalse();
        assertThat(PlaintextHostAllowlist.contains(allowed, "seller-ap")).isFalse();
    }
}
