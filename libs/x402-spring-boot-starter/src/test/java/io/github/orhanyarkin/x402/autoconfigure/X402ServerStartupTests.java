package io.github.orhanyarkin.x402.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.orhanyarkin.x402.server.RequiresPayment;
import io.github.orhanyarkin.x402.testing.FakeFacilitator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-0008's fail-closed startup validation: a missing/invalid seller {@code pay-to}, a
 * non-allowlisted or non-https facilitator host, a zero {@code @RequiresPayment} price, and a
 * facilitator that doesn't advertise {@code exact} on {@code eip155:84532} each stop application
 * startup -- with no {@code enabled} flag to bypass it, and without ever printing the rejected
 * value (checked with {@link OutputCaptureExtension}).
 */
@ExtendWith(OutputCaptureExtension.class)
class X402ServerStartupTests {

    private static final FakeFacilitator FACILITATOR = new FakeFacilitator();

    @AfterAll
    static void stopFacilitator() {
        FACILITATOR.close();
    }

    private static WebApplicationContextRunner baseRunner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        X402ServerAutoConfiguration.class,
                        X402ObservationAutoConfiguration.class,
                        WebMvcAutoConfiguration.class,
                        RestClientAutoConfiguration.class))
                .withPropertyValues("x402.server.facilitator.url=" + FACILITATOR.url());
    }

    @Test
    void missingPayToFailsStartupWhenARequiresPaymentHandlerExists() {
        baseRunner()
                .withUserConfiguration(PaidControllerConfig.class)
                .withPropertyValues("test.price=10000")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context)).hasMessageContaining("pay-to");
                });
    }

    @Test
    void invalidPayToFailsStartupAndNeverAppearsInOutput(CapturedOutput output) {
        String plantedInvalidPayTo = "not-a-valid-address-marker-zzqx";

        Throwable thrown = org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> new SpringApplicationBuilder(RealStartupApplication.class)
                        .web(WebApplicationType.SERVLET)
                        .properties(
                                "server.port=0",
                                "x402.server.facilitator.url=" + FACILITATOR.url(),
                                "x402.server.pay-to=" + plantedInvalidPayTo,
                                "test.price=10000")
                        .run());
        Throwable root = thrown;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pay-to")
                .hasMessageContaining("0x-prefixed 20-byte hex address");

        assertThat(output.getAll()).doesNotContain(plantedInvalidPayTo);
    }

    @Test
    void zeroAddressPayToFailsStartup() {
        baseRunner()
                .withUserConfiguration(PaidControllerConfig.class)
                .withPropertyValues("test.price=10000", "x402.server.pay-to=0x" + "0".repeat(40))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context)).hasMessageContaining("zero address");
                });
    }

    @Test
    void usdcContractAddressPayToFailsStartup() {
        baseRunner()
                .withUserConfiguration(PaidControllerConfig.class)
                .withPropertyValues(
                        "test.price=10000",
                        "x402.server.pay-to=" + io.github.orhanyarkin.x402.core.TestnetAssets.USDC_ADDRESS)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context)).hasMessageContaining("USDC contract");
                });
    }

    @Test
    void zeroPriceFailsStartup() {
        baseRunner()
                .withUserConfiguration(PaidControllerConfig.class)
                .withPropertyValues("test.price=0", "x402.server.pay-to=0x1111111111111111111111111111111111111111")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context)).hasMessageContaining("greater than zero");
                });
    }

    @Test
    void nonAllowlistedFacilitatorHostFailsStartup() {
        // The host allowlist is validated unconditionally in HttpFacilitatorClient's own
        // constructor (not gated on a @RequiresPayment handler existing), so no paid handler is
        // needed here to exercise it.
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        X402ServerAutoConfiguration.class,
                        X402ObservationAutoConfiguration.class,
                        WebMvcAutoConfiguration.class,
                        RestClientAutoConfiguration.class))
                .withPropertyValues("x402.server.facilitator.url=https://not-x402-dot-org.example/facilitator")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context)).hasMessageContaining("not allowlisted");
                });
    }

    @Test
    void nonLoopbackHttpFacilitatorUrlFailsStartup() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        X402ServerAutoConfiguration.class,
                        X402ObservationAutoConfiguration.class,
                        WebMvcAutoConfiguration.class,
                        RestClientAutoConfiguration.class))
                .withPropertyValues("x402.server.facilitator.url=http://x402.org/facilitator")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context)).hasMessageContaining("https");
                });
    }

    @Test
    void facilitatorNotSupportingExactOnTestnetFailsStartup() {
        // Unlike the host allowlist, the /supported network handshake only runs when a
        // @RequiresPayment handler exists (RequiresPaymentRegistry.afterSingletonsInstantiated),
        // so this test needs one, unlike the two above.
        try (UnsupportedFacilitator unsupported = new UnsupportedFacilitator()) {
            baseRunner()
                    .withUserConfiguration(PaidControllerConfig.class)
                    .withPropertyValues(
                            "test.price=10000", "x402.server.pay-to=0x1111111111111111111111111111111111111111")
                    .withPropertyValues("x402.server.facilitator.url=" + unsupported.url())
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(rootCause(context)).hasMessageContaining("does not advertise support");
                    });
        }
    }

    @Test
    void facilitatorHandshakeDoesNotRunWithoutAPaidHandler() {
        // The blocking review fix (item 2): with no @RequiresPayment handler, the context must
        // start cleanly even against a facilitator that would fail the handshake -- proving the
        // handshake genuinely does not run, rather than running and happening to pass.
        try (UnsupportedFacilitator unsupported = new UnsupportedFacilitator()) {
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            X402ServerAutoConfiguration.class,
                            X402ObservationAutoConfiguration.class,
                            WebMvcAutoConfiguration.class,
                            RestClientAutoConfiguration.class))
                    .withPropertyValues("x402.server.facilitator.url=" + unsupported.url())
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Test
    void asynchronousReturnTypeFailsStartup() {
        baseRunner()
                .withUserConfiguration(AsyncPaidControllerConfig.class)
                .withPropertyValues("x402.server.pay-to=0x1111111111111111111111111111111111111111")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context)).hasMessageContaining("asynchronous or reactive");
                });
    }

    @Test
    void maxTimeoutSecondsOutOfRangeFailsStartup() {
        baseRunner().withPropertyValues("x402.server.max-timeout-seconds=301").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context)).hasMessageContaining("max-timeout-seconds");
        });
        baseRunner().withPropertyValues("x402.server.max-timeout-seconds=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context)).hasMessageContaining("max-timeout-seconds");
        });
    }

    @Test
    void interceptorNotRegisteredFailsStartupWhenAnApplicationBypassesWebMvcConfigurer() {
        // Probe from review round 1: an application extending WebMvcConfigurationSupport directly
        // (instead of implementing WebMvcConfigurer) never has addInterceptors called on it by
        // Spring, so RequiresPaymentInterceptor's bean exists but is never actually registered.
        // This must now fail startup rather than silently serving paid content unpaid.
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        X402ServerAutoConfiguration.class,
                        X402ObservationAutoConfiguration.class,
                        WebMvcAutoConfiguration.class,
                        RestClientAutoConfiguration.class))
                .withUserConfiguration(PaidControllerConfig.class, BypassingWebMvcConfiguration.class)
                .withPropertyValues(
                        "test.price=10000",
                        "x402.server.pay-to=0x1111111111111111111111111111111111111111",
                        "x402.server.facilitator.url=" + FACILITATOR.url())
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context)).hasMessageContaining("is not registered");
                });
    }

    private static Throwable rootCause(
            org.springframework.boot.test.context.assertj.AssertableWebApplicationContext context) {
        Throwable failure = context.getStartupFailure();
        assertThat(failure).isNotNull();
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Configuration(proxyBeanMethods = false)
    static class PaidControllerConfig {

        @Bean
        PaidController paidController() {
            return new PaidController();
        }
    }

    @RestController
    static class PaidController {

        @GetMapping("/paid")
        @RequiresPayment(price = "${test.price}")
        String paid() {
            return "paid";
        }
    }

    // @Configuration + @EnableAutoConfiguration, not @SpringBootApplication: the latter's
    // component scan would also pick up PaidControllerConfig/PaidController above (nested in the
    // same package), registering the paid endpoint twice.
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class RealStartupApplication {

        @Bean
        PaidController paidController() {
            return new PaidController();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AsyncPaidControllerConfig {

        @Bean
        AsyncPaidController asyncPaidController() {
            return new AsyncPaidController();
        }
    }

    @RestController
    static class AsyncPaidController {

        @GetMapping("/paid-async")
        @RequiresPayment(price = "10000")
        java.util.concurrent.Callable<String> paid() {
            return () -> "paid";
        }
    }

    /**
     * Registered as a bean, not via {@code @EnableWebMvc}: Boot's own {@code
     * WebMvcAutoConfiguration.EnableWebMvcConfiguration} is {@code
     * @ConditionalOnMissingBean(WebMvcConfigurationSupport.class)}, so providing any subclass as a
     * bean makes Boot back off entirely -- including never calling any {@code WebMvcConfigurer}'s
     * {@code addInterceptors}, exactly like an application that extends this class directly
     * instead of implementing {@code WebMvcConfigurer}.
     */
    @Configuration(proxyBeanMethods = false)
    static class BypassingWebMvcConfiguration
            extends org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport {}

    /** A minimal {@code /supported} responder that never advertises {@code exact} on any network. */
    private static final class UnsupportedFacilitator implements AutoCloseable {

        private final HttpServer server;

        UnsupportedFacilitator() {
            try {
                this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            server.createContext("/supported", exchange -> {
                byte[] body = "{\"kinds\":[],\"extensions\":[],\"signers\":{}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
