package io.github.orhanyarkin.x402.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.orhanyarkin.x402.client.PropertiesSpendGuard;
import io.github.orhanyarkin.x402.client.SpendGuard;
import io.github.orhanyarkin.x402.client.X402ClientProperties;
import io.github.orhanyarkin.x402.client.X402PaymentInterceptor;
import io.github.orhanyarkin.x402.core.TestnetAssets;
import io.github.orhanyarkin.x402.evm.PaymentSigner;
import io.github.orhanyarkin.x402.evm.PrivateKeyPaymentSigner;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Verifies the fail-closed startup contract in {@link X402ClientAutoConfiguration}'s Javadoc
 * without ever printing a private key: no assertion below inspects raw {@code System.out}/{@code
 * System.err} content, only {@link org.springframework.boot.test.context.assertj.AssertableApplicationContext}
 * state and exception types/messages.
 */
class X402ClientAutoConfigurationTest {

    // The well-known "cow" test private key (keccak256("cow")); see PrivateKeyPaymentSignerTest.
    private static final String COW_PRIVATE_KEY = "0xc85ef7d79691fe79573b1a7064c19c1a9819ebdbd1faaab1a8ec92344438aaf4";
    private static final String PAY_TO = "0x209693Bc6afc0C5328bA36FaF03C514EF312287C";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(X402ClientAutoConfiguration.class));

    @Test
    void noPrivateKeyMeansNoClientBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PaymentSigner.class);
            assertThat(context).doesNotHaveBean(SpendGuard.class);
            assertThat(context).doesNotHaveBean(X402PaymentInterceptor.class);
        });
    }

    @Test
    void aBlankPrivateKeyStillTriggersStartupFailureWithoutEchoingIt() {
        runner.withPropertyValues("x402.client.private-key=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void privateKeyWithoutMaxAmountFailsStartupWithoutEchoingTheKey() {
        runner.withPropertyValues("x402.client.private-key=" + COW_PRIVATE_KEY, "x402.client.allowed-pay-to=" + PAY_TO)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasStackTraceContaining("x402.client.max-amount-per-request")
                            .satisfies(e -> assertThat(rootCauseMessage(e)).doesNotContain(COW_PRIVATE_KEY));
                });
    }

    @Test
    void privateKeyWithoutAllowedPayToFailsStartupWithoutEchoingTheKey() {
        runner.withPropertyValues(
                        "x402.client.private-key=" + COW_PRIVATE_KEY, "x402.client.max-amount-per-request=1000")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasStackTraceContaining("x402.client.allowed-pay-to")
                            .satisfies(e -> assertThat(rootCauseMessage(e)).doesNotContain(COW_PRIVATE_KEY));
                });
    }

    @Test
    void anInvalidPrivateKeyFailsStartupWithoutEchoingIt() {
        String invalidKey = "not-a-valid-private-key";
        runner.withPropertyValues(
                        "x402.client.private-key=" + invalidKey,
                        "x402.client.max-amount-per-request=1000",
                        "x402.client.allowed-pay-to=" + PAY_TO)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .satisfies(e -> assertThat(rootCauseMessage(e)).doesNotContain(invalidKey));
                });
    }

    @Test
    void aMalformedAllowedPayToEntryFailsStartupWithoutEchoingTheKeyOrTheEntry() {
        String malformedEntry = "not-an-address";
        runner.withPropertyValues(
                        "x402.client.private-key=" + COW_PRIVATE_KEY,
                        "x402.client.max-amount-per-request=1000",
                        "x402.client.allowed-pay-to=" + malformedEntry)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .satisfies(e -> assertThat(rootCauseMessage(e))
                                    .doesNotContain(COW_PRIVATE_KEY)
                                    .doesNotContain(malformedEntry));
                });
    }

    @Test
    void aCompleteConfigurationRegistersAllThreeClientBeans() {
        runner.withPropertyValues(
                        "x402.client.private-key=" + COW_PRIVATE_KEY,
                        "x402.client.max-amount-per-request=1000",
                        "x402.client.allowed-pay-to=" + PAY_TO)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PaymentSigner.class);
                    assertThat(context.getBean(PaymentSigner.class)).isInstanceOf(PrivateKeyPaymentSigner.class);
                    assertThat(context).hasSingleBean(SpendGuard.class);
                    assertThat(context.getBean(SpendGuard.class)).isInstanceOf(PropertiesSpendGuard.class);
                    assertThat(context).hasSingleBean(X402PaymentInterceptor.class);
                });
    }

    @Test
    void userDefinedSpendGuardReplacesThePropertiesDefaultAndIsUsedByTheInterceptor() {
        runner.withUserConfiguration(CustomSpendGuardConfig.class)
                .withPropertyValues(
                        "x402.client.private-key=" + COW_PRIVATE_KEY,
                        "x402.client.max-amount-per-request=1000",
                        "x402.client.allowed-pay-to=" + PAY_TO)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SpendGuard.class);
                    assertThat(context.getBean(SpendGuard.class)).isNotInstanceOf(PropertiesSpendGuard.class);
                    // The interceptor bean method receives whichever single SpendGuard bean exists
                    // via constructor injection, so its successful creation here (there is only one
                    // candidate SpendGuard bean in this context) is itself proof the custom guard is
                    // the one wired into it.
                    assertThat(context).hasSingleBean(X402PaymentInterceptor.class);
                });
    }

    @Test
    void userDefinedPaymentSignerWithoutPrivateKeyStillYieldsTheInterceptor() {
        runner.withUserConfiguration(CustomPaymentSignerConfig.class)
                .withPropertyValues("x402.client.max-amount-per-request=1000", "x402.client.allowed-pay-to=" + PAY_TO)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PaymentSigner.class);
                    assertThat(context.getBean(PaymentSigner.class)).isNotInstanceOf(PrivateKeyPaymentSigner.class);
                    assertThat(context).hasSingleBean(SpendGuard.class);
                    assertThat(context).hasSingleBean(X402PaymentInterceptor.class);
                });
    }

    @Test
    void anExactPlaintextHostStartsOnTheTestnetAndIsEmptyByDefault() {
        runner.withPropertyValues(
                        "x402.client.private-key=" + COW_PRIVATE_KEY,
                        "x402.client.max-amount-per-request=1000",
                        "x402.client.allowed-pay-to=" + PAY_TO,
                        "x402.client.allowed-plaintext-hosts=seller-api",
                        "spring.http.clients.redirects=dont-follow")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(X402ClientProperties.class).allowedPlaintextHosts())
                            .containsExactly("seller-api");
                    assertThat(context).hasSingleBean(X402PaymentInterceptor.class);
                });
        runner.run(context -> assertThat(
                        context.getBean(X402ClientProperties.class).allowedPlaintextHosts())
                .isEmpty());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "follow", "follow-when-possible"})
    void aPlaintextHostWithoutDontFollowRedirectsFailsStartup(String redirects) {
        java.util.List<String> props = new java.util.ArrayList<>(List.of(
                "x402.client.private-key=" + COW_PRIVATE_KEY,
                "x402.client.max-amount-per-request=1000",
                "x402.client.allowed-pay-to=" + PAY_TO,
                "x402.client.allowed-plaintext-hosts=seller-api"));
        if (!redirects.isEmpty()) {
            props.add("spring.http.clients.redirects=" + redirects);
        }
        runner.withPropertyValues(props.toArray(String[]::new)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("spring.http.clients.redirects=dont-follow")
                    .satisfies(e -> assertThat(rootCauseMessage(e)).doesNotContain(COW_PRIVATE_KEY));
        });
    }

    @Test
    void anEmptyPlaintextListNeedsNoRedirectSetting() {
        runner.withPropertyValues(
                        "x402.client.private-key=" + COW_PRIVATE_KEY,
                        "x402.client.max-amount-per-request=1000",
                        "x402.client.allowed-pay-to=" + PAY_TO)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void aWildcardPlaintextHostFailsStartupWithoutEchoingIt() {
        runner.withPropertyValues(
                        "x402.client.private-key=" + COW_PRIVATE_KEY,
                        "x402.client.max-amount-per-request=1000",
                        "x402.client.allowed-pay-to=" + PAY_TO,
                        "x402.client.allowed-plaintext-hosts=*.internal",
                        "spring.http.clients.redirects=dont-follow")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasStackTraceContaining("x402.client.allowed-plaintext-hosts")
                            .satisfies(e -> assertThat(rootCauseMessage(e))
                                    .doesNotContain("*.internal")
                                    .doesNotContain(COW_PRIVATE_KEY));
                });
    }

    @Test
    void aNonEmptyPlaintextListFailsOnANetworkOtherThanBaseSepolia() {
        X402ClientProperties properties = new X402ClientProperties(null, 1000L, List.of(PAY_TO), List.of("seller-api"));

        assertThat(X402ClientAutoConfiguration.requireAllowedPlaintextHosts(properties, TestnetAssets.NETWORK))
                .containsExactly("seller-api");
        // Base mainnet and Ethereum mainnet: the plaintext exception is never available there.
        assertThatThrownBy(() -> X402ClientAutoConfiguration.requireAllowedPlaintextHosts(properties, "eip155:8453"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> X402ClientAutoConfiguration.requireAllowedPlaintextHosts(properties, "eip155:1"))
                .isInstanceOf(IllegalStateException.class);
        // An empty list is fine anywhere.
        assertThat(X402ClientAutoConfiguration.requireAllowedPlaintextHosts(
                        new X402ClientProperties(null, 1000L, List.of(PAY_TO), List.of()), "eip155:8453"))
                .isEmpty();
    }

    private static String rootCauseMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return String.valueOf(root.getMessage());
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomSpendGuardConfig {
        @Bean
        SpendGuard customSpendGuard() {
            return mock(SpendGuard.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomPaymentSignerConfig {
        @Bean
        PaymentSigner customPaymentSigner() {
            PaymentSigner signer = mock(PaymentSigner.class);
            when(signer.address()).thenReturn(PAY_TO);
            return signer;
        }
    }
}
